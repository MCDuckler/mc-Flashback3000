package duckduck.flashback3000.api;

import duckduck.flashback3000.Flashback3000;
import duckduck.flashback3000.playback.PlaybackManager;
import duckduck.flashback3000.protocol.ReplayLibrary;
import duckduck.flashback3000.scene.ParsedScenes;
import duckduck.flashback3000.scene.SceneStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stable façade exposed to other plugins for triggering scene playback.
 * Obtain via {@link #get()}; returns {@code null} if the Flashback3000
 * plugin is missing or not yet enabled.
 */
public final class PlaybackApi {

    private static volatile @Nullable PlaybackApi instance;

    private final Flashback3000 plugin;
    private final PlaybackManager manager;
    private final SceneStore sceneStore;

    private PlaybackApi(Flashback3000 plugin, PlaybackManager manager, SceneStore sceneStore) {
        this.plugin = plugin;
        this.manager = manager;
        this.sceneStore = sceneStore;
    }

    public static void register(Flashback3000 plugin, PlaybackManager manager, SceneStore sceneStore) {
        instance = new PlaybackApi(plugin, manager, sceneStore);
    }

    public static void unregister() {
        instance = null;
    }

    public static @Nullable PlaybackApi get() {
        return instance;
    }

    public List<UUID> listReplays() {
        try {
            List<UUID> out = new ArrayList<>();
            for (ReplayLibrary.Entry e : this.manager.library().list()) out.add(e.uuid());
            return out;
        } catch (Exception e) {
            this.plugin.getLogger().warning("listReplays failed: " + e);
            return Collections.emptyList();
        }
    }

    public List<SceneSummary> listScenes(UUID replayId) {
        ParsedScenes parsed = this.sceneStore.load(replayId);
        if (parsed == null) return Collections.emptyList();
        List<SceneSummary> out = new ArrayList<>(parsed.scenes().size());
        for (ParsedScenes.Summary s : parsed.summaries()) {
            out.add(new SceneSummary(s.id(), s.name(), s.startTick(), s.endTick(), s.sampleCount()));
        }
        return out;
    }

    public boolean isPlaying(Player viewer) {
        return this.manager.isPlaying(viewer);
    }

    public CompletableFuture<Void> startScene(Player viewer, UUID replayId, String sceneId,
                                              ScenePlaybackOptions opts) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Runnable run = () -> {
            try {
                this.manager.startScene(viewer, replayId, sceneId, opts);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            run.run();
        } else {
            Bukkit.getScheduler().runTask(this.plugin, run);
        }
        return future;
    }

    /**
     * Multi-segment playback: each entry is a (replayId, sceneId) pair from a
     * possibly different recording. Played back-to-back with a wipe-Respawn +
     * snapshot reload between each. Use {@link TrailerEntry#of} to build the
     * list.
     */
    public CompletableFuture<Void> startTrailer(Player viewer,
                                                List<TrailerEntry> entries,
                                                ScenePlaybackOptions opts) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        java.util.List<duckduck.flashback3000.playback.PlaybackManager.TrailerEntry> mapped =
                new java.util.ArrayList<>(entries.size());
        for (TrailerEntry e : entries) {
            mapped.add(new duckduck.flashback3000.playback.PlaybackManager.TrailerEntry(e.replayId(), e.sceneId()));
        }
        Runnable run = () -> {
            try {
                this.manager.startTrailer(viewer, mapped, opts);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            run.run();
        } else {
            Bukkit.getScheduler().runTask(this.plugin, run);
        }
        return future;
    }

    public record TrailerEntry(UUID replayId, String sceneId) {
        public static TrailerEntry of(UUID replayId, String sceneId) {
            return new TrailerEntry(replayId, sceneId);
        }
    }

    public boolean cancel(Player viewer) {
        if (!this.manager.isPlaying(viewer)) return false;
        if (Bukkit.isPrimaryThread()) {
            this.manager.cancel(viewer);
        } else {
            Bukkit.getScheduler().runTask(this.plugin, () -> this.manager.cancel(viewer));
        }
        return true;
    }
}
