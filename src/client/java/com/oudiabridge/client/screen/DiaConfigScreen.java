package com.oudiabridge.client.screen;

import com.oudiabridge.parser.OudiaParser;
import mtr.screen.DashboardScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class DiaConfigScreen extends Screen {
    private final DashboardScreen parent;
    private final OudiaParser.ParseResult data;

    // ★スクロール状態
    private int stationScroll = 0;
    private final int STATION_LIST_WIDTH = 200;
    private final int STATION_LIST_HEIGHT = 300;
    private final int MAX_VISIBLE_STATIONS = 12;

    public DiaConfigScreen(DashboardScreen parent, OudiaParser.ParseResult data) {
        super(Text.literal("ダイヤ設定 - " + data.routeName));
        this.parent = parent;
        this.data = data;
    }

    @Override
    protected void init() {
        int listX = 20, panelX = 240, bottomY = this.height - 40;

        // ★1. 駅リストスクロールボタン（最初）
        this.addDrawableChild(new ButtonWidget(listX, 40, 20, 20, Text.literal("↑"), btn -> scrollStations(-1)));
        this.addDrawableChild(new ButtonWidget(listX + STATION_LIST_WIDTH - 20, 40, 20, 20, Text.literal("↓"), btn -> scrollStations(1)));

        // ★2. 設定ボタン群
        this.addDrawableChild(new ButtonWidget(panelX, 40, 200, 20, Text.literal("🎨 路線色設定"), btn -> {
            System.out.println("🎨 路線色設定クリック！");
        }));
        this.addDrawableChild(new ButtonWidget(panelX, 70, 200, 20, Text.literal("🚂 列車タイプ選択"), btn -> {
            System.out.println("🚂 列車タイプ選択クリック！");
        }));
        this.addDrawableChild(new ButtonWidget(panelX, 100, 200, 20, Text.literal("⚙️ 駅間隔設定"), btn -> {
            System.out.println("⚙️ 駅間隔設定クリック！");
        }));
        this.addDrawableChild(new ButtonWidget(panelX, 130, 200, 20, Text.literal("🗺️ プレビュー生成"), btn -> {
            generatePreview();
        }));

        // ★3. 下部実行ボタン（最後）
        this.addDrawableChild(new ButtonWidget(this.width / 2 - 150, bottomY, 140, 30,
                Text.literal("🚄 MTRにダイヤ登録"), btn -> registerToMtr()));
        this.addDrawableChild(new ButtonWidget(this.width / 2 + 10, bottomY, 140, 30,
                Text.literal("← 戻る"), btn -> MinecraftClient.getInstance().setScreen(this.parent)));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // ★必須！親のmouseClickedを呼び出してボタンイベント有効化
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // ★駅リストクリックで選択変更
        int listX = 20, listY = 70;
        if (mouseX >= listX && mouseX <= listX + STATION_LIST_WIDTH &&
                mouseY >= listY && mouseY <= listY + STATION_LIST_HEIGHT) {

            int relativeY = (int) (mouseY - listY) / 25;
            int clickedIndex = stationScroll * MAX_VISIBLE_STATIONS + relativeY;

            if (clickedIndex < data.stations.size()) {
                stationScroll = clickedIndex / MAX_VISIBLE_STATIONS;
                System.out.println("👆 駅選択: " + data.stations.get(clickedIndex).name);
            }
            return true;
        }

        return false;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);

        // ★全ボタン描画前に背景描画（重要！）
        super.render(matrices, mouseX, mouseY, delta);  // ★ボタン描画を先に！

        // タイトル（ボタンの後ろ）
        this.textRenderer.drawWithShadow(matrices, this.title, 20, 10, 0xFFFFFF);

        // ★左側：駅リストパネル
        int listX = 20, listY = 70;
        net.minecraft.client.gui.hud.InGameHud.fill(matrices, listX, listY - 5,
                listX + STATION_LIST_WIDTH, listY + STATION_LIST_HEIGHT + 5, 0x80222222);

        // 駅リストヘッダ
        this.textRenderer.drawWithShadow(matrices, "📋 駅一覧 (" + data.stations.size() + "駅)",
                listX + 10, listY - 15, 0xFFFFFF);

        // ★スクロール表示駅リスト
        int startIndex = stationScroll;
        int endIndex = Math.min(startIndex + MAX_VISIBLE_STATIONS, data.stations.size());
        for (int i = startIndex; i < endIndex; i++) {
            String stationName = data.stations.get(i).name;
            boolean selected = i == stationScroll * MAX_VISIBLE_STATIONS; // 最初の駅ハイライト

            int y = listY + (i - startIndex) * 25;
            int bgColor = selected ? 0x804CAF50 : 0xFF333333;
            net.minecraft.client.gui.hud.InGameHud.fill(matrices, listX + 2, y,
                    listX + STATION_LIST_WIDTH - 2, y + 20, bgColor);

            this.textRenderer.drawWithShadow(matrices, (i + 1) + ". " + stationName,
                    listX + 5, y + 4, selected ? 0xFFFFFF : 0xCCCCCC);
        }

        // スクロール位置表示
        this.textRenderer.drawWithShadow(matrices,
                "ページ: " + (stationScroll + 1) + "/" + ((data.stations.size() + MAX_VISIBLE_STATIONS - 1) / MAX_VISIBLE_STATIONS),
                listX + 10, listY + STATION_LIST_HEIGHT + 5, 0xAAAAAA);

        // ★右側：設定パネル
        int panelX = 240, panelY = 40;
        net.minecraft.client.gui.hud.InGameHud.fill(matrices, panelX - 5, panelY - 5,
                panelX + 210, panelY + 200, 0x80222222);

        this.textRenderer.drawWithShadow(matrices, "⚙️ ダイヤ設定", panelX, panelY - 15, 0xFFFFFF);

        // 統計情報
        this.textRenderer.drawWithShadow(matrices, "路線: " + data.routeName, panelX + 10, panelY + 30, 0xFFFFFF);
        this.textRenderer.drawWithShadow(matrices, "総駅数: " + data.stations.size(), panelX + 10, panelY + 45, 0x4CAF50);
        this.textRenderer.drawWithShadow(matrices, "総列車数: " + data.trains.size(), panelX + 10, panelY + 60, 0xFF9800);

        // 選択駅情報（仮）
        this.textRenderer.drawWithShadow(matrices, "選択駅: " +
                        (stationScroll < data.stations.size() ? data.stations.get(stationScroll).name : "なし"),
                panelX + 10, panelY + 85, 0xFFFFFF);

        super.render(matrices, mouseX, mouseY, delta);
    }

    private void scrollStations(int direction) {
        int maxScroll = Math.max(0, (data.stations.size() - MAX_VISIBLE_STATIONS) / MAX_VISIBLE_STATIONS);
        stationScroll = Math.max(0, Math.min(maxScroll, stationScroll + direction));
    }

    private void generatePreview() {
        System.out.println("🗺️ 駅プレビュー生成: " + data.stations.get(stationScroll).name);
    }

    private void registerToMtr() {
        System.out.println("🚄 " + data.routeName + " 全列車自動登録開始！");
        System.out.println("📊 " + data.stations.size() + "駅 | " + data.trains.size() + "列車");

        // ★次ステップ：MTR RailwayData実登録
        MinecraftClient.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
