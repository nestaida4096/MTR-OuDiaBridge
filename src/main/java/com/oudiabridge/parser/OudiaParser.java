package com.oudiabridge.parser;

import java.io.*;
import java.util.*;

public class OudiaParser {

    public static ParseResult parseOud2(File file) throws IOException {
        List<StationData> stations = new ArrayList<>();
        List<TrainData> trains = new ArrayList<>();
        String routeName = "";

        // 文字コード指定（.oud2 は Shift_JIS または UTF-8）
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            String currentSection = "";
            String currentDirection = ""; // "Kudari" or "Nobori"

            TrainData currentTrain = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";")) continue;

                // セクション切替判定
                if (line.equals("Rosen.")) {
                    currentSection = "ROSEN";
                } else if (line.equals("Eki.")) {
                    currentSection = "EKI";
                    stations.add(new StationData());
                } else if (line.equals("Kudari.")) {
                    currentDirection = "Kudari";
                } else if (line.equals("Nobori.")) {
                    currentDirection = "Nobori";
                } else if (line.equals("Ressya.")) {
                    currentSection = "RESSYA";
                    currentTrain = new TrainData();
                    currentTrain.direction = currentDirection;
                    trains.add(currentTrain);
                } else if (line.equals(".")) {
                    // ブロックの終わり
                    if ("RESSYA".equals(currentSection)) {
                        currentSection = "";
                    } else if ("EKI".equals(currentSection)) {
                        currentSection = "";
                    }
                }

                // データプロパティ解析
                switch (currentSection) {
                    case "ROSEN":
                        if (line.startsWith("Rosenmei=")) {
                            routeName = line.substring("Rosenmei=".length());
                        }
                        break;

                    case "EKI":
                        if (line.startsWith("Ekimei=")) {
                            int lastIndex = stations.size() - 1;
                            if (lastIndex >= 0) {
                                stations.get(lastIndex).name = line.substring("Ekimei=".length());
                            }
                        }
                        break;

                    case "RESSYA":
                        if (currentTrain == null) break;

                        if (line.startsWith("Houkou=")) {
                            currentTrain.direction = line.substring("Houkou=".length());
                        } else if (line.startsWith("Syubetsu=")) {
                            currentTrain.typeIndex = Integer.parseInt(line.substring("Syubetsu=".length()));
                        } else if (line.startsWith("EkiJikoku=")) {
                            parseTimetable(line.substring("EkiJikoku=".length()), currentTrain, stations.size());
                        }
                        break;
                }
            }
        }

        return new ParseResult(routeName, stations, trains);
    }

    /**
     * EkiJikoku文字列の解析
     * 例: ,,,1;114354$0,1;114550/114620$0,2$0,2;115111/$0
     */
    private static void parseTimetable(String rawData, TrainData train, int stationCount) {
        // カンマ区切りで各駅のデータに分割
        String[] stationEntries = rawData.split(",", -1);

        for (int i = 0; i < stationEntries.length; i++) {
            String entry = stationEntries[i].trim();
            StopTime stopTime = new StopTime();
            stopTime.stationIndex = i;

            if (entry.isEmpty()) {
                // 時刻データなし（採時対象外または未設定）
                stopTime.stopType = StopType.NONE;
            } else {
                // 番線情報などの属性（$0 や $1）を分離
                String timePart = entry.contains("$") ? entry.split("\\$")[0] : entry;

                if (timePart.startsWith("1;")) {
                    // 停車 (例: 1;114550/114620 または 1;114354)
                    stopTime.stopType = StopType.STOP;
                    parseArrivalAndDeparture(timePart.substring(2), stopTime);
                } else if (timePart.startsWith("2;")) {
                    // 通過（通過時刻の指定あり 例: 2;115111/）
                    stopTime.stopType = StopType.PASS;
                    parseArrivalAndDeparture(timePart.substring(2), stopTime);
                } else if (timePart.equals("2")) {
                    // 通過（通過時刻の指定なし）
                    stopTime.stopType = StopType.PASS;
                } else if (timePart.startsWith("1")) {
                    // 時刻記述がない停車設定
                    stopTime.stopType = StopType.STOP;
                } else {
                    stopTime.stopType = StopType.NONE;
                }
            }

            train.stops.add(stopTime);
        }
    }

    private static void parseArrivalAndDeparture(String timeStr, StopTime stopTime) {
        if (timeStr.contains("/")) {
            // 着時刻 / 発時刻
            String[] times = timeStr.split("/", -1);
            stopTime.arrivalTime = times[0];
            stopTime.departureTime = times.length > 1 ? times[1] : "";
        } else {
            // 単一時刻（始発の発時刻、または終着の着時刻など）
            stopTime.departureTime = timeStr;
        }
    }

    // --- データ構造クラス ---

    public enum StopType {
        STOP, // 停車
        PASS, // 通過
        NONE  // 経由なし・未設定
    }

    public static class ParseResult {
        public final String routeName;
        public final List<StationData> stations;
        public final List<TrainData> trains;
        public final RouteSyncConfig syncConfig;

        public ParseResult(String routeName, List<StationData> stations, List<TrainData> trains) {
            this(routeName, stations, trains, new RouteSyncConfig(stations.size(), trains.size()));
        }

        public ParseResult(String routeName, List<StationData> stations, List<TrainData> trains, RouteSyncConfig syncConfig) {
            this.routeName = routeName;
            this.stations = stations;
            this.trains = trains;
            this.syncConfig = syncConfig;
        }
    }

    public static class StationData {
        public String name = "";
    }

    public static class TrainData {
        public String name = "";
        public String direction = ""; // Kudari / Nobori
        public int typeIndex = 0;     // 列車種別（0:普通, 1:快速 など）
        public List<StopTime> stops = new ArrayList<>();
    }

    public static class StopTime {
        public int stationIndex;      // 何番目の駅か
        public StopType stopType = StopType.NONE; // 停車・通過区分
        public String arrivalTime = "";   // 着時刻 ("114550" など)
        public String departureTime = ""; // 発時刻 ("114620" など)
    }

    /**
     * StopTime へのエイリアスクラス（型名の表記ブレによるビルド不具合を防ぎます）
     */
    public static class StopData extends StopTime {
    }

    public static class RouteSyncConfig {
        public final List<StationMapping> stationMappings = new ArrayList<>();
        public final List<TrainSyncConfig> trainConfigs = new ArrayList<>();
        public String depotName = "";
        public String depotLineName = "";
        public long depotId = -1L;
        public long startStationId = -1L;

        public RouteSyncConfig(int stationCount, int trainCount) {
            for (int i = 0; i < stationCount; i++) {
                stationMappings.add(new StationMapping());
            }
            for (int i = 0; i < trainCount; i++) {
                trainConfigs.add(new TrainSyncConfig());
            }
        }
    }

    public static class StationMapping {
        public long mtrStationId = -1L;
        public String mtrStationName = "";
        public boolean enabled = true;
    }

    public static class TrainSyncConfig {
        public String depotName = "";
        public String depotLineName = "";
        public long depotId = -1L;
        public double speedMultiplier = 1.0D;
        public int departureOffsetTicks = 0;
        public boolean applyTimingAdjustment = true;
        public String firstArrivalTime = "";
        public String firstDepartureTime = "";
        public long startStationId = -1L;
        public int minimumDwellTicks = 20;
        public int maxRailSpeed = 0;
    }
}