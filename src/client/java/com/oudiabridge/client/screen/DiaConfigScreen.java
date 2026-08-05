package com.oudiabridge.client.screen;

import com.oudiabridge.parser.OudiaParser;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

public class DiaConfigScreen extends Screen {
    private final DashboardScreen parent;
    private final OudiaParser.ParseResult data;

    private static final int MAX_CHUNK_SIZE = 32000;
    private int activeTab = 0;
    private int x, y, width, height;

    private NamedOption selectedDepot = new NamedOption(-1L, "未選択", 0);
    private NamedOption selectedStartStation = new NamedOption(-1L, "未選択", 0);

    private final Map<Integer, TrainConfigControls> trainControlsMap = new HashMap<>();
    private final List<StationDropdown> stationDropdowns = new ArrayList<>();

    private int stationScrollOffset = 0;
    private int trainScrollOffset = 0;

    public DiaConfigScreen(DashboardScreen parent, OudiaParser.ParseResult data) {
        super(Text.literal("OuDia 連携設定"));
        this.parent = parent;
        this.data = data;
    }

    @Override
    protected void init() {
        super.init();
        this.width = 440;
        this.height = 260;
        this.x = (this.client != null ? this.client.getWindow().getScaledWidth() : 0) / 2 - this.width / 2;
        this.y = (this.client != null ? this.client.getWindow().getScaledHeight() : 0) / 2 - this.height / 2;

        initUI();
    }

    private void initUI() {
        this.clearChildren();

        this.addDrawableChild(new ButtonWidget(this.x + 10, this.y + 10, 130, 20, Text.literal("概要 & 送信"), btn -> {
            activeTab = 0;
            initUI();
        }));

        this.addDrawableChild(new ButtonWidget(this.x + 145, this.y + 10, 140, 20, Text.literal("駅・車庫マッピング (" + data.stations.size() + ")"), btn -> {
            activeTab = 1;
            initUI();
        }));

        this.addDrawableChild(new ButtonWidget(this.x + 290, this.y + 10, 140, 20, Text.literal("列車個別設定 (" + data.trains.size() + ")"), btn -> {
            activeTab = 2;
            initUI();
        }));

        if (activeTab == 0) {
            initSummaryTab();
        } else if (activeTab == 1) {
            initStationsTab();
        } else if (activeTab == 2) {
            initTrainsTab();
        }

        this.addDrawableChild(new ButtonWidget(this.x + this.width - 75, this.y + this.height - 28, 65, 20, Text.literal("閉じる"), btn -> {
            if (this.client != null) {
                this.client.setScreen(parent);
            }
        }));
    }

    private void initSummaryTab() {
        List<NamedOption> depotOptions = new ArrayList<>();
        depotOptions.add(new NamedOption(-1L, "未選択", 0));
        for (Depot depot : ClientData.DEPOTS) {
            depotOptions.add(new NamedOption(depot.id, depot.name, 2));
        }

        this.addDrawableChild(new CustomDropdownWidget(
                this.x + 130, this.y + 75, 260, 20,
                "対象デポ: ", depotOptions, selectedDepot,
                opt -> selectedDepot = opt
        ));

        List<NamedOption> stationOptions = new ArrayList<>();
        stationOptions.add(new NamedOption(-1L, "未選択", 0));
        for (Station station : ClientData.STATIONS) {
            stationOptions.add(new NamedOption(station.id, station.name, 1));
        }

        this.addDrawableChild(new CustomDropdownWidget(
                this.x + 130, this.y + 120, 260, 20,
                "起点駅: ", stationOptions, selectedStartStation,
                opt -> selectedStartStation = opt
        ));

        this.addDrawableChild(new ButtonWidget(
                this.x + 130, this.y + 175, 260, 22,
                Text.literal("MTRへダイヤを一括登録"),
                btn -> registerToMtr()
        ));
    }

    private void initStationsTab() {
        stationDropdowns.clear();
        List<NamedOption> mtrOptions = new ArrayList<>();
        mtrOptions.add(new NamedOption(-1L, "なし (無効)", 0));

        for (Station s : ClientData.STATIONS) {
            mtrOptions.add(new NamedOption(s.id, "[駅] " + s.name, 1));
        }
        for (Depot d : ClientData.DEPOTS) {
            mtrOptions.add(new NamedOption(d.id, "[車庫] " + d.name, 2));
        }

        int startY = this.y + 48;
        int rowHeight = 28;
        int maxVisible = 6;

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

            StationDropdown dropdown = new StationDropdown(
                    station.name,
                    new CustomDropdownWidget(
                            0, 0, 210, 20,
                            "", mtrOptions, initialSelection, opt -> {}
                    )
            );
            stationDropdowns.add(dropdown);
        }

        int totalRows = stationDropdowns.size();
        for (int i = 0; i < maxVisible && (i + stationScrollOffset) < totalRows; i++) {
            int idx = i + stationScrollOffset;
            StationDropdown sd = stationDropdowns.get(idx);
            int rowY = startY + (i * rowHeight);

            sd.widget.x = this.x + 185;
            sd.widget.y = rowY;
            this.addDrawableChild(sd.widget);
        }

        if (totalRows > maxVisible) {
            this.addDrawableChild(new ButtonWidget(this.x + 405, startY, 20, 20, Text.literal("^"), btn -> {
                if (stationScrollOffset > 0) {
                    stationScrollOffset--;
                    initUI();
                }
            }));
            this.addDrawableChild(new ButtonWidget(this.x + 405, startY + (maxVisible - 1) * rowHeight, 20, 20, Text.literal("v"), btn -> {
                if (stationScrollOffset < totalRows - maxVisible) {
                    stationScrollOffset++;
                    initUI();
                }
            }));
        }
    }

    private void initTrainsTab() {
        int startY = this.y + 48;
        int rowHeight = 30;
        int maxVisible = 5;

        for (int i = 0; i < data.trains.size(); i++) {
            if (!trainControlsMap.containsKey(i)) {
                TrainConfigControls ctrl = new TrainConfigControls();
                ctrl.speedField = new TextFieldWidget(this.textRenderer, 0, 0, 55, 18, Text.literal(""));
                ctrl.speedField.setText("1.0");

                ctrl.maxSpeedField = new TextFieldWidget(this.textRenderer, 0, 0, 55, 18, Text.literal(""));
                ctrl.maxSpeedField.setText("0");

                ctrl.minDwellField = new TextFieldWidget(this.textRenderer, 0, 0, 55, 18, Text.literal(""));
                ctrl.minDwellField.setText("20");

                trainControlsMap.put(i, ctrl);
            }
        }

        int totalRows = data.trains.size();
        for (int i = 0; i < maxVisible && (i + trainScrollOffset) < totalRows; i++) {
            int idx = i + trainScrollOffset;
            TrainConfigControls ctrl = trainControlsMap.get(idx);
            int rowY = startY + (i * rowHeight);

            ctrl.speedField.x = this.x + 160;
            ctrl.speedField.y = rowY;

            ctrl.maxSpeedField.x = this.x + 235;
            ctrl.maxSpeedField.y = rowY;

            ctrl.minDwellField.x = this.x + 310;
            ctrl.minDwellField.y = rowY;

            this.addDrawableChild(ctrl.speedField);
            this.addDrawableChild(ctrl.maxSpeedField);
            this.addDrawableChild(ctrl.minDwellField);
        }

        if (totalRows > maxVisible) {
            this.addDrawableChild(new ButtonWidget(this.x + 385, startY, 20, 20, Text.literal("^"), btn -> {
                if (trainScrollOffset > 0) {
                    trainScrollOffset--;
                    initUI();
                }
            }));
            this.addDrawableChild(new ButtonWidget(this.x + 385, startY + (maxVisible - 1) * rowHeight, 20, 20, Text.literal("v"), btn -> {
                if (trainScrollOffset < totalRows - maxVisible) {
                    trainScrollOffset++;
                    initUI();
                }
            }));
        }
    }

    private void registerToMtr() {
        NbtCompound payload = new NbtCompound();
        payload.putString("depotName", selectedDepot.name);
        payload.putLong("depotId", selectedDepot.id);
        payload.putLong("startStationId", selectedStartStation.id);

        NbtCompound stationTag = new NbtCompound();
        for (int i = 0; i < stationDropdowns.size(); i++) {
            StationDropdown sd = stationDropdowns.get(i);
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
            tCompound.putString("depotName", selectedDepot.name);
            tCompound.putLong("depotId", selectedDepot.id);
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
        fill(matrices, this.x, this.y, this.x + this.width, this.y + this.height, 0xC0101010);

        super.render(matrices, mouseX, mouseY, delta);

        if (activeTab == 0) {
            this.textRenderer.draw(matrices, "OuDia 概要設定", this.x + 20, this.y + 45, 0xFFFFFF);
            this.textRenderer.draw(matrices, "路線名: " + data.routeName, this.x + 20, this.y + 215, 0x00E5FF);
        } else if (activeTab == 1) {
            this.textRenderer.draw(matrices, "OuDia 駅名", this.x + 20, this.y + 32, 0xAAAAAA);
            this.textRenderer.draw(matrices, "MTR 紐付け先", this.x + 185, this.y + 32, 0xAAAAAA);

            int startY = this.y + 48;
            int rowHeight = 28;
            int maxVisible = 6;
            for (int i = 0; i < maxVisible && (i + stationScrollOffset) < data.stations.size(); i++) {
                int idx = i + stationScrollOffset;
                String name = data.stations.get(idx).name;
                this.textRenderer.draw(matrices, name, this.x + 20, startY + (i * rowHeight) + 6, 0xFFFFFF);
            }
        } else if (activeTab == 2) {
            this.textRenderer.draw(matrices, "列車情報", this.x + 20, this.y + 32, 0xAAAAAA);
            this.textRenderer.draw(matrices, "倍率", this.x + 160, this.y + 32, 0xAAAAAA);
            this.textRenderer.draw(matrices, "最高速", this.x + 235, this.y + 32, 0xAAAAAA);
            this.textRenderer.draw(matrices, "停車T", this.x + 310, this.y + 32, 0xAAAAAA);

            int startY = this.y + 48;
            int rowHeight = 30;
            int maxVisible = 5;
            for (int i = 0; i < maxVisible && (i + trainScrollOffset) < data.trains.size(); i++) {
                int idx = i + trainScrollOffset;
                String name = "列車 #" + (idx + 1);
                this.textRenderer.draw(matrices, name, this.x + 20, startY + (i * rowHeight) + 6, 0xFFFFFF);
            }
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
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
        private boolean isOpen = false;
        private int scrollIndex = 0;
        private final int maxVisibleRows = 5;
        private final int itemHeight = 18;

        public CustomDropdownWidget(int x, int y, int width, int height, String labelPrefix, List<NamedOption> options, NamedOption initial, Consumer<NamedOption> onSelect) {
            super(x, y, width, height, Text.literal(labelPrefix + initial.name), btn -> {});
            this.labelPrefix = labelPrefix;
            this.options = options;
            this.selectedOption = initial;
            this.onSelect = onSelect;
        }

        public NamedOption getSelected() {
            return selectedOption;
        }

        @Override
        public void onPress() {
            this.isOpen = !this.isOpen;
        }

        @Override
        public void renderButton(MatrixStack matrices, int mouseX, int mouseY, float delta) {
            this.setMessage(Text.literal(labelPrefix + selectedOption.name));
            super.renderButton(matrices, mouseX, mouseY, delta);

            if (isOpen) {
                int drawerY = this.y + this.height;
                int visibleRows = Math.min(options.size(), maxVisibleRows);
                int drawerH = visibleRows * itemHeight;

                fill(matrices, this.x, drawerY, this.x + this.width, drawerY + drawerH, 0xF018181B);
                fill(matrices, this.x, drawerY, this.x + this.width, drawerY + drawerH, 0x80000000);

                int endIndex = Math.min(options.size(), scrollIndex + visibleRows);
                for (int i = scrollIndex; i < endIndex; i++) {
                    NamedOption option = options.get(i);
                    int itemY = drawerY + ((i - scrollIndex) * itemHeight);
                    boolean hovered = mouseX >= this.x && mouseX <= this.x + this.width &&
                            mouseY >= itemY && mouseY < itemY + itemHeight;
                    boolean selected = (option.id == selectedOption.id);

                    if (selected) {
                        fill(matrices, this.x + 1, itemY + 1, this.x + this.width - 1, itemY + itemHeight - 1, 0xFF2563EB);
                    } else if (hovered) {
                        fill(matrices, this.x + 1, itemY + 1, this.x + this.width - 1, itemY + itemHeight - 1, 0xFF3F3F46);
                    }

                    MinecraftClient.getInstance().textRenderer.draw(matrices, option.name, this.x + 5, itemY + 5, 0xFFFFFF);
                }
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isOpen) {
                int drawerY = this.y + this.height;
                int visibleRows = Math.min(options.size(), maxVisibleRows);
                int drawerH = visibleRows * itemHeight;

                if (mouseX >= this.x && mouseX <= this.x + this.width &&
                        mouseY >= drawerY && mouseY <= drawerY + drawerH) {
                    int clickedIdx = scrollIndex + (int) ((mouseY - drawerY) / itemHeight);
                    if (clickedIdx >= 0 && clickedIdx < options.size()) {
                        selectedOption = options.get(clickedIdx);
                        onSelect.accept(selectedOption);
                        isOpen = false;
                        return true;
                    }
                } else {
                    isOpen = false;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
            if (isOpen) {
                if (amount < 0 && scrollIndex < options.size() - maxVisibleRows) {
                    scrollIndex++;
                    return true;
                } else if (amount > 0 && scrollIndex > 0) {
                    scrollIndex--;
                    return true;
                }
            }
            return super.mouseScrolled(mouseX, mouseY, amount);
        }
    }
}