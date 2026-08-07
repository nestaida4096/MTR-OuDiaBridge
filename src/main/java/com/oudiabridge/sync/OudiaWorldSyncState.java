package com.oudiabridge.sync;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * サーバー・クライアント共通のOuDia同期データ保持用シングルトンクラス
 * 複数のダイヤプロジェクトを管理し、選択された作業プロジェクトを保持します。
 */
public final class OudiaWorldSyncState {
    private static final OudiaWorldSyncState INSTANCE = new OudiaWorldSyncState();

    // 複数プロジェクト管理: プロジェクトID/名前 -> DiagramProject
    private final Map<String, DiagramProject> projects = new HashMap<>();
    private String activeProjectId = "";

    private OudiaWorldSyncState() {
    }

    public static OudiaWorldSyncState getInstance() {
        return INSTANCE;
    }

    public Map<String, DiagramProject> getProjects() {
        return projects;
    }

    public String getActiveProjectId() {
        return activeProjectId;
    }

    public void setActiveProjectId(String activeProjectId) {
        this.activeProjectId = activeProjectId == null ? "" : activeProjectId;
    }

    public DiagramProject getActiveProject() {
        if (activeProjectId != null && projects.containsKey(activeProjectId)) {
            return projects.get(activeProjectId);
        }
        if (!projects.isEmpty()) {
            return projects.values().iterator().next();
        }
        return null;
    }

    public DiagramProject getOrCreateProject(String projectId, String name) {
        return projects.computeIfAbsent(projectId, id -> new DiagramProject(id, name));
    }

    public void removeProject(String projectId) {
        projects.remove(projectId);
        if (activeProjectId.equals(projectId)) {
            activeProjectId = projects.isEmpty() ? "" : projects.keySet().iterator().next();
        }
    }

    public void clear() {
        projects.clear();
        activeProjectId = "";
    }

    /**
     * 時刻文字列 ("835", "600", "114550", "1145", "11:45:50" 等) を
     * 現実時間の「日の始まりからの秒数」に正確に変換する
     */
    public static int parseTimeToSeconds(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return -1;
        }
        String clean = timeStr.replaceAll("[^0-9]", "");

        if (clean.isEmpty()) {
            return -1;
        }

        // 3桁 (例: "835" -> "0835") への補正
        if (clean.length() == 3) {
            clean = "0" + clean;
        }
        // 4桁 (例: "0835" -> "083500") への補正
        if (clean.length() == 4) {
            clean += "00";
        }
        // 5桁 (例: "83500" -> "083500") への補正
        if (clean.length() == 5) {
            clean = "0" + clean;
        }

        if (clean.length() != 6) {
            return -1;
        }
        try {
            int h = Integer.parseInt(clean.substring(0, 2));
            int m = Integer.parseInt(clean.substring(2, 4));
            int s = Integer.parseInt(clean.substring(4, 6));

            if (h < 0 || h >= 24 || m < 0 || m >= 60 || s < 0 || s >= 60) {
                return -1;
            }

            return h * 3600 + m * 60 + s;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 現在の現実時間（秒）を取得
     */
    public static int getCurrentRealTimeSeconds() {
        LocalTime now = LocalTime.now();
        return now.getHour() * 3600 + now.getMinute() * 60 + now.getSecond();
    }

    /**
     * 単一のダイヤプロジェクト（1つの .oud2 設定群）を表すデータ構造
     */
    public static final class DiagramProject {
        public String id = "";
        public String name = "";
        public final Map<Long, TrainRuntimeConfig> trainConfigs = new HashMap<>();
        public final Map<String, StationRuntimeConfig> stationConfigs = new HashMap<>();
        public long depotId = -1L;
        public long startStationId = -1L;

        public DiagramProject() {
        }

        public DiagramProject(String id, String name) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }

        public TrainRuntimeConfig getOrCreateTrainConfig(long trainId) {
            return trainConfigs.computeIfAbsent(trainId, ignored -> new TrainRuntimeConfig());
        }

        public StationRuntimeConfig getOrCreateStationConfig(String stationKey) {
            return stationConfigs.computeIfAbsent(stationKey, ignored -> new StationRuntimeConfig());
        }
    }

    public static final class StopSchedule {
        public String arrivalTime = "";
        public String departureTime = "";
        public String stationName = "";

        public StopSchedule() {
        }

        public StopSchedule(String arrivalTime, String departureTime, String stationName) {
            this.arrivalTime = arrivalTime == null ? "" : arrivalTime;
            this.departureTime = departureTime == null ? "" : departureTime;
            this.stationName = stationName == null ? "" : stationName;
        }
    }

    public static final class TrainRuntimeConfig {
        public String depotName = "";
        public String depotLineName = "";
        public long depotId = -1L;
        public double speedMultiplier = 1.0D;
        public int departureOffsetTicks = 0;
        public int minimumDwellTicks = 20; // 到着遅延時の最小停車Tick数
        public int maxRailSpeed = 0;
        public String firstArrivalTime = "";
        public String firstDepartureTime = "";
        public long startStationId = -1L;

        public final List<StopSchedule> stopSchedules = new ArrayList<>();

        public StopSchedule getStopSchedule(int index) {
            if (index >= 0 && index < stopSchedules.size()) {
                return stopSchedules.get(index);
            }
            return null;
        }
    }

    public static final class StationRuntimeConfig {
        public long mtrId = -1L;
        public int mappingType = 0; // 0=None, 1=Station, 2=Depot

        public long mtrStationId = -1L;
        public String mtrStationName = "";
        public boolean enabled = true;
    }
}