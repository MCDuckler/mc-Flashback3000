package duckduck.flashback3000.playback;

import duckduck.flashback3000.Flashback3000;
import duckduck.flashback3000.protocol.ReplayLibrary;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlaybackManager {

    private final Flashback3000 plugin;
    private final ReplayLibrary library;
    private final Map<UUID, PlaybackSession> active = new ConcurrentHashMap<>();

    public PlaybackManager(Flashback3000 plugin) {
        this.plugin = plugin;
        this.library = new ReplayLibrary(plugin.getRecordingManager().outputRoot());
    }

    public boolean isPlaying(Player player) {
        return this.active.containsKey(player.getUniqueId());
    }

    public PlaybackSession start(Player player, Path replayPath) throws IOException {
        if (this.active.containsKey(player.getUniqueId())) {
            throw new IllegalStateException("Already playing back for " + player.getName());
        }
        ReplayFile replay = new ReplayFile(replayPath);
        PlaybackSession session = new PlaybackSession(this.plugin, player, replay);
        this.active.put(player.getUniqueId(), session);
        session.start();
        return session;
    }

    public @Nullable Path resolveReplay(String identifier) throws IOException {
        // Allow either a UUID or the bare filename (with or without .zip).
        try {
            UUID uuid = UUID.fromString(identifier);
            ReplayLibrary.Entry e = this.library.findById(uuid);
            return e != null ? e.path() : null;
        } catch (IllegalArgumentException ignored) {}

        String fileName = identifier.endsWith(".zip") ? identifier : identifier + ".zip";
        Path candidate = this.library.root().resolve(fileName);
        return java.nio.file.Files.exists(candidate) ? candidate : null;
    }

    public void cancel(Player player) {
        PlaybackSession session = this.active.remove(player.getUniqueId());
        if (session != null) {
            session.finish("Playback cancelled");
        }
    }

    public void shutdown() {
        for (PlaybackSession session : this.active.values()) {
            try { session.finish("Server shutting down"); } catch (Exception ignored) {}
        }
        this.active.clear();
    }

    public ReplayLibrary library() {
        return this.library;
    }
}
