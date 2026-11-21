package nest.oudiabridge.sync;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class OudiaWorldSyncState {
    private static final OudiaWorldSyncState INSTANCE = new OudiaWorldSyncState();

    // 複数プロジェクト管理: プロジェクトID/名前 -> DiagramProject
    private final Map<String, DiagramProject> projects = new HashMap<>();
    private String activeProjectId = "";

    // 列車ごとの運用動態データ（ワールドデータの保存・復元およびネットワーク同期用）
    public static final Map<Long, TrainOperatingState> trainOperatingStates = new ConcurrentHashMap<>();

    // クライアント側でのサーバー同期時刻管理用フィールド
    private static volatile int clientSyncedServerSec = -1;
    private static volatile long clientSyncedLocalTimestampMs = 0L;

    private OudiaWorldSyncState() {
    }

    public static OudiaWorldSyncState getInstance() {
        return INSTANCE;
    }

    public static TrainOperatingState getOrCreateOperatingState(long trainId) {
        return trainOperatingStates.computeIfAbsent(trainId, TrainOperatingState::new);
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
        trainOperatingStates.clear();
        activeProjectId = "";
    }

    public static void updateClientSyncedTime(int serverSec) {
        clientSyncedServerSec = serverSec;
        clientSyncedLocalTimestampMs = System.currentTimeMillis();
    }

    public static int getClientSyncedCurrentTimeSeconds() {
        if (clientSyncedServerSec < 0) {
            return getCurrentRealTimeSeconds();
        }
        long elapsedMs = System.currentTimeMillis() - clientSyncedLocalTimestampMs;
        int currentSec = clientSyncedServerSec + (int) (elapsedMs / 1000L);
        return ((currentSec % 86400) + 86400) % 86400;
    }

    public void readNbt(NbtCompound nbt) {
        if (nbt == null) return;

        this.activeProjectId = nbt.contains("activeProjectId") ? nbt.getString("activeProjectId") : "";
        this.projects.clear();

        if (nbt.contains("projects")) {
            NbtList projList = nbt.getList("projects", 10);
            for (int i = 0; i < projList.size(); i++) {
                NbtCompound projNbt = projList.getCompound(i);
                String id = projNbt.getString("id");
                String name = projNbt.getString("name");

                DiagramProject proj = new DiagramProject(id, name);
                proj.depotId = projNbt.contains("depotId") ? projNbt.getLong("depotId") : -1L;
                proj.startStationId = projNbt.contains("startStationId") ? projNbt.getLong("startStationId") : -1L;

                if (projNbt.contains("stations")) {
                    NbtCompound stationTag = projNbt.getCompound("stations");
                    for (String key : stationTag.getKeys()) {
                        NbtCompound value = stationTag.getCompound(key);
                        StationRuntimeConfig cfg = new StationRuntimeConfig();
                        cfg.mtrId = value.contains("mtrId") ? value.getLong("mtrId") : (value.contains("mtrStationId") ? value.getLong("mtrStationId") : -1L);
                        cfg.mappingType = value.contains("mappingType") ? value.getInt("mappingType") : 1;
                        cfg.mtrStationId = cfg.mtrId;
                        cfg.mtrStationName = value.getString("mtrStationName");
                        cfg.enabled = !value.contains("enabled") || value.getBoolean("enabled");
                        proj.stationConfigs.put(key, cfg);
                    }
                }

                if (projNbt.contains("trains")) {
                    NbtCompound trainTag = projNbt.getCompound("trains");
                    for (String key : trainTag.getKeys()) {
                        try {
                            long trainId = Long.parseLong(key);
                            NbtCompound value = trainTag.getCompound(key);
                            TrainRuntimeConfig cfg = new TrainRuntimeConfig();
                            cfg.depotName = value.getString("depotName");
                            cfg.depotLineName = value.getString("depotLineName");
                            cfg.depotId = value.contains("depotId") ? value.getLong("depotId") : -1L;
                            cfg.speedMultiplier = value.getDouble("speedMultiplier");
                            cfg.departureOffsetTicks = value.getInt("departureOffsetTicks");
                            cfg.minimumDwellTicks = value.getInt("minimumDwellTicks");
                            cfg.maxRailSpeed = value.getInt("maxRailSpeed");
                            cfg.firstArrivalTime = value.getString("firstArrivalTime");
                            cfg.firstDepartureTime = value.getString("firstDepartureTime");
                            cfg.startStationId = value.contains("startStationId") ? value.getLong("startStationId") : -1L;

                            if (value.contains("stops")) {
                                NbtList stopsList = value.getList("stops", 10);
                                cfg.stopSchedules.clear();
                                for (int j = 0; j < stopsList.size(); j++) {
                                    NbtCompound stopComp = stopsList.getCompound(j);
                                    cfg.stopSchedules.add(new StopSchedule(
                                            stopComp.getString("arr"),
                                            stopComp.getString("dep"),
                                            stopComp.contains("name") ? stopComp.getString("name") : ""
                                    ));
                                }
                            }

                            proj.trainConfigs.put(trainId, cfg);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }

                this.projects.put(id, proj);
            }


        }

        trainOperatingStates.clear();
        if (nbt.contains("operatingStates")) {
            NbtList nbtList = nbt.getList("operatingStates", 10);
            for (int j = 0; j < nbtList.size(); j++) {
                NbtCompound value = nbtList.getCompound(j);
                long trainId = value.getLong("trainId");
                TrainOperatingState state = getOrCreateOperatingState(trainId);
                if (value.contains("matchedTrainConfigId")) {
                    state.matchedTrainConfigId = value.getLong("matchedTrainConfigId");
                } else {
                    state.matchedTrainConfigId = null;
                }
                state.lastIsOnRoute = value.getBoolean("lastIsOnRoute");
                state.turnaroundStopIndexOffset = value.getInt("turnaroundStopIndexOffset");
                state.lastLoggedStopIndex = value.getInt("lastLoggedStopIndex");
                state.lockedStationStopIndex = value.getInt("lockedStationStopIndex");
            }
        }
    }

    public static int parseTimeToSeconds(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return -1;
        }
        String clean = timeStr.replaceAll("[^0-9]", "");

        if (clean.isEmpty()) {
            return -1;
        }

        if (clean.length() == 3) {
            clean = "0" + clean;
        }
        if (clean.length() == 4) {
            clean += "00";
        }
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

            // OuDia は 24:00 以降の時刻（翌日扱い）を使用することがある。
            // 2日未満の時刻を許可し、日跨ぎの差分計算側で現在時刻と比較する。
            if (h < 0 || h >= 48 || m < 0 || m >= 60 || s < 0 || s >= 60) {
                return -1;
            }

            return h * 3600 + m * 60 + s;


        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static int getCurrentRealTimeSeconds() {
        LocalTime now = LocalTime.now();
        return now.getHour() * 3600 + now.getMinute() * 60 + now.getSecond();
    }

    public static final class TrainOperatingState {
        public long trainId;
        public Long matchedTrainConfigId = null;
        public boolean lastIsOnRoute = false;
        public int turnaroundStopIndexOffset = 0;
        public int lastLoggedStopIndex = -1;
        public int lockedStationStopIndex = -1;

        public TrainOperatingState() {
        }

        public TrainOperatingState(long trainId) {
            this.trainId = trainId;
        }
    }

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