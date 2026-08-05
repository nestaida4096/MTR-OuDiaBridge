package com.oudiabridge.parser;

import java.io.*;
import java.util.*;

public class OudiaParser {

    public static ParseResult parseOud2(File file) throws IOException {
        List<StationData> stations = new ArrayList<>();
        List<TrainData> trains = new ArrayList<>();
        String routeName = "";

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            String currentSection = "";

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";")) continue;

                // セクション検出
                if (line.equals("Rosen.")) {
                    currentSection = "ROSEN";
                } else if (line.equals("Eki.")) {
                    currentSection = "EKI";
                    stations.add(new StationData());
                } else if (line.equals("Dia.")) {
                    currentSection = "DIA";
                } else if (line.equals("Ressya.")) {
                    currentSection = "RESSYA";
                    trains.add(new TrainData());
                } else if (line.equals(".")) {
                    currentSection = "";
                }

                // データ解析
                switch (currentSection) {
                    case "ROSEN":
                        if (line.startsWith("Rosenmei=")) routeName = line.substring(9);
                        break;
                    case "EKI":
                        if (line.startsWith("Ekimei=")) {
                            int lastIndex = stations.size() - 1;
                            stations.get(lastIndex).name = line.substring(7);
                        }
                        break;
                    case "RESSYA":
                        // EkiJikoku=解析（時刻表）
                        if (line.startsWith("EkiJikoku=")) {
                            parseTimetable(line, trains.get(trains.size() - 1));
                        }
                        break;
                }
            }
        }

        return new ParseResult(routeName, stations, trains);
    }

    private static void parseTimetable(String ekiJikoku, TrainData train) {
        // EkiJikoku=1;114854$0,1;115050/115120$0,...形式を解析
        String[] parts = ekiJikoku.substring(10).split("\\$");
        for (String part : parts) {
            if (part.contains(";")) {
                String[] stationTime = part.split(";");
                if (stationTime.length >= 2) {
                    train.stops.add(new StopTime(stationTime[1]));
                }
            }
        }
    }

    // データクラス
    public static class ParseResult {
        public final String routeName;
        public final List<StationData> stations;
        public final List<TrainData> trains;

        public ParseResult(String routeName, List<StationData> stations, List<TrainData> trains) {
            this.routeName = routeName;
            this.stations = stations;
            this.trains = trains;
        }
    }

    public static class StationData {
        public String name = "";
    }

    public static class TrainData {
        public List<StopTime> stops = new ArrayList<>();
    }

    public static class StopTime {
        public String time;  // "114854" = 11:48:54

        public StopTime(String time) {
            this.time = time;
        }
    }
}
