package nest.oudiabridge.parser;

import java.io.*;
import java.util.*;

public class OudiaParser {

    public static ParseResult parseOud2(File file) throws IOException {
        List<RawStation> rawStations = new ArrayList<>();
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
                    rawStations.add(new RawStation());
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
                        int lastIndex = rawStations.size() - 1;
                        if (lastIndex >= 0) {
                            RawStation currentStation = rawStations.get(lastIndex);
                            if (line.startsWith("Ekimei=")) {
                                currentStation.name = line.substring("Ekimei=".length());
                            } else if (line.startsWith("BrunchCoreEkiIndex=")) {
                                try {
                                    currentStation.branchCoreIndex = Integer.parseInt(line.substring("BrunchCoreEkiIndex=".length()));
                                    currentStation.isBranch = true;
                                } catch (NumberFormatException ignored) {}
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
                            parseTimetable(line.substring("EkiJikoku=".length()), currentTrain, rawStations);
                        }
                        break;
                }
            }
        }

        // 分岐駅（派生駅）を排除した浄化済みの駅リストを構築
        List<StationData> cleanStations = new ArrayList<>();
        // rawIndex -> cleanIndex のマッピング
        Map<Integer, Integer> indexMap = new HashMap<>();

        for (int i = 0; i < rawStations.size(); i++) {
            RawStation rs = rawStations.get(i);
            if (rs.isBranch) {
                // 派生駅の場合、紐づく基幹駅の cleanIndex にマッピング
                int coreCleanIndex = indexMap.getOrDefault(rs.branchCoreIndex, rs.branchCoreIndex);
                indexMap.put(i, coreCleanIndex);
            } else {
                // 独自の駅として残す
                int newIndex = cleanStations.size();
                StationData sd = new StationData();
                sd.name = rs.name;
                cleanStations.add(sd);
                indexMap.put(i, newIndex);
            }
        }

        // 各列車のストップ情報にある stationIndex を正規化された cleanIndex に書き換える
        for (TrainData train : trains) {
            for (StopTime stop : train.stops) {
                if (indexMap.containsKey(stop.stationIndex)) {
                    stop.stationIndex = indexMap.get(stop.stationIndex);
                }
            }
        }

        return new ParseResult(routeName, cleanStations, trains);
    }

    private static void parseTimetable(String rawData, TrainData train, List<RawStation> rawStations) {
        String[] stationEntries = rawData.split(",", -1);
        boolean isNobori = "Nobori".equalsIgnoreCase(train.direction);
        int stationCount = rawStations.size();

        for (int i = 0; i < stationEntries.length; i++) {
            String entry = stationEntries[i].trim();
            StopTime stopTime = new StopTime();

            int rawIndex = isNobori ? (stationCount - 1 - i) : i;
            stopTime.stationIndex = rawIndex;

            if (entry.isEmpty()) {
                stopTime.stopType = StopType.NONE;
            } else {
                String timePart = entry.contains("$") ? entry.split("\\$")[0] : entry;

                if (timePart.startsWith("1;")) {
                    stopTime.stopType = StopType.STOP;
                    parseArrivalAndDeparture(timePart.substring(2), stopTime);
                } else if (timePart.startsWith("2;")) {
                    stopTime.stopType = StopType.PASS;
                    parseArrivalAndDeparture(timePart.substring(2), stopTime);
                } else if (timePart.equals("2")) {
                    stopTime.stopType = StopType.PASS;
                } else if (timePart.startsWith("1")) {
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
            String[] times = timeStr.split("/", -1);
            stopTime.arrivalTime = times[0];
            stopTime.departureTime = times.length > 1 ? times[1] : "";
        } else {
            stopTime.departureTime = timeStr;
        }
    }

    // --- 内部用データ構造 ---
    private static class RawStation {
        public String name = "";
        public boolean isBranch = false;
        public int branchCoreIndex = -1;
    }

    // --- 公開データ構造クラス ---

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