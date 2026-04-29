package duckduck.flashback3000;

import duckduck.flashback3000.record.Recorder;
import duckduck.flashback3000.record.ReplayExporter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RecordingManager {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Flashback3000 plugin;
    private final Path outputRoot;
    private final Path workRoot;
    private final Map<UUID, Recorder> active = new ConcurrentHashMap<>();

    public RecordingManager(Flashback3000 plugin) {
        this.plugin = plugin;
        this.outputRoot = plugin.getDataFolder().toPath().resolve("replays");
        this.workRoot = plugin.getDataFolder().toPath().resolve("work");
        try {
            Files.createDirectories(this.outputRoot);
            Files.createDirectories(this.workRoot);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Path outputRoot() {
        return this.outputRoot;
    }

    public boolean isRecording(Player player) {
        return this.active.containsKey(player.getUniqueId());
    }

    public Recorder start(Player player, @Nullable String name) {
        if (this.active.containsKey(player.getUniqueId())) {
            throw new IllegalStateException("Already recording " + player.getName());
        }
        String label = name != null ? name : player.getName() + "-" + LocalDateTime.now().format(STAMP);
        Path workFolder = this.workRoot.resolve(player.getUniqueId() + "-" + UUID.randomUUID());
        Recorder recorder = new Recorder(player, workFolder, label);
        this.active.put(player.getUniqueId(), recorder);
        recorder.start(this.plugin);
        return recorder;
    }

    public @Nullable Path stopAndExport(Player player) {
        Recorder recorder = this.active.remove(player.getUniqueId());
        if (recorder == null) return null;
        return finalizeRecording(recorder);
    }

    public void cancel(Player player) {
        Recorder recorder = this.active.remove(player.getUniqueId());
        if (recorder == null) return;
        try {
            Path folder = recorder.stop();
            deleteRecursive(folder);
        } catch (Exception e) {
            this.plugin.getLogger().warning("Failed to cancel recording: " + e);
        }
    }

    public void handlePlayerQuit(Player player) {
        Recorder recorder = this.active.remove(player.getUniqueId());
        if (recorder == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> finalizeRecording(recorder));
    }

    public void shutdown() {
        for (Recorder recorder : this.active.values()) {
            try {
                finalizeRecording(recorder);
            } catch (Exception e) {
                this.plugin.getLogger().warning("Failed to finalize recording during shutdown: " + e);
            }
        }
        this.active.clear();
    }

    public Map<UUID, Recorder> activeRecorders() {
        return this.active;
    }

    private Path finalizeRecording(Recorder recorder) {
        Path workFolder = recorder.stop();
        String fileName = recorder.metadata().name.replaceAll("[^A-Za-z0-9._-]", "_") + ".zip";
        Path outFile = this.outputRoot.resolve(fileName);
        int dedupe = 1;
        while (Files.exists(outFile)) {
            outFile = this.outputRoot.resolve(recorder.metadata().name.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + (dedupe++) + ".zip");
        }
        try {
            ReplayExporter.export(workFolder, recorder.metadata(), outFile, null);
            deleteRecursive(workFolder);
        } catch (IOException e) {
            this.plugin.getLogger().severe("Failed to export replay: " + e);
            return null;
        }
        return outFile;
    }

    private static void deleteRecursive(Path path) {
        if (!Files.exists(path)) return;
        try {
            Files.walk(path)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }
}
