package com.oudiabridge.client.screen;

import com.oudiabridge.parser.OudiaParser;
import com.oudiabridge.sync.OudiaWorldSyncState;
import mtr.client.ClientData;
import mtr.data.Depot;
import mtr.data.Station;
import mtr.screen.DashboardScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtIo;
import net.minecraft.text.Text;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

public class DiaConfigScreen extends Screen {
    private final DashboardScreen parent;
    private final OudiaParser.ParseResult data;

    private static final int MAX_CHUNK_SIZE = 24000;

    // タブ状態 (0: 管理ダッシュボード, 1: 駅マッピング, 2: 列車設定)
    private int activeTab = 0;

    // GUI描画位置
    private int x, y, width, height;

    // サーバー上のプロジェクト情報キャッシュ
    private static String serverActiveProjectId = "";
    private static final List<ProjectSummary> serverProjects = new ArrayList<>();

    // 選択中の起点駅
    private NamedOption selectedStartStation = new NamedOption(-1L, "未選択", 0);

    // ドロップダウンリスト管理用
    private final List<CustomDropdownWidget> allDropdowns = new ArrayList<>();

    // コントロール保持 (状態リセット防止用キャッシュ)
    private final Map<Integer, TrainConfigControls> trainControlsMap = new HashMap<>();
    private final List<StationDropdown> stationDropdowns = new ArrayList<>();
    private boolean isStationDropdownsInitialized = false;

    private CustomDropdownWidget summaryStationDropdown;

    // スクロールオフセット
    private int dashboardScrollOffset = 0;
    private int stationScrollOffset = 0;
    private int trainScrollOffset = 0;

    public DiaConfigScreen(DashboardScreen parent, OudiaParser.ParseResult data) {
        super(Text.literal("OuDia 連携ダッシュボード"));
        this.parent = parent;
        this.data = data;
    }

    @Override
    protected void init() {
        super.init();
        this.width = 410;
        this.height = 245;
        this.x = (this.client != null ? this.client.getWindow().getScaledWidth() : 0) / 2 - this.width / 2;
        this.y = (this.client != null ? this.client.getWindow().getScaledHeight() : 0) / 2 - this.height / 2;

        registerServerResponseReceiver();
        requestServerState();

        if (!isStationDropdownsInitialized && data != null) {
            setupStationDropdownsData();
            isStationDropdownsInitialized = true;
        }

        initUI();
    }

    private void registerServerResponseReceiver() {
        ClientPlayNetworking.registerReceiver(com.oudiabridge.Oudiabridge.RESPONSE_DIA_LIST_PACKET_ID, (client, handler, buf, responseSender) -> {
            int length = buf.readInt();
            byte[] dataBytes = new byte[length];
            buf.readBytes(dataBytes);

            try {
                NbtCompound nbt = NbtIo.readCompressed(new ByteArrayInputStream(dataBytes));
                if (nbt != null) {
                    serverActiveProjectId = nbt.getString("activeProjectId");
                    serverProjects.clear();

                    if (nbt.contains("projects")) {
                        NbtList pList = nbt.getList("projects", 10);
                        for (int i = 0; i < pList.size(); i++) {
                            NbtCompound pComp = pList.getCompound(i);
                            serverProjects.add(new ProjectSummary(
                                    pComp.getString("id"),
                                    pComp.getString("name"),
                                    pComp.getInt("trainCount"),
                                    pComp.getInt("stationCount")
                            ));
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void requestServerState() {
        net.minecraft.network.PacketByteBuf buf = PacketByteBufs.create();
        ClientPlayNetworking.send(com.oudiabridge.Oudiabridge.REQUEST_DIA_LIST_PACKET_ID, buf);
    }

    private void setupStationDropdownsData() {
        stationDropdowns.clear();
        List<NamedOption> mtrOptions = new ArrayList<>();
        mtrOptions.add(new NamedOption(-1L, "なし (無効)", 0));

        for (Station s : ClientData.STATIONS) {
            mtrOptions.add(new NamedOption(s.id, "[駅] " + s.name, 1));
        }
        for (Depot d : ClientData.DEPOTS) {
            mtrOptions.add(new NamedOption(d.id, "[車庫] " + d.name, 2));
        }

        for (int i = 0; i < data.stations.size(); i++) {
            OudiaParser.StationData station = data.stations.get(i);
            NamedOption initialSelection = mtrOptions.get(0);

            for (NamedOption opt : mtrOptions) {
                String cleanOptName = opt.name.replaceAll("^\\[.*?\\]\\s*", "");
                if (cleanOptName.equalsIgnoreCase(station.name)) {
                    initialSelection = opt;
                    break;
                }
            }

            CustomDropdownWidget dd = new CustomDropdownWidget(
                    0, 0, 180, 20,
                    "", mtrOptions, initialSelection, opt -> {}, this
            );

            StationDropdown dropdown = new StationDropdown(station.name, dd);
            stationDropdowns.add(dropdown);
        }
    }

    private void initUI() {
        this.clearChildren();
        this.allDropdowns.clear();

        int tabWidth = 125;
        int tabHeight = 20;

        this.addDrawableChild(new ButtonWidget(this.x + 10, this.y + 10, tabWidth, tabHeight,
                Text.literal((activeTab == 0 ? "■ " : "") + "プロジェクト一覧"), btn -> {
            activeTab = 0;
            initUI();
        }));

        if (data != null) {
            this.addDrawableChild(new ButtonWidget(this.x + 140, this.y + 10, tabWidth, tabHeight,
                    Text.literal((activeTab == 1 ? "■ " : "") + "駅マッピング (" + data.stations.size() + ")"), btn -> {
                activeTab = 1;
                initUI();
            }));

            this.addDrawableChild(new ButtonWidget(this.x + 270, this.y + 10, tabWidth, tabHeight,
                    Text.literal((activeTab == 2 ? "■ " : "") + "列車設定 (" + data.trains.size() + ")"), btn -> {
                activeTab = 2;
                initUI();
            }));
        }

        if (activeTab == 0) {
            initDashboardTab();
        } else if (activeTab == 1 && data != null) {
            initStationsTab();
        } else if (activeTab == 2 && data != null) {
            initTrainsTab();
        }

        this.addDrawableChild(new ButtonWidget(this.x + this.width - 75, this.y + this.height - 25, 65, 20, Text.literal("閉じる"), btn -> {
            if (this.client != null) {
                this.client.setScreen(parent);
            }
        }));
    }

    private void initDashboardTab() {
        // .oud2 インポート画面遷移ボタン
        this.addDrawableChild(new ButtonWidget(
                this.x + 20, this.y + 35, 170, 20,
                Text.literal("📂 新規 .oud2 をインポート"),
                btn -> {
                    if (this.client != null) {
                        this.client.setScreen(new OudiaImportScreen(parent));
                    }
                }
        ));

        if (data != null) {
            // 反映ボタン
            this.addDrawableChild(new ButtonWidget(
                    this.x + 200, this.y + 35, 190, 20,
                    Text.literal("★ 編集中のダイヤをサーバーへ保存"),
                    btn -> registerToMtr()
            ));
        }

        int startY = this.y + 80;
        int rowHeight = 22;
        int maxVisible = 4;

        int totalRows = serverProjects.size();
        for (int i = 0; i < maxVisible && (i + dashboardScrollOffset) < totalRows; i++) {
            int idx = i + dashboardScrollOffset;
            ProjectSummary proj = serverProjects.get(idx);
            int rowY = startY + (i * rowHeight);

            boolean isActive = proj.id.equals(serverActiveProjectId);

            // 作業プロジェクト切り替えボタン
            this.addDrawableChild(new ButtonWidget(
                    this.x + 230, rowY, 110, 18,
                    Text.literal(isActive ? "★ 作業中に設定済" : "作業プロジェクト化"),
                    btn -> selectActiveProject(proj.id)
            ));

            // プロジェクト削除ボタン
            this.addDrawableChild(new ButtonWidget(
                    this.x + 345, rowY, 45, 18,
                    Text.literal("削除"),
                    btn -> deleteProject(proj.id)
            ));
        }

        if (totalRows > maxVisible) {
            this.addDrawableChild(new ButtonWidget(this.x + 392, startY, 15, 18, Text.literal("▲"), btn -> {
                if (dashboardScrollOffset > 0) {
                    dashboardScrollOffset--;
                    initUI();
                }
            }));
            this.addDrawableChild(new ButtonWidget(this.x + 392, startY + (maxVisible - 1) * rowHeight, 15, 18, Text.literal("▼"), btn -> {
                if (dashboardScrollOffset < totalRows - maxVisible) {
                    dashboardScrollOffset++;
                    initUI();
                }
            }));
        }
    }

    private void initStationsTab() {
        int startY = this.y + 45;
        int rowHeight = 24;
        int maxVisible = 6;

        int totalRows = stationDropdowns.size();
        for (int i = 0; i < maxVisible && (i + stationScrollOffset) < totalRows; i++) {
            int idx = i + stationScrollOffset;
            StationDropdown sd = stationDropdowns.get(idx);
            int rowY = startY + (i * rowHeight);

            sd.widget.x = this.x + 170;
            sd.widget.y = rowY;
            sd.widget.setOpen(false);

            this.addDrawableChild(sd.widget);
            this.allDropdowns.add(sd.widget);
        }

        if (totalRows > maxVisible) {
            this.addDrawableChild(new ButtonWidget(this.x + 365, startY, 25, 20, Text.literal("▲"), btn -> {
                if (stationScrollOffset > 0) {
                    stationScrollOffset--;
                    initUI();
                }
            }));
            this.addDrawableChild(new ButtonWidget(this.x + 365, startY + (maxVisible - 1) * rowHeight, 25, 20, Text.literal("▼"), btn -> {
                if (stationScrollOffset < totalRows - maxVisible) {
                    stationScrollOffset++;
                    initUI();
                }
            }));
        }
    }

    private void initTrainsTab() {
        int startY = this.y + 60;
        int rowHeight = 24;
        int maxVisible = 5;

        for (int i = 0; i < data.trains.size(); i++) {
            if (!trainControlsMap.containsKey(i)) {
                TrainConfigControls ctrl = new TrainConfigControls();
                ctrl.speedField = new TextFieldWidget(this.textRenderer, 0, 0, 45, 18, Text.literal(""));
                ctrl.speedField.setText("1.0");

                ctrl.maxSpeedField = new TextFieldWidget(this.textRenderer, 0, 0, 45, 18, Text.literal(""));
                ctrl.maxSpeedField.setText("0");

                ctrl.minDwellField = new TextFieldWidget(this.textRenderer, 0, 0, 45, 18, Text.literal(""));
                ctrl.minDwellField.setText("20");

                trainControlsMap.put(i, ctrl);
            }
        }

        int totalRows = data.trains.size();
        for (int i = 0; i < maxVisible && (i + trainScrollOffset) < totalRows; i++) {
            int idx = i + trainScrollOffset;
            TrainConfigControls ctrl = trainControlsMap.get(idx);
            int rowY = startY + (i * rowHeight);

            ctrl.speedField.x = this.x + 130;
            ctrl.speedField.y = rowY;

            ctrl.maxSpeedField.x = this.x + 210;
            ctrl.maxSpeedField.y = rowY;

            ctrl.minDwellField.x = this.x + 290;
            ctrl.minDwellField.y = rowY;

            this.addDrawableChild(ctrl.speedField);
            this.addDrawableChild(ctrl.maxSpeedField);
            this.addDrawableChild(ctrl.minDwellField);
        }

        if (totalRows > maxVisible) {
            this.addDrawableChild(new ButtonWidget(this.x + 360, startY, 25, 20, Text.literal("▲"), btn -> {
                if (trainScrollOffset > 0) {
                    trainScrollOffset--;
                    initUI();
                }
            }));
            this.addDrawableChild(new ButtonWidget(this.x + 360, startY + (maxVisible - 1) * rowHeight, 25, 20, Text.literal("▼"), btn -> {
                if (trainScrollOffset < totalRows - maxVisible) {
                    trainScrollOffset++;
                    initUI();
                }
            }));
        }
    }

    public void closeOtherDropdowns(CustomDropdownWidget current) {
        for (CustomDropdownWidget dropdown : allDropdowns) {
            if (dropdown != current) {
                dropdown.setOpen(false);
            }
        }
    }

    private void selectActiveProject(String projId) {
        net.minecraft.network.PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(projId);
        ClientPlayNetworking.send(com.oudiabridge.Oudiabridge.SELECT_PROJECT_PACKET_ID, buf);
    }

    private void deleteProject(String projId) {
        net.minecraft.network.PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(projId);
        ClientPlayNetworking.send(com.oudiabridge.Oudiabridge.DELETE_PROJECT_PACKET_ID, buf);
    }

    private void registerToMtr() {
        if (data == null) return;

        NbtCompound payload = new NbtCompound();
        String projName = data.routeName != null && !data.routeName.isEmpty() ? data.routeName : "OuDia_Project";
        payload.putString("diaName", projName);
        payload.putString("projId", projName);
        payload.putLong("startStationId", selectedStartStation.id);

        NbtCompound stationTag = new NbtCompound();
        for (StationDropdown sd : stationDropdowns) {
            NbtCompound sCompound = new NbtCompound();
            sCompound.putLong("mtrId", sd.widget.getSelected().id);
            sCompound.putInt("mappingType", sd.widget.getSelected().type);
            sCompound.putString("mtrStationName", sd.widget.getSelected().name);
            sCompound.putLong("mtrStationId", sd.widget.getSelected().id);
            stationTag.put(sd.ouDiaStationName, sCompound);
        }
        payload.put("stations", stationTag);

        NbtCompound trainTag = new NbtCompound();
        for (int i = 0; i < data.trains.size(); i++) {
            OudiaParser.TrainData train = data.trains.get(i);
            TrainConfigControls ctrl = trainControlsMap.get(i);

            NbtCompound tCompound = new NbtCompound();
            tCompound.putLong("startStationId", selectedStartStation.id);

            double speedMul = 1.0D;
            int maxSpeed = 0;
            int minDwell = 20;

            if (ctrl != null) {
                try { speedMul = Double.parseDouble(ctrl.speedField.getText()); } catch (NumberFormatException ignored) {}
                try { maxSpeed = Integer.parseInt(ctrl.maxSpeedField.getText()); } catch (NumberFormatException ignored) {}
                try { minDwell = Integer.parseInt(ctrl.minDwellField.getText()); } catch (NumberFormatException ignored) {}
            }

            tCompound.putDouble("speedMultiplier", speedMul);
            tCompound.putInt("maxRailSpeed", maxSpeed);
            tCompound.putInt("minimumDwellTicks", minDwell);

            String[] firstTimes = extractFirstTimes(train);
            tCompound.putString("firstArrivalTime", firstTimes[0]);
            tCompound.putString("firstDepartureTime", firstTimes[1]);

            NbtList stopsList = new NbtList();
            for (OudiaParser.StopTime stop : train.stops) {
                NbtCompound stopComp = new NbtCompound();
                stopComp.putString("arr", stop.arrivalTime != null ? stop.arrivalTime : "");
                stopComp.putString("dep", stop.departureTime != null ? stop.departureTime : "");

                String stName = "";
                if (stop.stationIndex >= 0 && stop.stationIndex < data.stations.size()) {
                    stName = data.stations.get(stop.stationIndex).name;
                }
                stopComp.putString("name", stName);

                stopsList.add(stopComp);
            }
            tCompound.put("stops", stopsList);

            trainTag.put(String.valueOf(i), tCompound);
        }
        payload.put("trains", trainTag);

        sendSplitNbtPacket(payload);
    }

    private String[] extractFirstTimes(OudiaParser.TrainData train) {
        String arr = "";
        String dep = "";
        for (OudiaParser.StopTime stop : train.stops) {
            if (arr.isEmpty() && !stop.arrivalTime.isEmpty()) arr = stop.arrivalTime;
            if (dep.isEmpty() && !stop.departureTime.isEmpty()) dep = stop.departureTime;
            if (!arr.isEmpty() && !dep.isEmpty()) break;
        }
        return new String[]{arr, dep};
    }

    private void sendSplitNbtPacket(NbtCompound payload) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(payload, baos);
            byte[] fullBytes = baos.toByteArray();

            UUID transferId = UUID.randomUUID();
            int totalLength = fullBytes.length;
            int totalChunks = (int) Math.ceil((double) totalLength / MAX_CHUNK_SIZE);

            for (int i = 0; i < totalChunks; i++) {
                int start = i * MAX_CHUNK_SIZE;
                int length = Math.min(MAX_CHUNK_SIZE, totalLength - start);

                net.minecraft.network.PacketByteBuf buf = PacketByteBufs.create();
                buf.writeUuid(transferId);
                buf.writeInt(i);
                buf.writeInt(totalChunks);
                buf.writeInt(length);
                buf.writeBytes(fullBytes, start, length);

                ClientPlayNetworking.send(com.oudiabridge.Oudiabridge.SYNC_PACKET_ID, buf);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);

        fill(matrices, this.x, this.y, this.x + this.width, this.y + this.height, 0xEE1E1E2E);
        fill(matrices, this.x, this.y, this.x + this.width, this.y + 30, 0xEE11111B);

        fill(matrices, this.x, this.y, this.x + this.width, this.y + 1, 0xFF45475A);
        fill(matrices, this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, 0xFF45475A);

        if (activeTab == 0) {
            this.textRenderer.draw(matrices, "保存済みプロジェクト一覧", this.x + 20, this.y + 65, 0x89B4FA);

            int startY = this.y + 80;
            int rowHeight = 22;
            int maxVisible = 4;

            for (int i = 0; i < maxVisible && (i + dashboardScrollOffset) < serverProjects.size(); i++) {
                int idx = i + dashboardScrollOffset;
                ProjectSummary proj = serverProjects.get(idx);
                int rowY = startY + (i * rowHeight);

                boolean isActive = proj.id.equals(serverActiveProjectId);
                int textColor = isActive ? 0xA6E3A1 : 0xCDD6F4;
                String displayTitle = (isActive ? "★ " : "") + proj.name + " (" + proj.trainCount + "列車)";

                this.textRenderer.draw(matrices, displayTitle, this.x + 20, rowY + 4, textColor);
            }
        } else if (activeTab == 1 && data != null) {
            int startY = this.y + 45;
            int rowHeight = 24;
            int maxVisible = 6;
            for (int i = 0; i < maxVisible && (i + stationScrollOffset) < data.stations.size(); i++) {
                int idx = i + stationScrollOffset;
                String name = data.stations.get(idx).name;
                this.textRenderer.draw(matrices, name, this.x + 25, startY + (i * rowHeight) + 5, 0xCDD6F4);
            }
        } else if (activeTab == 2 && data != null) {
            this.textRenderer.draw(matrices, "列車", this.x + 25, this.y + 42, 0x89B4FA);
            this.textRenderer.draw(matrices, "速度倍率", this.x + 130, this.y + 42, 0x89B4FA);
            this.textRenderer.draw(matrices, "最高速度", this.x + 210, this.y + 42, 0x89B4FA);
            this.textRenderer.draw(matrices, "最小停車", this.x + 290, this.y + 42, 0x89B4FA);

            int startY = this.y + 60;
            int rowHeight = 24;
            int maxVisible = 5;
            for (int i = 0; i < maxVisible && (i + trainScrollOffset) < data.trains.size(); i++) {
                int idx = i + trainScrollOffset;
                String name = "列車 #" + (idx + 1);
                this.textRenderer.draw(matrices, name, this.x + 25, startY + (i * rowHeight) + 5, 0xCDD6F4);
            }
        }

        super.render(matrices, mouseX, mouseY, delta);

        for (CustomDropdownWidget dropdown : allDropdowns) {
            dropdown.renderOverlay(matrices, mouseX, mouseY);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static class ProjectSummary {
        final String id;
        final String name;
        final int trainCount;
        final int stationCount;

        ProjectSummary(String id, String name, int trainCount, int stationCount) {
            this.id = id;
            this.name = name;
            this.trainCount = trainCount;
            this.stationCount = stationCount;
        }
    }

    private static class NamedOption {
        final long id;
        final String name;
        final int type;

        NamedOption(long id, String name, int type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }
    }

    private static class StationDropdown {
        final String ouDiaStationName;
        final CustomDropdownWidget widget;
        StationDropdown(String ouDiaStationName, CustomDropdownWidget widget) {
            this.ouDiaStationName = ouDiaStationName;
            this.widget = widget;
        }
    }

    private static class TrainConfigControls {
        TextFieldWidget speedField;
        TextFieldWidget maxSpeedField;
        TextFieldWidget minDwellField;
    }

    private static class CustomDropdownWidget extends ButtonWidget {
        private final String labelPrefix;
        private final List<NamedOption> options;
        private NamedOption selectedOption;
        private final Consumer<NamedOption> onSelect;
        private final DiaConfigScreen parentScreen;
        private boolean isOpen = false;
        private int scrollIndex = 0;
        private final int maxVisibleRows = 5;
        private final int itemHeight = 18;
        private boolean isDraggingScrollbar = false;

        public CustomDropdownWidget(int x, int y, int width, int height, String labelPrefix, List<NamedOption> options, NamedOption initial, Consumer<NamedOption> onSelect, DiaConfigScreen parentScreen) {
            super(x, y, width, height, Text.literal(labelPrefix + initial.name), btn -> {});
            this.labelPrefix = labelPrefix;
            this.options = options;
            this.selectedOption = initial;
            this.onSelect = onSelect;
            this.parentScreen = parentScreen;
        }

        public NamedOption getSelected() {
            return selectedOption;
        }

        public void setOpen(boolean open) {
            this.isOpen = open;
            if (!open) isDraggingScrollbar = false;
        }

        @Override
        public void onPress() {
            boolean nextState = !this.isOpen;
            if (nextState) {
                parentScreen.closeOtherDropdowns(this);
            }
            this.isOpen = nextState;
            this.isDraggingScrollbar = false;
        }

        @Override
        public void renderButton(MatrixStack matrices, int mouseX, int mouseY, float delta) {
            this.setMessage(Text.literal(labelPrefix + selectedOption.name + (isOpen ? " ▲" : " ▼")));
            super.renderButton(matrices, mouseX, mouseY, delta);
        }

        public void renderOverlay(MatrixStack matrices, int mouseX, int mouseY) {
            if (!isOpen || !this.visible) return;

            int drawerY = this.y + this.height;
            int visibleRows = Math.min(options.size(), maxVisibleRows);
            int drawerH = visibleRows * itemHeight;
            int scrollbarWidth = 6;

            fill(matrices, this.x, drawerY, this.x + this.width, drawerY + drawerH, 0xFA181825);
            fill(matrices, this.x - 1, drawerY - 1, this.x + this.width + 1, drawerY, 0xFF45475A);
            fill(matrices, this.x - 1, drawerY + drawerH, this.x + this.width + 1, drawerY + drawerH + 1, 0xFF45475A);

            int maxScroll = Math.max(0, options.size() - maxVisibleRows);
            int endIndex = Math.min(options.size(), scrollIndex + visibleRows);

            for (int i = scrollIndex; i < endIndex; i++) {
                NamedOption option = options.get(i);
                int itemY = drawerY + ((i - scrollIndex) * itemHeight);
                boolean hovered = mouseX >= this.x && mouseX <= this.x + this.width - (maxScroll > 0 ? scrollbarWidth : 0) &&
                        mouseY >= itemY && mouseY < itemY + itemHeight;
                boolean selected = (option.id == selectedOption.id);

                if (selected) {
                    fill(matrices, this.x + 1, itemY + 1, this.x + this.width - 1, itemY + itemHeight - 1, 0xFF313244);
                } else if (hovered) {
                    fill(matrices, this.x + 1, itemY + 1, this.x + this.width - 1, itemY + itemHeight - 1, 0xFF45475A);
                }

                MinecraftClient.getInstance().textRenderer.draw(
                        matrices,
                        option.name,
                        this.x + 5,
                        itemY + 5,
                        selected ? 0x89B4FA : 0xCDD6F4
                );
            }

            if (maxScroll > 0) {
                int trackX = this.x + this.width - scrollbarWidth;
                fill(matrices, trackX, drawerY, trackX + scrollbarWidth, drawerY + drawerH, 0xFF11111B);

                int thumbHeight = Math.max(8, (int) ((float) visibleRows / options.size() * drawerH));
                int thumbY = drawerY + (int) ((float) scrollIndex / maxScroll * (drawerH - thumbHeight));

                fill(matrices, trackX, thumbY, trackX + scrollbarWidth, thumbY + thumbHeight, 0xFF89B4FA);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isOpen && this.visible) {
                int drawerY = this.y + this.height;
                int visibleRows = Math.min(options.size(), maxVisibleRows);
                int drawerH = visibleRows * itemHeight;
                int maxScroll = Math.max(0, options.size() - maxVisibleRows);

                if (mouseX >= this.x && mouseX <= this.x + this.width &&
                        mouseY >= drawerY && mouseY <= drawerY + drawerH) {

                    if (maxScroll > 0 && mouseX >= this.x + this.width - 8) {
                        isDraggingScrollbar = true;
                        updateScrollFromMouseY(mouseY, drawerY, drawerH);
                        return true;
                    }

                    int clickedIdx = scrollIndex + (int) ((mouseY - drawerY) / itemHeight);
                    if (clickedIdx >= 0 && clickedIdx < options.size()) {
                        selectedOption = options.get(clickedIdx);
                        onSelect.accept(selectedOption);
                        isOpen = false;
                        return true;
                    }
                } else if (!(mouseX >= this.x && mouseX <= this.x + this.width && mouseY >= this.y && mouseY <= this.y + this.height)) {
                    isOpen = false;
                    isDraggingScrollbar = false;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (isOpen && isDraggingScrollbar) {
                int drawerY = this.y + this.height;
                int visibleRows = Math.min(options.size(), maxVisibleRows);
                int drawerH = visibleRows * itemHeight;
                updateScrollFromMouseY(mouseY, drawerY, drawerH);
                return true;
            }
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            isDraggingScrollbar = false;
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
            if (isOpen && this.visible) {
                int drawerY = this.y + this.height;
                int visibleRows = Math.min(options.size(), maxVisibleRows);
                int drawerH = visibleRows * itemHeight;

                if (mouseX >= this.x && mouseX <= this.x + this.width &&
                        mouseY >= this.y && mouseY <= drawerY + drawerH) {
                    int maxScroll = Math.max(0, options.size() - maxVisibleRows);
                    if (amount < 0 && scrollIndex < maxScroll) {
                        scrollIndex++;
                        return true;
                    } else if (amount > 0 && scrollIndex > 0) {
                        scrollIndex--;
                        return true;
                    }
                }
            }
            return super.mouseScrolled(mouseX, mouseY, amount);
        }

        private void updateScrollFromMouseY(double mouseY, int drawerY, int drawerH) {
            int maxScroll = Math.max(0, options.size() - maxVisibleRows);
            if (maxScroll <= 0) return;

            double relativeY = Math.max(0, Math.min(drawerH, mouseY - drawerY));
            scrollIndex = (int) Math.round((relativeY / drawerH) * maxScroll);
            scrollIndex = Math.max(0, Math.min(maxScroll, scrollIndex));
        }
    }
}