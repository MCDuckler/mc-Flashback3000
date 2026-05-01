package duckduck.flashback3000.playback;

import duckduck.flashback3000.Flashback3000;
import duckduck.flashback3000.api.ScenePlaybackOptions;
import duckduck.flashback3000.protocol.ReplayLibrary;
import duckduck.flashback3000.scene.ParsedScenes;
import duckduck.flashback3000.scene.SceneStore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlaybackManager implements Listener {

    private final Flashback3000 plugin;
    private final ReplayLibrary library;
    private final SceneStore sceneStore;
    private final Map<UUID, PlaybackSession> active = new ConcurrentHashMap<>();

    public PlaybackManager(Flashback3000 plugin, SceneStore sceneStore) {
        this.plugin = plugin;
        this.library = new ReplayLibrary(plugin.getRecordingManager().outputRoot());
        this.sceneStore = sceneStore;
    }

    public boolean isPlaying(Player player) {
        return this.active.containsKey(player.getUniqueId());
    }

    public PlaybackSession start(Player player, Path replayPath) throws IOException {
        if (this.active.containsKey(player.getUniqueId())) {
            throw new IllegalStateException("Already playing back for " + player.getName());
        }
        ReplayFile replay = new ReplayFile(replayPath);
        UUID viewerId = player.getUniqueId();
        PlaybackSession session = new PlaybackSession(this.plugin, player, replay, null,
                () -> {
                    this.active.remove(viewerId);
                    if (player.isOnline() && this.plugin.getServerProtocol() != null) {
                        this.plugin.getServerProtocol().sendPlaybackStatus(
                                player, false, new UUID(0L, 0L), "", "Playback ended");
                    }
                });
        this.active.put(viewerId, session);
        session.start();
        return session;
    }

    public PlaybackSession startScene(Player player, UUID replayId, String sceneId,
                                      ScenePlaybackOptions opts) throws IOException {
        if (this.active.containsKey(player.getUniqueId())) {
            throw new IllegalStateException("Already playing back for " + player.getName());
        }
        ReplayLibrary.Entry entry = this.library.findById(replayId);
        if (entry == null) throw new IOException("Replay not found: " + replayId);
        ParsedScenes scenes = this.sceneStore.load(replayId);
        if (scenes == null) throw new IOException("No scenes uploaded for replay " + replayId);
        ParsedScenes.Scene scene = scenes.findById(sceneId)
                .orElseThrow(() -> new IOException("Scene not found: " + sceneId));
        if (!scene.isWellFormed()) {
            throw new IOException("Scene " + sceneId + " malformed");
        }
        TrailerPlan.TrailerSegment segment = new TrailerPlan.TrailerSegment(
                entry.path(), replayId, scene.id(),
                scene.startTick(), scene.endTick(), scene.samples());
        TrailerPlan plan = new TrailerPlan(java.util.List.of(segment),
                opts.end(), opts.overrideCamera());
        ReplayFile replay = new ReplayFile(entry.path());
        UUID viewerId = player.getUniqueId();
        PlaybackSession session = new PlaybackSession(this.plugin, player, replay, plan,
                () -> {
                    this.active.remove(viewerId);
                    if (player.isOnline() && this.plugin.getServerProtocol() != null) {
                        this.plugin.getServerProtocol().sendPlaybackStatus(
                                player, false, replayId, sceneId, "Scene ended");
                    }
                });
        this.active.put(viewerId, session);
        this.plugin.getLogger().info("Scene playback start: replay=" + replayId
                + " scene=" + sceneId + " ticks=" + scene.startTick() + "-" + scene.endTick()
                + " viewer=" + player.getName() + " end=" + opts.end());
        session.start();
        return session;
    }

    /**
     * Build a multi-segment {@link TrailerPlan} from a list of (replayId, sceneId)
     * pairs. Each pair is resolved against the library + scene store. Throws if
     * any pair is missing.
     */
    public PlaybackSession startTrailer(Player player,
                                        java.util.List<TrailerEntry> entries,
                                        ScenePlaybackOptions opts) throws IOException {
        if (this.active.containsKey(player.getUniqueId())) {
            throw new IllegalStateException("Already playing back for " + player.getName());
        }
        if (entries == null || entries.isEmpty()) {
            throw new IOException("Trailer needs at least one segment");
        }
        java.util.List<TrailerPlan.TrailerSegment> segments = new java.util.ArrayList<>(entries.size());
        for (TrailerEntry te : entries) {
            ReplayLibrary.Entry entry = this.library.findById(te.replayId());
            if (entry == null) throw new IOException("Replay not found: " + te.replayId());
            ParsedScenes scenes = this.sceneStore.load(te.replayId());
            if (scenes == null) throw new IOException("No scenes uploaded for replay " + te.replayId());
            ParsedScenes.Scene scene = scenes.findById(te.sceneId())
                    .orElseThrow(() -> new IOException("Scene not found: " + te.sceneId() + " in " + te.replayId()));
            if (!scene.isWellFormed()) {
                throw new IOException("Scene " + te.sceneId() + " malformed in " + te.replayId());
            }
            segments.add(new TrailerPlan.TrailerSegment(
                    entry.path(), te.replayId(), scene.id(),
                    scene.startTick(), scene.endTick(), scene.samples()));
        }
        TrailerPlan plan = new TrailerPlan(segments, opts.end(), opts.overrideCamera());
        TrailerPlan.TrailerSegment first = plan.first();
        ReplayFile replay = new ReplayFile(first.replayPath());
        UUID viewerId = player.getUniqueId();
        UUID firstReplayId = first.replayId();
        String firstSceneId = first.sceneId();
        PlaybackSession session = new PlaybackSession(this.plugin, player, replay, plan,
                () -> {
                    this.active.remove(viewerId);
                    if (player.isOnline() && this.plugin.getServerProtocol() != null) {
                        this.plugin.getServerProtocol().sendPlaybackStatus(
                                player, false, firstReplayId, firstSceneId, "Trailer ended");
                    }
                });
        this.active.put(viewerId, session);
        StringBuilder log = new StringBuilder("Trailer playback start: viewer=").append(player.getName())
                .append(" segments=").append(segments.size())
                .append(" end=").append(opts.end()).append(" [");
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) log.append(", ");
            log.append(segments.get(i).replayId()).append(':').append(segments.get(i).sceneId());
        }
        log.append(']');
        this.plugin.getLogger().info(log.toString());
        session.start();
        return session;
    }

    public record TrailerEntry(UUID replayId, String sceneId) {}

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

    public SceneStore sceneStore() {
        return this.sceneStore;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PlaybackSession session = this.active.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            try { session.finish("Player disconnected"); } catch (Exception ignored) {}
        }
    }
}
