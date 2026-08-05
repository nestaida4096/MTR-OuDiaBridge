package com.oudiabridge.sync;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * サーバー・クライアント共通のOuDia同期データ保持用シングルトンクラス
 */
public final class OudiaWorldSyncState {
    private static final OudiaWorldSyncState INSTANCE = new OudiaWorldSyncState();

    private final Map<String, TrainRuntimeConfig> trainConfigs = new HashMap<>();
    private final Map<String, StationRuntimeConfig> stationConfigs = new HashMap<>();
    private final TrainRuntimeConfig defaultTrainConfig = new TrainRuntimeConfig();
    private final StationRuntimeConfig defaultStationConfig = new StationRuntimeConfig();

    // MTRのDepotごとの出庫スケジュールとインデックス管理
    private final Map<Long, List<Integer>> depotDepartureSchedules = new HashMap<>();
    private final Map<Long, Integer> depotDepartureIndices = new HashMap<>();

    private String depotName = "";
    private String depotLineName = "";

    private OudiaWorldSyncState() {
    }

    public static OudiaWorldSyncState getInstance() {
        return INSTANCE;
    }

    public Map<String, TrainRuntimeConfig> getTrainConfigs() {
        return trainConfigs;
    }

    public Map<String, StationRuntimeConfig> getStationConfigs() {
        return stationConfigs;
    }

    public String getDepotName() {
        return depotName;
    }

    public void setDepotName(String depotName) {
        this.depotName = depotName == null ? "" : depotName;
    }

    public String getDepotLineName() {
        return depotLineName;
    }

    public void setDepotLineName(String depotLineName) {
        this.depotLineName = depotLineName == null ? "" : depotLineName;
    }

    public TrainRuntimeConfig getOrCreateTrainConfig(String trainId) {
        return trainConfigs.computeIfAbsent(trainId, ignored -> new TrainRuntimeConfig());
    }

    public TrainRuntimeConfig getDefaultTrainConfig() {
        return defaultTrainConfig;
    }

    public StationRuntimeConfig getDefaultStationConfig() {
        return defaultStationConfig;
    }

    public StationRuntimeConfig getOrCreateStationConfig(String stationKey) {
        return stationConfigs.computeIfAbsent(stationKey, ignored -> new StationRuntimeConfig());
    }

    public void clear() {
        trainConfigs.clear();
        stationConfigs.clear();
        defaultTrainConfig.depotName = "";
        defaultTrainConfig.depotLineName = "";
        defaultTrainConfig.depotId = -1L;
        defaultTrainConfig.startStationId = -1L;
        defaultTrainConfig.speedMultiplier = 1.0D;
        defaultTrainConfig.departureOffsetTicks = 0;
        defaultTrainConfig.minimumDwellTicks = 20;
        defaultTrainConfig.maxRailSpeed = 0;
        defaultTrainConfig.firstArrivalTime = "";
        defaultTrainConfig.firstDepartureTime = "";
        defaultTrainConfig.stopSchedules.clear();
        defaultStationConfig.mtrStationName = "";
        defaultStationConfig.mtrStationId = -1L;
        defaultStationConfig.mtrId = -1L;
        defaultStationConfig.mappingType = 0;
        defaultStationConfig.enabled = true;
        depotName = "";
        depotLineName = "";
        depotDepartureSchedules.clear();
        depotDepartureIndices.clear();
    }

    /**
     * ダイヤデータから各MTR Depotの出庫スケジュールを再構築・ソートします。
     * OuDiaで「車庫(Depot)」としてマッピングされた駅の時刻を集計します。
     */
    public void rebuildDepotSchedules() {
        depotDepartureSchedules.clear();
        depotDepartureIndices.clear();

        for (TrainRuntimeConfig train : trainConfigs.values()) {
            if (train.stopSchedules.isEmpty()) continue;

            StopSchedule firstStop = train.stopSchedules.get(0);
            String startStationName = firstStop.stationName;

            StationRuntimeConfig stationConfig = stationConfigs.get(startStationName);
            if (stationConfig != null && stationConfig.mappingType == 2) { // 2 = Depot (車庫)
                long depotId = stationConfig.mtrId;

                int depSec = parseTimeToSeconds(firstStop.departureTime);
                if (depSec <= 0 && !firstStop.arrivalTime.isEmpty()) {
                    depSec = parseTimeToSeconds(firstStop.arrivalTime); // 発時刻がない場合は着時刻を代用
                }

                if (depSec > 0) {
                    // 出庫オフセット(準備・回送時間)を引いた時刻をスケジュールに追加
                    int offsetSec = train.departureOffsetTicks / 20;
                    depotDepartureSchedules.computeIfAbsent(depotId, k -> new ArrayList<>()).add(depSec - offsetSec);
                }
            }
        }

        int currentSec = getCurrentRealTimeSeconds();
        for (Map.Entry<Long, List<Integer>> entry : depotDepartureSchedules.entrySet()) {
            long depotId = entry.getKey();
            List<Integer> times = entry.getValue();
            Collections.sort(times); // 時間順(昇順)にソート

            // 現在時刻に基づいて、次に出庫すべきインデックスを算出（過去の予定はスキップする）
            int nextIdx = times.size();
            for (int i = 0; i < times.size(); i++) {
                // 2分(120秒)前の過去までは許容して出庫対象とする
                if (times.get(i) >= currentSec - 120) {
                    nextIdx = i;
                    break;
                }
            }
            depotDepartureIndices.put(depotId, nextIdx);
        }
    }

    public List<Integer> getDepotSchedule(long depotId) {
        return depotDepartureSchedules.get(depotId);
    }

    public int getDepotNextIndex(long depotId) {
        return depotDepartureIndices.getOrDefault(depotId, 0);
    }

    public void advanceDepotNextIndex(long depotId) {
        int current = getDepotNextIndex(depotId);
        depotDepartureIndices.put(depotId, current + 1);
    }

    /**
     * 時刻文字列 ("114550", "1145", "11:45:50" 等) を現実時間の「日の始まりからの秒数」に変換する
     */
    public static int parseTimeToSeconds(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return -1;
        }
        String clean = timeStr.replaceAll("[^0-9]", "");
        if (clean.length() == 4) { // HHmm
            clean += "00";
        }
        if (clean.length() != 6) {
            return -1;
        }
        try {
            int h = Integer.parseInt(clean.substring(0, 2));
            int m = Integer.parseInt(clean.substring(2, 4));
            int s = Integer.parseInt(clean.substring(4, 6));
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
     * 各駅の着発時刻を保持する構造体
     */
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

        /**
         * 各停車駅の時刻情報リスト（インデックス順）
         */
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

        // 以下は互換性維持用の変数
        public long mtrStationId = -1L;
        public String mtrStationName = "";
        public boolean enabled = true;
    }
}