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
    private OudiaParser.ParseResult data;

    private static final int MAX_CHUNK_SIZE = 24000;

    // タブ状態 (0: 管理ダッシュボード/プロジェクト一覧, 1: 駅マッピング, 2: 列車設定)
    private int activeTab = 0;

    // GUI描画位置・サイズ
    private int x, y, width, height;

    // カラーパレット定数
    private static final int COLOR_BG_OUTER = 0xFA0B101D;
    private static final int COLOR_BG_INNER = 0xEE111726;
    private static final int COLOR_CARD_BG = 0xFF182032;
    private static final int COLOR_BORDER = 0xFF2B3854;
    private static final int COLOR_ACCENT_BLUE = 0xFF4F46E5;
    private static final int COLOR_ACTIVE_TAB = 0xFF3B82F6;
    private static final int COLOR_INACTIVE_TAB = 0xFF1E293B;
    private static final int COLOR_TEXT_MAIN = 0xFFE2E8F0;
    private static final int COLOR_TEXT_MUTED = 0xFF94A3B8;
    private static final int COLOR_TEXT_DIM = 0xFF64748B;

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
        this.width = 460;
        this.height = 265;
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
                            ProjectSummary summary = new ProjectSummary(
                                    pComp.getString("id"),
                                    pComp.getString("name"),
                                    pComp.getInt("trainCount"),
                                    pComp.getInt("stationCount"),
                                    pComp
                            );
                            serverProjects.add(summary);

                            // アクティブプロジェクトがあり、現在編集データが未セットの場合はロードする
                            /*
                            if (summary.id.equals(serverActiveProjectId) && data == null) {
                                data = summary.toParseResult();
                                setupStationDropdownsData();
                                isStationDropdownsInitialized = true;
                            }
                             */
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
        if (data == null) return;

        List<NamedOption> mtrOptions = new ArrayList<>();
        mtrOptions.add(new NamedOption(-1L, "[駅] なし (無効)", 0));

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

        // ----------------- トップナビゲーション（タブ） -----------------
        int tabY = this.y + 12;
        int tabH = 20;

        // タブ 0: プロジェクト一覧
        this.addDrawableChild(new ModernButton(this.x + 190, tabY, 80, tabH, Text.literal("プロジェクト一覧"), btn -> {
            activeTab = 0;
            initUI();
        }, activeTab == 0));

        if (data != null) {
            // タブ 1: 駅マッピング
            this.addDrawableChild(new ModernButton(this.x + 275, tabY, 85, tabH, Text.literal("駅マッピング (" + data.stations.size() + ")"), btn -> {
                activeTab = 1;
                initUI();
            }, activeTab == 1));

            // タブ 2: 列車設定
            this.addDrawableChild(new ModernButton(this.x + 365, tabY, 80, tabH, Text.literal("列車設定 (" + data.trains.size() + ")"), btn -> {
                activeTab = 2;
                initUI();
            }, activeTab == 2));
        }

        // タブ内容初期化
        if (activeTab == 0) {
            initDashboardTab();
        } else if (activeTab == 1 && data != null) {
            initStationsTab();
        } else if (activeTab == 2 && data != null) {
            initTrainsTab();
        }

        // 閉じるボタン (右下)
        this.addDrawableChild(new ModernButton(this.x + this.width - 65, this.y + this.height - 24, 55, 18, Text.literal("閉じる"), btn -> {
            if (this.client != null) {
                this.client.setScreen(parent);
            }
        }, false));
    }

    private void initDashboardTab() {
        // カード型ボタン 1: 新規 .oud2 インポート
        this.addDrawableChild(new CardButtonWidget(
                this.x + 15, this.y + 45, 210, 52,
                "新規 .oud2 をインポート",
                ".oud2 ファイルを選択して、\n新しいプロジェクトとしてインポートします。",
                "[IMPORT]",
                btn -> {
                    if (this.client != null) {
                        this.client.setScreen(new OudiaImportScreen(parent));
                    }
                }
        ));

        // カード型ボタン 2: ダイヤ保存
        if (data != null) {
            this.addDrawableChild(new CardButtonWidget(
                    this.x + 235, this.y + 45, 210, 52,
                    "編集中のダイヤをサーバーへ保存",
                    "現在編集中のダイヤデータを\nサーバーに保存します。",
                    "[SAVE]",
                    btn -> registerToMtr()
            ));
        }

        // リスト部
        int startY = this.y + 125;
        int rowHeight = 22;
        int maxVisible = 4;

        int totalRows = serverProjects.size();
        for (int i = 0; i < maxVisible && (i + dashboardScrollOffset) < totalRows; i++) {
            int idx = i + dashboardScrollOffset;
            ProjectSummary proj = serverProjects.get(idx);
            int rowY = startY + (i * rowHeight);

            boolean isActive = proj.id.equals(serverActiveProjectId);

            // 作業プロジェクト切り替えボタン
            /*
            this.addDrawableChild(new ModernButton(
                    this.x + 290, rowY, 105, 18,
                    Text.literal(isActive ? "作業中に設定済" : "作業プロジェクト化"),
                    btn -> selectActiveProject(proj),
                    isActive
            ));
             */

            // プロジェクト削除ボタン
            this.addDrawableChild(new ModernButton(
                    this.x + 400, rowY, 40, 18,
                    Text.literal("削除"),
                    btn -> deleteProject(proj.id),
                    false
            ));
        }

        // スクロールボタン
        if (totalRows > maxVisible) {
            this.addDrawableChild(new ModernButton(this.x + 425, startY - 20, 20, 16, Text.literal("▲"), btn -> {
                if (dashboardScrollOffset > 0) {
                    dashboardScrollOffset--;
                    initUI();
                }
            }, false));
            this.addDrawableChild(new ModernButton(this.x + 425, startY + (maxVisible * rowHeight) + 2, 20, 16, Text.literal("▼"), btn -> {
                if (dashboardScrollOffset < totalRows - maxVisible) {
                    dashboardScrollOffset++;
                    initUI();
                }
            }, false));
        }
    }

    private void initStationsTab() {
        int startY = this.y + 48;
        int rowHeight = 26;
        int maxVisible = 6;

        int totalRows = stationDropdowns.size();
        for (int i = 0; i < maxVisible && (i + stationScrollOffset) < totalRows; i++) {
            int idx = i + stationScrollOffset;
            StationDropdown sd = stationDropdowns.get(idx);
            int rowY = startY + (i * rowHeight);

            sd.widget.x = this.x + 235;
            sd.widget.y = rowY;
            sd.widget.setOpen(false);

            this.addDrawableChild(sd.widget);
            this.allDropdowns.add(sd.widget);
        }

        if (totalRows > maxVisible) {
            this.addDrawableChild(new ModernButton(this.x + 425, startY, 20, 20, Text.literal("▲"), btn -> {
                if (stationScrollOffset > 0) {
                    stationScrollOffset--;
                    initUI();
                }
            }, false));
            this.addDrawableChild(new ModernButton(this.x + 425, startY + (maxVisible - 1) * rowHeight, 20, 20, Text.literal("▼"), btn -> {
                if (stationScrollOffset < totalRows - maxVisible) {
                    stationScrollOffset++;
                    initUI();
                }
            }, false));
        }
    }

    private void initTrainsTab() {
        int startY = this.y + 65;
        int rowHeight = 28;
        int maxVisible = 5;

        for (int i = 0; i < data.trains.size(); i++) {
            if (!trainControlsMap.containsKey(i)) {
                TrainConfigControls ctrl = new TrainConfigControls();
                ctrl.speedField = new TextFieldWidget(this.textRenderer, 0, 0, 50, 18, Text.literal(""));
                ctrl.speedField.setText("1.0");

                // 数値のみ初期設定 (単位表記はプレースホルダーとして描画側に移動)
                ctrl.maxSpeedField = new TextFieldWidget(this.textRenderer, 0, 0, 40, 18, Text.literal(""));
                ctrl.maxSpeedField.setText("0");

                ctrl.minDwellField = new TextFieldWidget(this.textRenderer, 0, 0, 45, 18, Text.literal(""));
                ctrl.minDwellField.setText("600");

                trainControlsMap.put(i, ctrl);
            }
        }

        int totalRows = data.trains.size();
        for (int i = 0; i < maxVisible && (i + trainScrollOffset) < totalRows; i++) {
            int idx = i + trainScrollOffset;
            TrainConfigControls ctrl = trainControlsMap.get(idx);
            int rowY = startY + (i * rowHeight);

            ctrl.speedField.x = this.x + 150;
            ctrl.speedField.y = rowY;

            ctrl.maxSpeedField.x = this.x + 225;
            ctrl.maxSpeedField.y = rowY;

            ctrl.minDwellField.x = this.x + 310;
            ctrl.minDwellField.y = rowY;

            this.addDrawableChild(ctrl.speedField);
            this.addDrawableChild(ctrl.maxSpeedField);
            this.addDrawableChild(ctrl.minDwellField);
        }

        if (totalRows > maxVisible) {
            this.addDrawableChild(new ModernButton(this.x + 425, startY, 20, 20, Text.literal("▲"), btn -> {
                if (trainScrollOffset > 0) {
                    trainScrollOffset--;
                    initUI();
                }
            }, false));
            this.addDrawableChild(new ModernButton(this.x + 425, startY + (maxVisible - 1) * rowHeight, 20, 20, Text.literal("▼"), btn -> {
                if (trainScrollOffset < totalRows - maxVisible) {
                    trainScrollOffset++;
                    initUI();
                }
            }, false));
        }
    }

    public void closeOtherDropdowns(CustomDropdownWidget current) {
        for (CustomDropdownWidget dropdown : allDropdowns) {
            if (dropdown != current) {
                dropdown.setOpen(false);
            }
        }
    }

    private void selectActiveProject(ProjectSummary proj) {
        serverActiveProjectId = proj.id;
        net.minecraft.network.PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(proj.id);
        ClientPlayNetworking.send(com.oudiabridge.Oudiabridge.SELECT_PROJECT_PACKET_ID, buf);

        // 作業プロジェクト化の際、編集可能にするためにデータ構築およびUIの更新を行う
        this.data = proj.toParseResult();
        setupStationDropdownsData();
        isStationDropdownsInitialized = true;
        initUI();
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
            int minDwell = 600;

            if (ctrl != null) {
                try { speedMul = Double.parseDouble(ctrl.speedField.getText()); } catch (NumberFormatException ignored) {}
                try { maxSpeed = Integer.parseInt(ctrl.maxSpeedField.getText().trim()); } catch (NumberFormatException ignored) {}
                try { minDwell = Integer.parseInt(ctrl.minDwellField.getText().trim()); } catch (NumberFormatException ignored) {}
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

        // ----------------- メインウィンドウ背景描画 -----------------
        fill(matrices, this.x, this.y, this.x + this.width, this.y + this.height, COLOR_BG_OUTER);
        drawOutline(matrices, this.x, this.y, this.width, this.height, COLOR_BORDER);

        fill(matrices, this.x + 6, this.y + 6, this.x + this.width - 6, this.y + this.height - 6, COLOR_BG_INNER);

        // ヘッダー部
        this.textRenderer.draw(matrices, "プロジェクト管理", this.x + 18, this.y + 10, COLOR_TEXT_MAIN);
        this.textRenderer.draw(matrices, "PROJECT MANAGEMENT", this.x + 18, this.y + 20, COLOR_TEXT_DIM);

        // ----------------- タブ別コンテンツ描画 -----------------
        if (activeTab == 0) {
            // プロジェクト一覧セクションタイトル
            this.textRenderer.draw(matrices, "保存済みプロジェクト一覧", this.x + 15, this.y + 108, COLOR_TEXT_MAIN);
            fill(matrices, this.x + 15, this.y + 120, this.x + this.width - 15, this.y + 121, COLOR_BORDER);

            int startY = this.y + 128;
            int rowHeight = 22;
            int maxVisible = 4;

            for (int i = 0; i < maxVisible && (i + dashboardScrollOffset) < serverProjects.size(); i++) {
                int idx = i + dashboardScrollOffset;
                ProjectSummary proj = serverProjects.get(idx);
                int rowY = startY + (i * rowHeight);

                boolean isActive = proj.id.equals(serverActiveProjectId);
                int textColor = isActive ? 0xFF818CF8 : COLOR_TEXT_MUTED;
                String displayTitle = (isActive ? "[選択中] " : "  ") + proj.name + " (" + proj.trainCount + "列車)";

                this.textRenderer.draw(matrices, displayTitle, this.x + 20, rowY + 4, textColor);
            }
        } else if (activeTab == 1 && data != null) {
            this.textRenderer.draw(matrices, "駅マッピング一覧", this.x + 15, this.y + 35, COLOR_TEXT_MAIN);

            int startY = this.y + 50;
            int rowHeight = 26;
            int maxVisible = 6;
            for (int i = 0; i < maxVisible && (i + stationScrollOffset) < data.stations.size(); i++) {
                int idx = i + stationScrollOffset;
                String name = data.stations.get(idx).name;

                this.textRenderer.draw(matrices, name, this.x + 25, startY + (i * rowHeight) + 2, COLOR_TEXT_MAIN);
                this.textRenderer.draw(matrices, "Station_" + (idx + 1), this.x + 25, startY + (i * rowHeight) + 12, COLOR_TEXT_DIM);
            }
        } else if (activeTab == 2 && data != null) {
            int headerY = this.y + 42;
            this.textRenderer.draw(matrices, "列車", this.x + 25, headerY, COLOR_TEXT_DIM);
            this.textRenderer.draw(matrices, "速度倍率", this.x + 150, headerY, COLOR_TEXT_DIM);
            this.textRenderer.draw(matrices, "最高速度", this.x + 225, headerY, COLOR_TEXT_DIM);
            this.textRenderer.draw(matrices, "最小停車", this.x + 310, headerY, COLOR_TEXT_DIM);

            int startY = this.y + 65;
            int rowHeight = 28;
            int maxVisible = 5;
            for (int i = 0; i < maxVisible && (i + trainScrollOffset) < data.trains.size(); i++) {
                int idx = i + trainScrollOffset;
                TrainConfigControls ctrl = trainControlsMap.get(idx);
                int rowY = startY + (i * rowHeight);
                String name = "列車 #" + (idx + 1);
                this.textRenderer.draw(matrices, name, this.x + 25, rowY + 5, COLOR_TEXT_MAIN);

                // 単位ラベルをテキストボックスの横に描画
                if (ctrl != null) {
                    this.textRenderer.draw(matrices, "km/h", ctrl.maxSpeedField.x + ctrl.maxSpeedField.getWidth() + 3, rowY + 5, COLOR_TEXT_DIM);
                    this.textRenderer.draw(matrices, "ticks", ctrl.minDwellField.x + ctrl.minDwellField.getWidth() + 3, rowY + 5, COLOR_TEXT_DIM);
                }
            }
        }

        super.render(matrices, mouseX, mouseY, delta);

        // ドロップダウンのオーバーレイ描画
        for (CustomDropdownWidget dropdown : allDropdowns) {
            dropdown.renderOverlay(matrices, mouseX, mouseY);
        }
    }

    private void drawOutline(MatrixStack matrices, int x, int y, int width, int height, int color) {
        fill(matrices, x, y, x + width, y + 1, color);
        fill(matrices, x, y + height - 1, x + width, y + height, color);
        fill(matrices, x, y, x + 1, y + height, color);
        fill(matrices, x + width - 1, y, x + width, y + height, color);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ----------------- カスタムGUIコンポーネントクラス群 -----------------

    private static class ModernButton extends ButtonWidget {
        private final boolean isActive;

        public ModernButton(int x, int y, int width, int height, Text message, PressAction onPress, boolean isActive) {
            super(x, y, width, height, message, onPress);
            this.isActive = isActive;
        }

        @Override
        public void renderButton(MatrixStack matrices, int mouseX, int mouseY, float delta) {
            boolean hovered = this.isHovered();
            int bg = isActive ? COLOR_ACTIVE_TAB : (hovered ? 0xFF334155 : COLOR_INACTIVE_TAB);
            int textColor = isActive ? 0xFFFFFFFF : (hovered ? 0xFFF1F5F9 : COLOR_TEXT_MUTED);

            fill(matrices, this.x, this.y, this.x + this.width, this.y + this.height, bg);

            int borderColor = isActive ? COLOR_ACCENT_BLUE : COLOR_BORDER;
            fill(matrices, this.x, this.y, this.x + this.width, this.y + 1, borderColor);
            fill(matrices, this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, borderColor);
            fill(matrices, this.x, this.y, this.x + 1, this.y + this.height, borderColor);
            fill(matrices, this.x + this.width - 1, this.y, x + this.width, this.y + this.height, borderColor);

            int textWidth = MinecraftClient.getInstance().textRenderer.getWidth(this.getMessage());
            int textX = this.x + (this.width - textWidth) / 2;
            int textY = this.y + (this.height - 8) / 2;

            MinecraftClient.getInstance().textRenderer.draw(matrices, this.getMessage(), textX, textY, textColor);
        }
    }

    private static class CardButtonWidget extends ButtonWidget {
        private final String title;
        private final String description;
        private final String tagText;

        public CardButtonWidget(int x, int y, int width, int height, String title, String description, String tagText, PressAction onPress) {
            super(x, y, width, height, Text.literal(""), onPress);
            this.title = title;
            this.description = description;
            this.tagText = tagText;
        }

        @Override
        public void renderButton(MatrixStack matrices, int mouseX, int mouseY, float delta) {
            boolean hovered = this.isHovered();
            int bg = hovered ? 0xFF232D42 : COLOR_CARD_BG;
            int borderColor = hovered ? COLOR_ACTIVE_TAB : COLOR_BORDER;

            fill(matrices, this.x, this.y, this.x + this.width, this.y + this.height, bg);

            fill(matrices, this.x, this.y, this.x + this.width, this.y + 1, borderColor);
            fill(matrices, this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, borderColor);
            fill(matrices, this.x, this.y, this.x + 1, this.y + this.height, borderColor);
            fill(matrices, this.x + this.width - 1, this.y, this.x + this.width, this.y + this.height, borderColor);

            int textLeft = this.x + 12;

            if (tagText != null && !tagText.isEmpty()) {
                MinecraftClient.getInstance().textRenderer.draw(matrices, tagText, this.x + 12, this.y + 8, COLOR_ACTIVE_TAB);
                textLeft = this.x + 12 + MinecraftClient.getInstance().textRenderer.getWidth(tagText) + 6;
            }

            MinecraftClient.getInstance().textRenderer.draw(matrices, title, textLeft, this.y + 8, COLOR_TEXT_MAIN);

            String[] descLines = description.split("\n");
            int descY = this.y + 22;
            for (String line : descLines) {
                MinecraftClient.getInstance().textRenderer.draw(matrices, line, this.x + 12, descY, COLOR_TEXT_DIM);
                descY += 10;
            }
        }
    }

    private static class ProjectSummary {
        final String id;
        final String name;
        final int trainCount;
        final int stationCount;
        final NbtCompound fullNbt;

        ProjectSummary(String id, String name, int trainCount, int stationCount, NbtCompound fullNbt) {
            this.id = id;
            this.name = name;
            this.trainCount = trainCount;
            this.stationCount = stationCount;
            this.fullNbt = fullNbt;
        }

        OudiaParser.ParseResult toParseResult() {
            String routeName = this.name;
            List<OudiaParser.StationData> stations = new ArrayList<>();
            List<OudiaParser.TrainData> trains = new ArrayList<>();

            if (fullNbt != null && fullNbt.contains("stations")) {
                NbtCompound stTag = fullNbt.getCompound("stations");
                for (String key : stTag.getKeys()) {
                    OudiaParser.StationData sd = new OudiaParser.StationData();
                    sd.name = key;
                    stations.add(sd);
                }
            }

            if (stations.size() < stationCount) {
                int needed = stationCount - stations.size();
                for (int i = 0; i < needed; i++) {
                    OudiaParser.StationData sd = new OudiaParser.StationData();
                    sd.name = "駅 " + (stations.size() + 1);
                    stations.add(sd);
                }
            }

            if (fullNbt != null && fullNbt.contains("trains")) {
                NbtCompound trTag = fullNbt.getCompound("trains");
                for (String key : trTag.getKeys()) {
                    OudiaParser.TrainData td = new OudiaParser.TrainData();
                    td.stops = new ArrayList<>();
                    trains.add(td);
                }
            }

            if (trains.size() < trainCount) {
                int needed = trainCount - trains.size();
                for (int i = 0; i < needed; i++) {
                    OudiaParser.TrainData td = new OudiaParser.TrainData();
                    td.stops = new ArrayList<>();
                    trains.add(td);
                }
            }

            return new OudiaParser.ParseResult(routeName, stations, trains);
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
        private List<NamedOption> filteredOptions;
        private NamedOption selectedOption;
        private final Consumer<NamedOption> onSelect;
        private final DiaConfigScreen parentScreen;

        private boolean isOpen = false;
        private int scrollIndex = 0;
        private final int maxVisibleRows = 5;
        private final int itemHeight = 18;

        // 検索・操作用の状態変数
        private String searchQuery = "";
        private boolean isSearching = false;
        private boolean isDraggingScrollbar = false;

        public CustomDropdownWidget(int x, int y, int width, int height, String labelPrefix, List<NamedOption> options, NamedOption initial, Consumer<NamedOption> onSelect, DiaConfigScreen parentScreen) {
            super(x, y, width, height, Text.literal(labelPrefix + initial.name), btn -> {});
            this.labelPrefix = labelPrefix;
            this.options = options;
            this.filteredOptions = new ArrayList<>(options);
            this.selectedOption = initial;
            this.onSelect = onSelect;
            this.parentScreen = parentScreen;
        }

        public NamedOption getSelected() {
            return selectedOption;
        }

        public void setOpen(boolean open) {
            this.isOpen = open;
            if (!open) {
                isDraggingScrollbar = false;
                isSearching = false;
                searchQuery = "";
                updateFilter();
            }
        }

        @Override
        public void onPress() {
            boolean nextState = !this.isOpen;
            if (nextState) {
                parentScreen.closeOtherDropdowns(this);
                isSearching = true; // 開いた直後からそのまま文字入力で検索できるようにする
                searchQuery = "";
                updateFilter();
            }
            this.isOpen = nextState;
            this.isDraggingScrollbar = false;
        }

        private void updateFilter() {
            filteredOptions.clear();
            if (searchQuery.isEmpty()) {
                filteredOptions.addAll(options);
            } else {
                String lowerQuery = searchQuery.toLowerCase();
                for (NamedOption option : options) {
                    if (option.name.toLowerCase().contains(lowerQuery)) {
                        filteredOptions.add(option);
                    }
                }
            }
            scrollIndex = Math.max(0, Math.min(scrollIndex, Math.max(0, filteredOptions.size() - maxVisibleRows)));
        }

        // キー入力受付用（スクリーン側から呼び出す）
        public boolean charTyped(char chr, int modifiers) {
            if (!isOpen) return false;
            if (chr >= 32 && chr != 127) { // 有効な文字入力
                searchQuery += chr;
                updateFilter();
                return true;
            }
            return false;
        }

        // バックスペース等の特殊キー受付用（スクリーン側から呼び出す）
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!isOpen) return false;
            if (keyCode == 259) { // Backspace
                if (!searchQuery.isEmpty()) {
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                    updateFilter();
                }
                return true;
            } else if (keyCode == 256) { // Escapeでドロップダウンを閉じる
                setOpen(false);
                return true;
            } else if (keyCode == 264) { // 下矢印キー
                if (scrollIndex < Math.max(0, filteredOptions.size() - maxVisibleRows)) {
                    scrollIndex++;
                }
                return true;
            } else if (keyCode == 265) { // 上矢印キー
                if (scrollIndex > 0) {
                    scrollIndex--;
                }
                return true;
            }
            return false;
        }

        // マウスホイールスクロール対応（スクリーン側から呼び出す）
        public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
            if (!isOpen) return false;
            int visibleRows = Math.min(filteredOptions.size(), maxVisibleRows);
            int drawerY = this.y + this.height + 2;
            int drawerH = (visibleRows + 1) * itemHeight; // 検索バー分を含む高さ

            if (mouseX >= this.x && mouseX <= this.x + this.width && mouseY >= drawerY && mouseY <= drawerY + drawerH) {
                if (amount > 0 && scrollIndex > 0) {
                    scrollIndex--;
                } else if (amount < 0 && scrollIndex < Math.max(0, filteredOptions.size() - maxVisibleRows)) {
                    scrollIndex++;
                }
                return true;
            }
            return false;
        }

        // マウスドラッグによるスクロールバー操作対応（スクリーン側から呼び出す）
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (!isOpen || !isDraggingScrollbar) return false;

            int visibleRows = Math.min(filteredOptions.size(), maxVisibleRows);
            int drawerY = this.y + this.height + 2 + itemHeight; // 検索バーの下から
            int drawerH = visibleRows * itemHeight;
            int maxScroll = Math.max(0, filteredOptions.size() - maxVisibleRows);

            if (maxScroll > 0 && drawerH > 0) {
                int thumbHeight = Math.max(8, (int) ((float) visibleRows / filteredOptions.size() * drawerH));
                float trackClickRatio = (float) (mouseY - drawerY - (thumbHeight / 2.0f)) / (drawerH - thumbHeight);
                scrollIndex = Math.max(0, Math.min(maxScroll, (int) (trackClickRatio * maxScroll)));
            }
            return true;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!isOpen) {
                return super.mouseClicked(mouseX, mouseY, button);
            }

            int drawerY = this.y + this.height + 2;
            int visibleRows = Math.min(filteredOptions.size(), maxVisibleRows);
            int searchBarH = itemHeight;
            int drawerH = searchBarH + (visibleRows * itemHeight);
            int scrollbarWidth = 6;

            // ドロップダウン領域外をクリックした場合は閉じる
            if (mouseX < this.x || mouseX > this.x + this.width || mouseY < drawerY || mouseY > drawerY + drawerH) {
                setOpen(false);
                return false;
            }

            // 検索バー部分のクリック
            if (mouseY >= drawerY && mouseY < drawerY + searchBarH) {
                isSearching = true;
                return true;
            }

            // スクロールバー領域の判定
            int maxScroll = Math.max(0, filteredOptions.size() - maxVisibleRows);
            if (maxScroll > 0) {
                int trackX = this.x + this.width - scrollbarWidth - 1;
                int listStartY = drawerY + searchBarH;
                int listH = visibleRows * itemHeight;
                if (mouseX >= trackX && mouseX <= trackX + scrollbarWidth && mouseY >= listStartY && mouseY < listStartY + listH) {
                    isDraggingScrollbar = true;
                    return true;
                }
            }

            // リスト項目のクリック判定
            int listStartY = drawerY + searchBarH;
            for (int i = scrollIndex; i < Math.min(filteredOptions.size(), scrollIndex + visibleRows); i++) {
                int itemY = listStartY + ((i - scrollIndex) * itemHeight);
                if (mouseX >= this.x && mouseX <= this.x + this.width - (maxScroll > 0 ? scrollbarWidth : 0) &&
                        mouseY >= itemY && mouseY < itemY + itemHeight) {
                    NamedOption chosen = filteredOptions.get(i);
                    this.selectedOption = chosen;
                    this.onSelect.accept(chosen);
                    this.setMessage(Text.literal(labelPrefix + chosen.name));
                    setOpen(false);
                    return true;
                }
            }

            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            isDraggingScrollbar = false;
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public void renderButton(MatrixStack matrices, int mouseX, int mouseY, float delta) {
            boolean hovered = this.isHovered();
            fill(matrices, this.x, this.y, this.x + this.width, this.y + this.height, hovered ? 0xFF26334D : COLOR_CARD_BG);

            int borderColor = isOpen ? COLOR_ACTIVE_TAB : COLOR_BORDER;
            fill(matrices, this.x, this.y, this.x + this.width, this.y + 1, borderColor);
            fill(matrices, this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, borderColor);
            fill(matrices, this.x, this.y, this.x + 1, this.y + this.height, borderColor);
            fill(matrices, this.x + this.width - 1, this.y, this.x + this.width, this.y + this.height, borderColor);

            String text = labelPrefix + selectedOption.name;
            MinecraftClient.getInstance().textRenderer.draw(matrices, text, this.x + 6, this.y + 6, COLOR_TEXT_MAIN);

            String arrow = isOpen ? "▲" : "▼";
            MinecraftClient.getInstance().textRenderer.draw(matrices, arrow, this.x + this.width - 14, this.y + 6, COLOR_TEXT_DIM);
        }

        public void renderOverlay(MatrixStack matrices, int mouseX, int mouseY) {
            if (!isOpen || !this.visible) return;

            int drawerY = this.y + this.height + 2;
            int visibleRows = Math.min(filteredOptions.size(), maxVisibleRows);
            int searchBarH = itemHeight;
            int drawerH = searchBarH + (visibleRows * itemHeight);
            int scrollbarWidth = 6;

            // 背景描画
            fill(matrices, this.x, drawerY, this.x + this.width, drawerY + drawerH, 0xFF0F172A);

            // 外枠
            fill(matrices, this.x, drawerY, this.x + this.width, drawerY + 1, COLOR_ACTIVE_TAB);
            fill(matrices, this.x, drawerY + drawerH - 1, this.x + this.width, drawerY + drawerH, COLOR_ACTIVE_TAB);
            fill(matrices, this.x, drawerY, this.x + 1, drawerY + drawerH, COLOR_ACTIVE_TAB);
            fill(matrices, this.x + this.width - 1, drawerY, this.x + this.width, drawerY + drawerH, COLOR_ACTIVE_TAB);

            // --- 1. 検索バーの描画 ---
            fill(matrices, this.x + 1, drawerY + 1, this.x + this.width - 1, drawerY + searchBarH, 0xFF1E293B);
            String displaySearchText = searchQuery.isEmpty() ? "§7検索..." : searchQuery;
            MinecraftClient.getInstance().textRenderer.draw(
                    matrices,
                    displaySearchText,
                    this.x + 6,
                    drawerY + 5,
                    searchQuery.isEmpty() ? 0xFF64748B : COLOR_TEXT_MAIN
            );

            // --- 2. リスト項目の描画 ---
            int listStartY = drawerY + searchBarH;
            int maxScroll = Math.max(0, filteredOptions.size() - maxVisibleRows);
            int endIndex = Math.min(filteredOptions.size(), scrollIndex + visibleRows);

            for (int i = scrollIndex; i < endIndex; i++) {
                NamedOption option = filteredOptions.get(i);
                int itemY = listStartY + ((i - scrollIndex) * itemHeight);
                boolean hovered = mouseX >= this.x && mouseX <= this.x + this.width - (maxScroll > 0 ? scrollbarWidth : 0) &&
                        mouseY >= itemY && mouseY < itemY + itemHeight;
                boolean selected = (option.id == selectedOption.id);

                if (selected) {
                    fill(matrices, this.x + 2, itemY + 1, this.x + this.width - 2, itemY + itemHeight - 1, 0xFF1E293B);
                } else if (hovered) {
                    fill(matrices, this.x + 2, itemY + 1, this.x + this.width - 2, itemY + itemHeight - 1, 0xFF334155);
                }

                MinecraftClient.getInstance().textRenderer.draw(
                        matrices,
                        option.name,
                        this.x + 6,
                        itemY + 5,
                        selected ? COLOR_ACTIVE_TAB : COLOR_TEXT_MAIN
                );
            }

            // --- 3. スクロールバーの描画 ---
            if (maxScroll > 0) {
                int trackX = this.x + this.width - scrollbarWidth - 1;
                fill(matrices, trackX, listStartY, trackX + scrollbarWidth, drawerY + drawerH - 1, 0xFF020617);

                int listH = visibleRows * itemHeight;
                int thumbHeight = Math.max(8, (int) ((float) visibleRows / filteredOptions.size() * listH));
                int thumbY = listStartY + (int) ((float) scrollIndex / maxScroll * (listH - thumbHeight));

                fill(matrices, trackX, thumbY, trackX + scrollbarWidth, thumbY + thumbHeight, COLOR_ACTIVE_TAB);
            }
        }
    }
}