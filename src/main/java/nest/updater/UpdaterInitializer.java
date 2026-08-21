package nest.updater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oudiabridge.Oudiabridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class UpdaterInitializer implements ModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("ModUpdater");

    // 対象のGitHubリポジトリ情報
    private static final String GITHUB_OWNER = "nestaida4096";
    private static final String GITHUB_REPO = "MTR-OuDiaBridge";
    private static final String CURRENT_VERSION = FabricLoader.getInstance().getModContainer("oudiabridge").get().getMetadata().getVersion().getFriendlyString(); // 現在のModバージョン

    @Override
    public void onInitialize() {
        LOGGER.info("Starting Mod Updater...");

        try {
            boolean updateSuccess = runUpdateProcess();

            if (updateSuccess) {
                LOGGER.info("更新ファイルのダウンロード処理が正常に完了しました。");
            } else {
                LOGGER.info("更新処理はスキップされたか、最新バージョンを使用中です。");
            }
        } catch (Exception e) {
            LOGGER.error("Updater処理中にエラーが発生しました:", e);
        }

        // 更新処理完了後にメインModの初期化を呼び出す
        LOGGER.info("メインModの初期化へ移行します。");
        Oudiabridge.init();
    }

    private static boolean runUpdateProcess() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        // 1. GitHub REST API で最新リリースを取得
        String apiUrl = String.format("https://api.github.com/repos/%s/%s/releases/latest", GITHUB_OWNER, GITHUB_REPO);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("User-Agent", "Minecraft-Mod-Updater") // GitHub API要求: User-Agent必須
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOGGER.warn("GitHub APIからの応答が不正です (Status Code: {})", response.statusCode());
            return false;
        }

        // 2. JSONレスポンスの解析 (Minecraft同梱のGsonを使用)
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String latestVersion = json.get("tag_name").getAsString().replace("v", ""); // 例: "v1.1.0" -> "1.1.0"

        // バージョン比較
        if (latestVersion.equals(CURRENT_VERSION)) {
            LOGGER.info("既に最新バージョン ({}) が適用されています。", CURRENT_VERSION);
            return false;
        }

        LOGGER.info("新しいバージョンが見つかりました: {}", latestVersion);

        // 3. アセット（.jarファイル）のダウンロードURLを取得
        JsonArray assets = json.getAsJsonArray("assets");
        String downloadUrl = null;
        String fileName = null;

        for (JsonElement element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String assetName = asset.get("name").getAsString();

            // .jar ファイルを探す
            if (assetName.endsWith(".jar")) {
                downloadUrl = asset.get("browser_download_url").getAsString();
                fileName = assetName;
                break;
            }
        }

        if (downloadUrl == null) {
            LOGGER.warn("リリース内に適切な .jar ファイルが見つかりませんでした。");
            return false;
        }

        // 4. ファイルのダウンロード処理
        LOGGER.info("ダウンロード中: {} ...", fileName);
        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .header("User-Agent", "Minecraft-Mod-Updater")
                .GET()
                .build();

        // 保存先パスの設定（例: run/mods/downloaded_update.jar など）
        Path targetPath = Path.of("mods", fileName + ".jar");

        HttpResponse<InputStream> downloadResponse = client.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream());

        if (downloadResponse.statusCode() == 200) {
            try (InputStream in = downloadResponse.body()) {
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.info("ダウンロード完了: {}", targetPath.toAbsolutePath());
            return true;
        } else {
            LOGGER.error("ファイルのダウンロードに失敗しました (Status Code: {})", downloadResponse.statusCode());
            return false;
        }
    }
}
