package nest.oudiabridge.client.screen;

import nest.oudiabridge.parser.OudiaParser;
import mtr.screen.DashboardScreen;
import mtr.screen.FileUploaderScreen;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;
import java.util.List;

public class OudiaImportScreen extends FileUploaderScreen {
    private final DashboardScreen parent;

    public OudiaImportScreen(DashboardScreen parent) {
        super(parent, paths -> handleFilesDrop(paths, parent));  // ★ラムダ直書きで完璧！
        this.parent = parent;
    }

    /** ★staticメソッドにしてラムダから呼び出し */
    private static void handleFilesDrop(List<Path> paths, DashboardScreen parent) {
        System.out.println("🟢 MTR FileUploaderScreen → ドロップ！" + paths.size());

        // .oud2優先（なければ最初のを処理）
        Path targetFile = paths.stream()
                .filter(p -> p.toString().toLowerCase().endsWith(".oud2"))
                .findFirst()
                .orElse(paths.get(0));

        processOudiaFile(targetFile, parent);
    }

    private static void processOudiaFile(Path path, DashboardScreen parent) {
        System.out.println("📁 解析: " + path);

        new Thread(() -> {
            try {
                java.io.File file = path.toFile();
                OudiaParser.ParseResult result = OudiaParser.parseOud2(file);

                System.out.println("✅ 解析完了！" + result.routeName);
                System.out.println("📊 " + result.stations.size() + "駅 | " + result.trains.size() + "列車");

                // ★DiaConfigScreenへ
                MinecraftClient.getInstance().execute(() -> {
                    MinecraftClient.getInstance().setScreen(new DiaConfigScreen(parent, result));
                });

            } catch (Exception e) {
                System.out.println("❌ エラー: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
