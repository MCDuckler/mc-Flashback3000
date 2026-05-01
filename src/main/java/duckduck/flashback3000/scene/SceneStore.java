package duckduck.flashback3000.scene;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class SceneStore {

    private final Path root;
    private final Logger logger;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    public SceneStore(Path root, Logger logger) {
        this.root = root;
        this.logger = logger;
    }

    public Path pathFor(UUID replayId) {
        return this.root.resolve(replayId + ".scenes.json");
    }

    public boolean exists(UUID replayId) {
        return Files.isRegularFile(pathFor(replayId));
    }

    public void save(UUID replayId, byte[] json) throws IOException {
        Files.createDirectories(this.root);
        Path target = pathFor(replayId);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, json);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFail) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        this.cache.remove(replayId);
    }

    public boolean delete(UUID replayId) throws IOException {
        this.cache.remove(replayId);
        return Files.deleteIfExists(pathFor(replayId));
    }

    public @Nullable ParsedScenes load(UUID replayId) {
        Path path = pathFor(replayId);
        if (!Files.isRegularFile(path)) return null;
        try {
            FileTime mtime = Files.getLastModifiedTime(path);
            CacheEntry hit = this.cache.get(replayId);
            if (hit != null && hit.mtime.equals(mtime)) return hit.parsed;
            byte[] data = Files.readAllBytes(path);
            ParsedScenes parsed = ParsedScenes.parse(data);
            this.cache.put(replayId, new CacheEntry(mtime, parsed));
            return parsed;
        } catch (Exception e) {
            this.logger.warning("Failed to load scenes for " + replayId + ": " + e);
            return null;
        }
    }

    public List<ParsedScenes.Summary> list(UUID replayId) {
        ParsedScenes parsed = load(replayId);
        return parsed == null ? Collections.emptyList() : parsed.summaries();
    }

    private record CacheEntry(FileTime mtime, ParsedScenes parsed) {}
}
