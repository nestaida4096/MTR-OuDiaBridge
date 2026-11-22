package nest.oudiabridge.mtr4;

import nest.oudiabridge.parser.OudiaParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side repository for imported .oud2 timetable files. */
public final class OudiaTimetableRepository {
    private static final Map<String, OudiaParser.ParseResult> TIMETABLES = new ConcurrentHashMap<>();

    private OudiaTimetableRepository() {
    }

    public static ReloadResult reload(Path serverDirectory) throws IOException {
        Path directory = serverDirectory.resolve("config").resolve("oudiabridge").resolve("timetables")
                .toAbsolutePath().normalize();
        Files.createDirectories(directory);

        Map<String, OudiaParser.ParseResult> loaded = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        try (var stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".oud2"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        String id = fileName.substring(0, fileName.length() - ".oud2".length());
                        try {
                            OudiaParser.ParseResult result = OudiaParser.parseOud2(path.toFile());
                            loaded.put(id, result);
                        } catch (Exception exception) {
                            failures.put(fileName, exception.getClass().getSimpleName() + ": " + exception.getMessage());
                        }
                    });
        }

        TIMETABLES.clear();
        TIMETABLES.putAll(loaded);
        return new ReloadResult(directory, loaded.size(), failures);
    }

    public static Map<String, OudiaParser.ParseResult> getAll() {
        return Collections.unmodifiableMap(TIMETABLES);
    }

    public record ReloadResult(Path directory, int loadedCount, Map<String, String> failures) {
        public ReloadResult {
            failures = Map.copyOf(failures);
        }
    }
}
