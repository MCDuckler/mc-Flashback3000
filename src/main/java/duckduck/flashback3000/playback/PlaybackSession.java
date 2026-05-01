package duckduck.flashback3000.playback;

import duckduck.flashback3000.Flashback3000;
import duckduck.flashback3000.api.EndBehavior;
import duckduck.flashback3000.scene.ParsedScenes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PlaybackSession {

    private static final ResourceLocation NEXT_TICK = ResourceLocation.fromNamespaceAndPath("flashback", "action/next_tick");
    private static final ResourceLocation GAME_PACKET = ResourceLocation.fromNamespaceAndPath("flashback", "action/game_packet");
    private static final ResourceLocation CONFIGURATION_PACKET = ResourceLocation.fromNamespaceAndPath("flashback", "action/configuration_packet");
    private static final ResourceLocation LEVEL_CHUNK_CACHED = ResourceLocation.fromNamespaceAndPath("flashback", "action/level_chunk_cached");
    private static final ResourceLocation MOVE_ENTITIES = ResourceLocation.fromNamespaceAndPath("flashback", "action/move_entities");
    private static final ResourceLocation CREATE_LOCAL_PLAYER = ResourceLocation.fromNamespaceAndPath("flashback", "action/create_local_player");
    private static final ResourceLocation ACCURATE_PLAYER_POSITION = ResourceLocation.fromNamespaceAndPath("flashback", "action/accurate_player_position_optional");

    private final Flashback3000 plugin;
    private final Player bukkitPlayer;
    private final ServerPlayer serverPlayer;
    private final Channel channel;
    private @Nullable ReplayFile replay;
    private final RegistryAccess registryAccess;
    private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gamePacketCodec;
    private PlaybackFilter filter;
    private final @Nullable TrailerPlan plan;
    private int segmentIndex = 0;
    private @Nullable TrailerPlan.TrailerSegment currentSegment;
    private boolean originalGameModeCaptured = false;
    private final @Nullable Runnable onFinish;

    private int chunkIndex = 0;
    private @Nullable ReplayFile.ParsedChunk currentChunk;
    private int tickInChunk = 0;
    private int globalTick = 0;
    private int teleportSeq = 0;
    private boolean snapshotSent = false;
    private boolean finished = false;
    private boolean dispatchingSnapshot = false;
    private boolean loadStartSent = false;
    private boolean chunkBatchOpen = false;
    private int snapshotChunkCount = 0;
    private @Nullable BukkitTask task;
    private final Set<String> droppedClassesSeen = new HashSet<>();

    // Camera anchor: an invisible MARKER entity the client interpolates between
    // server EntityPositionSync updates. We lock the client's view to it via
    // ClientboundSetCameraPacket. Player's own gamemode is switched to spectator
    // during the scene so movement keys don't interfere visually.
    private static final int CAMERA_ENTITY_ID_OFFSET = 0x4F3B0000;
    private int cameraEntityId = -1;
    private @Nullable GameType originalGameMode;

    // Boss bars the viewer was watching when the trailer started. We pull them
    // off the player at start so their HUD chrome doesn't clutter the cinematic,
    // and reattach at end so post-trailer play resumes with the same bars.
    private final java.util.List<org.bukkit.boss.KeyedBossBar> savedBossBars = new java.util.ArrayList<>();

    // Per-segment client state we've installed so we can tear it down at the
    // segment boundary without a full Respawn (and thus without the vanilla
    // "Loading terrain" screen). Tracks every entity id we sent AddEntity for
    // and every chunk we sent ClientboundLevelChunkWithLightPacket for; on
    // segment advance we emit RemoveEntities + ForgetLevelChunk for the whole
    // set, then dispatch the next segment's snapshot fresh.
    private final java.util.Set<Integer> liveEntityIds = new java.util.HashSet<>();
    private final java.util.Set<Long> liveChunks = new java.util.HashSet<>();

    public PlaybackSession(Flashback3000 plugin, Player bukkitPlayer, ReplayFile replay) {
        this(plugin, bukkitPlayer, replay, null, null);
    }

    public PlaybackSession(Flashback3000 plugin, Player bukkitPlayer, ReplayFile replay,
                           @Nullable TrailerPlan plan, @Nullable Runnable onFinish) {
        this.plugin = plugin;
        this.bukkitPlayer = bukkitPlayer;
        this.serverPlayer = ((CraftPlayer) bukkitPlayer).getHandle();
        this.registryAccess = this.serverPlayer.registryAccess();
        this.channel = ((ServerCommonPacketListenerImpl) this.serverPlayer.connection).connection.channel;
        this.replay = replay;
        this.gamePacketCodec = GameProtocols.CLIENTBOUND_TEMPLATE
                .bind(RegistryFriendlyByteBuf.decorator(this.registryAccess)).codec();
        this.filter = new PlaybackFilter();
        this.plan = plan;
        if (plan != null && !plan.segments().isEmpty()) {
            this.currentSegment = plan.first();
        }
        this.onFinish = onFinish;
    }

    public Player player() {
        return this.bukkitPlayer;
    }

    private static final int SKIP_TICKS_PER_PASS = 200;

    public void start() {
        PlaybackFilter.resetTrace();
        this.channel.eventLoop().execute(() -> {
            try {
                PlaybackFilter existing = (PlaybackFilter) this.channel.pipeline().get(PlaybackFilter.NAME);
                if (existing == null) {
                    // Install at the very tail so the filter intercepts outbound writes
                    // BEFORE the unbundler splits server bundles into delimiter+sub
                    // sequences. Packet bundles emitted by ServerEntity tracking arrive
                    // here intact, lets us drop the whole bundle (otherwise the unbundler
                    // emits sub-packets we drop, but downstream pipeline still produces
                    // delimiter bytes on the wire that the client reassembles into a
                    // bundle missing some sub-packets -> NPE / AIOOBE in the bundle
                    // handler). Inbound is consumed by packet_handler upstream so this
                    // filter is effectively outbound-only at this position, which is
                    // fine — recorded keep-alives are already suppressed at dispatch
                    // and plugin messages still reach ServerProtocol via packet_handler.
                    this.channel.pipeline().addLast(PlaybackFilter.NAME, this.filter);
                } else {
                    // Filter from a previous session is still installed (we leave it
                    // in place permanently as a vanilla-safety net). Reuse it and
                    // re-activate scene-mode dropping; otherwise active=false from the
                    // last session lets real-server bundles flow during this scene.
                    existing.setActive(true);
                    this.filter = existing;
                }
            } catch (Throwable t) {
                this.plugin.getLogger().severe("Failed to install playback filter: " + t);
                this.finish("Failed to install filter");
                return;
            }
            clearViewerBossBars();
            try {
                wipeClientWorld();
                this.currentChunk = this.replay.readChunk(this.replay.chunkOrder().get(0));
                this.dispatchSnapshot();
                if (this.plan != null && this.plan.overrideCamera()) {
                    installCameraAnchor();
                }
                this.snapshotSent = true;
            } catch (Throwable t) {
                this.plugin.getLogger().severe("Failed to dispatch initial snapshot: " + t);
                this.finish("Snapshot dispatch failed");
                return;
            }
            if (this.currentSegment != null && this.currentSegment.startTick() > 0) {
                runSkipAheadPass();
            } else {
                scheduleTickTimer();
            }
        });
    }

    /**
     * Sliced skip-ahead: dispatch up to SKIP_TICKS_PER_PASS ticks then yield the
     * netty event loop. Reschedules itself until {@code globalTick} reaches
     * the current segment's startTick, then hands off to the Bukkit per-tick
     * task.
     */
    private void runSkipAheadPass() {
        if (this.finished) return;
        int target = this.currentSegment.startTick();
        try {
            int pass = 0;
            while (this.globalTick < target && pass < SKIP_TICKS_PER_PASS) {
                if (this.tickInChunk >= this.currentChunk.ticks().size()) {
                    advanceChunk();
                    if (this.finished) return;
                    continue;
                }
                dispatchTick(false);
                pass++;
            }
        } catch (Throwable t) {
            this.plugin.getLogger().severe("Skip-ahead failed: " + t);
            this.finish("Skip-ahead failed");
            return;
        }
        if (this.finished) return;
        if (this.globalTick < target) {
            this.channel.eventLoop().execute(this::runSkipAheadPass);
        } else {
            scheduleTickTimer();
        }
    }

    private void scheduleTickTimer() {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (this.finished) return;
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 1L, 1L);
        });
    }

    private void dispatchSnapshot() {
        this.dispatchingSnapshot = true;
        this.snapshotChunkCount = 0;
        try {
            for (RawAction action : this.currentChunk.snapshot()) {
                dispatch(action);
            }
        } finally {
            this.dispatchingSnapshot = false;
            // Close the chunk batch we may have opened so the client knows the
            // initial chunk burst is done and can transition out of "Loading
            // terrain" with the right tick-rate measurement.
            if (this.chunkBatchOpen) {
                send(new ClientboundChunkBatchFinishedPacket(this.snapshotChunkCount));
                this.chunkBatchOpen = false;
            }
        }
    }

    /**
     * Send a Respawn with dataToKeep=0 so the client tears down its current
     * ClientLevel: entities + scoreboards + boss bars + container menus +
     * attribute maps are all cleared. The subsequent snapshot then bootstraps
     * the recording's world into a clean slate, which prevents duplicate
     * entity UUID collisions and stale-state mismatches with the recording's
     * mid-stream UPDATE actions.
     */
    /**
     * Use the same trick as Toolbox3000's ScreenFade: title with the U+D6FD
     * character which the CTA resource-pack font maps to a full-screen black
     * sprite. Falls back to a normal small character on viewers without the
     * resource pack but the function still runs harmlessly.
     */
    private void sendBlackFade(int fadeInTicks, int stayTicks, int fadeOutTicks) {
        try {
            // U+D47F: the same character Toolbox3000's ScreenFade uses; the CTA
            // resource-pack font maps it to a full-screen black sprite.
            net.kyori.adventure.text.Component adv = net.kyori.adventure.text.Component
                    .text("푿")
                    .color(net.kyori.adventure.text.format.NamedTextColor.BLACK);
            net.minecraft.network.chat.Component nms = io.papermc.paper.adventure.PaperAdventure.asVanilla(adv);
            net.minecraft.network.chat.Component empty = net.minecraft.network.chat.Component.empty();
            // PlaybackPacket wrapper bypasses the active filter (player.showTitle
            // would be dropped because the filter blocks all real-server traffic
            // while a session is active).
            this.channel.write(new PlaybackPacket(
                    new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(
                            fadeInTicks, stayTicks, fadeOutTicks)));
            this.channel.write(new PlaybackPacket(
                    new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(empty)));
            this.channel.writeAndFlush(new PlaybackPacket(
                    new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(nms)));
        } catch (Throwable t) {
            this.plugin.getLogger().fine("Fade-to-black failed: " + t);
        }
    }

    private void clearViewerBossBars() {
        // Two sources of pre-existing bars: Bukkit KeyedBossBar (showable to
        // a Player via addPlayer) and Adventure BossBar (player.showBossBar).
        // The KeyedBossBar path needs to fire BEFORE the filter goes active,
        // since removePlayer routes through the real-server path. Filter is
        // already active by the time this is called, so we send the actual
        // ClientboundBossEventPacket REMOVE wrapped in PlaybackPacket so it
        // bypasses the filter — works for both kinds.
        java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
        try {
            var iter = org.bukkit.Bukkit.getBossBars();
            while (iter.hasNext()) {
                org.bukkit.boss.KeyedBossBar bar = iter.next();
                if (bar.getPlayers().contains(this.bukkitPlayer)) {
                    this.savedBossBars.add(bar);
                    // KeyedBossBar -> CraftBossBar wraps a ServerBossEvent whose
                    // getId() is the wire UUID. Pull it via reflection to keep
                    // this code compatible across CraftBukkit refactors.
                    java.util.UUID id = extractKeyedBossBarUuid(bar);
                    if (id != null) ids.add(id);
                }
            }
        } catch (Throwable t) {
            this.plugin.getLogger().warning("Failed to enumerate Keyed boss bars: " + t);
        }
        try {
            for (var advBar : this.bukkitPlayer.activeBossBars()) {
                java.util.UUID id = extractAdventureBossBarUuid(advBar);
                if (id != null) ids.add(id);
            }
        } catch (Throwable t) {
            this.plugin.getLogger().warning("Failed to enumerate Adventure boss bars: " + t);
        }
        for (java.util.UUID id : ids) {
            try {
                this.channel.write(new PlaybackPacket(
                        net.minecraft.network.protocol.game.ClientboundBossEventPacket.createRemovePacket(id)));
            } catch (Throwable ignored) {}
        }
        this.channel.flush();
    }

    private static java.util.@org.jspecify.annotations.Nullable UUID extractKeyedBossBarUuid(org.bukkit.boss.KeyedBossBar bar) {
        try {
            // CraftBossBar.handle is the underlying ServerBossEvent
            java.lang.reflect.Field f = bar.getClass().getDeclaredField("handle");
            f.setAccessible(true);
            Object handle = f.get(bar);
            if (handle instanceof net.minecraft.world.BossEvent be) return be.getId();
        } catch (Throwable ignored) {}
        return null;
    }

    private static java.util.@org.jspecify.annotations.Nullable UUID extractAdventureBossBarUuid(net.kyori.adventure.bossbar.BossBar bar) {
        try {
            var impl = net.kyori.adventure.bossbar.BossBarImplementation.get(bar,
                    io.papermc.paper.adventure.BossBarImplementationImpl.class);
            java.lang.reflect.Field f = impl.getClass().getDeclaredField("vanilla");
            f.setAccessible(true);
            Object handle = f.get(impl);
            if (handle instanceof net.minecraft.world.BossEvent be) return be.getId();
        } catch (Throwable ignored) {}
        return null;
    }

    private void restoreViewerBossBars() {
        for (org.bukkit.boss.KeyedBossBar bar : this.savedBossBars) {
            try { bar.addPlayer(this.bukkitPlayer); } catch (Throwable ignored) {}
        }
        this.savedBossBars.clear();
    }

    private void wipeClientWorld() {
        try {
            var info = this.serverPlayer.createCommonSpawnInfo(this.serverPlayer.level());
            send(new ClientboundRespawnPacket(info, (byte) 0));
        } catch (Throwable t) {
            this.plugin.getLogger().warning("wipeClientWorld failed (continuing): " + t);
        }
    }

    private void sendChunkCacheConfig() {
        ParsedScenes.CameraSample first = this.currentSegment != null && !this.currentSegment.samples().isEmpty()
                ? this.currentSegment.samples().get(0) : null;
        double cx = first != null ? first.x() : this.serverPlayer.getX();
        double cy = first != null ? first.y() : this.serverPlayer.getY();
        double cz = first != null ? first.z() : this.serverPlayer.getZ();
        float yaw = first != null ? first.yaw() : this.serverPlayer.getYRot();
        float pitch = first != null ? first.pitch() : this.serverPlayer.getXRot();
        int chunkX = (int) Math.floor(cx / 16.0);
        int chunkZ = (int) Math.floor(cz / 16.0);
        int viewDistance = Math.max(8, this.serverPlayer.requestedViewDistance());
        send(new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));
        send(new ClientboundSetChunkCacheRadiusPacket(viewDistance));
        send(new ClientboundSetSimulationDistancePacket(viewDistance));
        // Plant the viewer's local player at the scene-start coordinates. Vanilla's
        // LevelLoadStatusManager counts chunks within view distance of the LOCAL
        // PLAYER POSITION; without this teleport the player sits at default (0,0,0)
        // and our chunks (at scene coords) don't count toward "enough chunks loaded",
        // so the loading screen blocks indefinitely. Once the camera anchor takes
        // over via SetCameraPacket the player's literal position becomes irrelevant
        // for rendering, but the chunk-loaded check needs it to be in the right area.
        PositionMoveRotation pmr = new PositionMoveRotation(
                new Vec3(cx, cy, cz), Vec3.ZERO, yaw, pitch);
        send(new ClientboundPlayerPositionPacket(++this.teleportSeq, pmr, java.util.Set.<Relative>of()));
    }

    private void tick() {
        if (this.finished) return;
        if (this.currentSegment != null && this.globalTick > this.currentSegment.endTick()) {
            // Current segment finished. Advance to next or finish trailer.
            if (this.task != null) { this.task.cancel(); this.task = null; }
            if (this.plan != null && !this.plan.isLast(this.segmentIndex)) {
                // Black fade covers the inter-segment hand-off (entity unwind +
                // chunk forget + new snapshot dispatch). 4t fade-in, 12t hold,
                // 4t fade-out. Advance fires once we're fully black.
                sendBlackFade(4, 12, 4);
                Bukkit.getScheduler().runTaskLater(this.plugin, this::advanceToNextSegment, 4L);
            } else {
                finish("Trailer complete");
            }
            return;
        }
        try {
            if (this.tickInChunk >= this.currentChunk.ticks().size()) {
                advanceChunk();
                if (this.finished) return;
            }
            dispatchTick(true);
        } catch (Throwable t) {
            this.plugin.getLogger().severe("Playback tick failed: " + t);
            this.finish("Playback error");
        }
    }

    /**
     * Close the current segment's replay file, tear down its installed client
     * state via {@link #unwindSegment()}, then load the next segment and
     * dispatch its snapshot. No Respawn is sent between segments, so the
     * vanilla "Loading terrain" screen never shows; the client level stays
     * live and we explicitly clear per-segment state instead.
     */
    private void advanceToNextSegment() {
        if (this.finished) return;
        // Tear down THIS segment's installed state on the client.
        unwindSegment();
        try { if (this.replay != null) this.replay.close(); } catch (Exception ignored) {}
        this.segmentIndex++;
        this.currentSegment = this.plan.segments().get(this.segmentIndex);
        try {
            this.replay = new ReplayFile(this.currentSegment.replayPath());
        } catch (Throwable t) {
            this.plugin.getLogger().severe("Failed to open segment " + this.segmentIndex + ": " + t);
            finish("Segment open failed");
            return;
        }
        // Reset per-segment state. Camera anchor entity stays alive across
        // segments - we just teleport it to the new sample. Game mode also
        // stays SPECTATOR so we don't re-emit the change-gamemode packet.
        this.chunkIndex = 0;
        this.tickInChunk = 0;
        this.globalTick = 0;
        this.snapshotSent = false;
        this.loadStartSent = false;
        this.chunkBatchOpen = false;
        this.snapshotChunkCount = 0;
        this.droppedClassesSeen.clear();
        this.plugin.getLogger().info("Trailer segment " + this.segmentIndex
                + " start: replay=" + this.currentSegment.replayId()
                + " scene=" + this.currentSegment.sceneId()
                + " ticks=" + this.currentSegment.startTick() + "-" + this.currentSegment.endTick());
        // Re-engage scene-mode dropping in case the filter went inactive.
        this.filter.setActive(true);
        this.channel.eventLoop().execute(() -> {
            try {
                this.currentChunk = this.replay.readChunk(this.replay.chunkOrder().get(0));
                this.dispatchSnapshot();
                // Camera anchor lives across segments; teleport it to this
                // segment's first sample so the cut isn't a hard view jump.
                if (this.plan.overrideCamera()) {
                    ParsedScenes.CameraSample first = !this.currentSegment.samples().isEmpty()
                            ? this.currentSegment.samples().get(0) : null;
                    if (first != null && this.cameraEntityId >= 0) {
                        sendCameraOverride(first);
                    }
                }
                this.snapshotSent = true;
            } catch (Throwable t) {
                this.plugin.getLogger().severe("Failed to dispatch segment snapshot: " + t);
                finish("Segment snapshot failed");
                return;
            }
            if (this.currentSegment.startTick() > 0) {
                runSkipAheadPass();
            } else {
                scheduleTickTimer();
            }
        });
    }

    /**
     * Drop every entity and chunk this segment installed on the client without
     * a Respawn. Called between segments to avoid the vanilla "Loading terrain"
     * screen. The client-level instance stays live; only the recording's
     * tracked state goes away.
     */
    private void unwindSegment() {
        if (!this.liveEntityIds.isEmpty()) {
            int[] ids = new int[this.liveEntityIds.size()];
            int i = 0;
            for (int id : this.liveEntityIds) ids[i++] = id;
            this.liveEntityIds.clear();
            // Don't drop our own camera anchor; if the recording happened to
            // touch it (it shouldn't since we use a high-offset id), keep it.
            if (this.cameraEntityId >= 0) {
                int[] filtered = new int[ids.length];
                int n = 0;
                for (int id : ids) if (id != this.cameraEntityId) filtered[n++] = id;
                ids = java.util.Arrays.copyOf(filtered, n);
            }
            if (ids.length > 0) {
                send(new ClientboundRemoveEntitiesPacket(ids));
            }
        }
        if (!this.liveChunks.isEmpty()) {
            for (long packed : this.liveChunks) {
                send(new ClientboundForgetLevelChunkPacket(new net.minecraft.world.level.ChunkPos(packed)));
            }
            this.liveChunks.clear();
        }
    }

    private void dispatchTick(boolean applyCameraOverride) {
        List<RawAction> tickActions = this.currentChunk.ticks().get(this.tickInChunk);
        this.tickInChunk++;
        for (RawAction action : tickActions) {
            dispatch(action);
        }
        if (applyCameraOverride && this.plan != null && this.plan.overrideCamera()
                && this.currentSegment != null) {
            ParsedScenes.CameraSample sample = this.currentSegment.sampleAt(this.globalTick);
            if (sample != null) sendCameraOverride(sample);
        }
        this.globalTick++;
    }

    private void sendCameraOverride(ParsedScenes.CameraSample sample) {
        if (this.cameraEntityId < 0) return;
        PositionMoveRotation pmr = new PositionMoveRotation(
                new Vec3(sample.x(), sample.y(), sample.z()),
                Vec3.ZERO,
                sample.yaw(),
                sample.pitch());
        // Client interpolates entity positions over a few ticks, so this is smooth
        // even at 20Hz unlike a direct ClientboundPlayerPositionPacket which snaps.
        send(new ClientboundEntityPositionSyncPacket(this.cameraEntityId, pmr, false));
        FriendlyByteBuf rh = new FriendlyByteBuf(Unpooled.buffer());
        rh.writeVarInt(this.cameraEntityId);
        rh.writeByte(encodeAngle(sample.yaw()));
        send(ClientboundRotateHeadPacket.STREAM_CODEC.decode(rh));
    }

    private void installCameraAnchor() {
        ParsedScenes.CameraSample first = this.currentSegment != null && !this.currentSegment.samples().isEmpty()
                ? this.currentSegment.samples().get(0) : null;
        double x = first != null ? first.x() : this.serverPlayer.getX();
        double y = first != null ? first.y() : this.serverPlayer.getY();
        double z = first != null ? first.z() : this.serverPlayer.getZ();
        float yaw = first != null ? first.yaw() : this.serverPlayer.getYRot();
        float pitch = first != null ? first.pitch() : this.serverPlayer.getXRot();

        // Pick an entity id unlikely to collide with anything live. Hash the
        // viewer uuid into the high bits so concurrent sessions don't clash.
        this.cameraEntityId = CAMERA_ENTITY_ID_OFFSET
                ^ (this.serverPlayer.getUUID().hashCode() & 0xFFFF);

        send(new ClientboundAddEntityPacket(
                this.cameraEntityId,
                UUIDUtil.createOfflinePlayerUUID("FlashbackCam-" + this.cameraEntityId),
                x, y, z, pitch, yaw,
                EntityType.ARMOR_STAND,
                0,
                Vec3.ZERO,
                yaw));

        // Set the armor stand to invisible (sharedFlags bit 5 = 0x20). The client
        // still uses the entity for camera lock + interpolation, but the model and
        // base plate are not rendered, so no plate/limbs clip into the camera view.
        RegistryFriendlyByteBuf data = new RegistryFriendlyByteBuf(Unpooled.buffer(), this.registryAccess);
        data.writeVarInt(this.cameraEntityId);
        data.writeByte(0);            // field index 0 = sharedFlags
        data.writeVarInt(0);          // serializer id 0 = BYTE
        data.writeByte(0x20);         // invisible flag
        data.writeByte(255);          // terminator
        send(ClientboundSetEntityDataPacket.STREAM_CODEC.decode(data));

        // Lock client view to the marker.
        FriendlyByteBuf cam = new FriendlyByteBuf(Unpooled.buffer());
        cam.writeVarInt(this.cameraEntityId);
        send(ClientboundSetCameraPacket.STREAM_CODEC.decode(cam));

        if (!this.originalGameModeCaptured) {
            this.originalGameMode = this.serverPlayer.gameMode.getGameModeForPlayer();
            this.originalGameModeCaptured = true;
        }
        send(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE,
                (float) GameType.SPECTATOR.getId()));
    }

    private void uninstallCameraAnchor() {
        if (this.cameraEntityId < 0) return;
        // Restore camera to the player.
        FriendlyByteBuf cam = new FriendlyByteBuf(Unpooled.buffer());
        cam.writeVarInt(this.serverPlayer.getId());
        send(ClientboundSetCameraPacket.STREAM_CODEC.decode(cam));
        // Despawn the marker.
        send(new ClientboundRemoveEntitiesPacket(this.cameraEntityId));
        // Restore game mode (defaults to SURVIVAL if we didn't capture original).
        GameType restore = this.originalGameMode != null ? this.originalGameMode : GameType.SURVIVAL;
        send(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE,
                (float) restore.getId()));
        this.cameraEntityId = -1;
    }

    private void advanceChunk() {
        this.chunkIndex++;
        if (this.chunkIndex >= this.replay.chunkOrder().size()) {
            // End of recording's tick stream. If we still have more segments
            // queued, advance to the next; otherwise we're done.
            if (this.task != null) { this.task.cancel(); this.task = null; }
            if (this.plan != null && !this.plan.isLast(this.segmentIndex)) {
                advanceToNextSegment();
            } else {
                finish(this.plan != null ? "Trailer complete" : "Replay complete");
            }
            return;
        }
        try {
            this.currentChunk = this.replay.readChunk(this.replay.chunkOrder().get(this.chunkIndex));
            this.tickInChunk = 0;
            this.loadStartSent = false;
            // Subsequent chunks have their own snapshot block; replay it for state continuity.
            dispatchSnapshot();
        } catch (Throwable t) {
            this.plugin.getLogger().severe("Failed to advance to chunk " + this.chunkIndex + ": " + t);
            this.finish("Chunk advance failed");
        }
    }

    private void dispatch(RawAction action) {
        ResourceLocation type = action.type();
        try {
            if (type.equals(GAME_PACKET)) {
                Packet<? super ClientGamePacketListener> packet = decodeGame(action.payload());
                if (this.plan != null && this.plan.overrideCamera()
                        && packet instanceof ClientboundPlayerPositionPacket) {
                    return;
                }
                // For trailer segments after the first, skip the recorded
                // LoginPacket - the client is already in the world from the
                // previous segment and we don't want to trigger Mojang's
                // ReceivingLevelScreen ("Loading terrain"). The other state
                // packets in the snapshot (border, time, spawn) re-apply
                // fine without a relog.
                if (this.segmentIndex > 0 && packet instanceof ClientboundLoginPacket) {
                    return;
                }
                if (this.plan != null && shouldDropForScene(packet, this.dispatchingSnapshot)) {
                    String name = packet.getClass().getSimpleName();
                    if (this.droppedClassesSeen.add(name)) {
                        this.plugin.getLogger().info("Scene playback dropped " + name + " (filter active)");
                    }
                    return;
                }
                // Defensive: a recorded SetEntityData may carry field indices that
                // exceed the receiving entity's data-array length when the recording
                // came from a ModelEngine-augmented server (extra custom fields) or
                // when the server reused an entity id. Vanilla client throws AIOOBE
                // during bundle apply -> kick. Drop only the offending packet (high
                // field indices), keep low-index ones so animations still flow. Done
                // for BOTH snapshot and mid-stream phases - the snapshot emits one
                // SetEntityData per cached entity and ModelEngine entities have
                // baked-in high field indices in the cache.
                if (this.plan != null
                        && packet instanceof ClientboundSetEntityDataPacket sed
                        && hasOutOfBoundsField(sed)) {
                    if (this.droppedClassesSeen.add("SetEntityData[oob]")) {
                        this.plugin.getLogger().info("Scene playback dropped SetEntityData with out-of-bounds field index");
                    }
                    return;
                }
                // Open the initial chunk batch right before the first chunk packet.
                // Vanilla's LevelLoadStatusManager waits for ChunkBatchStart +
                // chunks + ChunkBatchFinished to leave "Loading terrain"; sending
                // chunks without that envelope leaves the client stuck on the
                // loading screen even after all chunks already arrived.
                if (!this.loadStartSent && packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket) {
                    // Chunk cache center/radius/simulation distance must be sent
                    // AFTER LoginPacket (which resets client cache state) and
                    // BEFORE chunks, otherwise the client doesn't know which
                    // chunks fall in its render area and stalls on "Loading
                    // terrain" even after all chunks arrived.
                    sendChunkCacheConfig();
                    send(new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F));
                    send(ClientboundChunkBatchStartPacket.INSTANCE);
                    this.loadStartSent = true;
                    this.chunkBatchOpen = true;
                }
                if (this.dispatchingSnapshot && packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket) {
                    this.snapshotChunkCount++;
                }
                // Defensive: if the recording reused entity ids over its lifetime,
                // the client may already have a tracker entry of a different type
                // for this id. Drop the stale tracker before the new AddEntity so
                // subsequent SetEntityData / SetEquipment field indices match the
                // fresh entity type instead of overrunning the old type's field
                // array (-> AIOOBE -> kick).
                if (packet instanceof ClientboundAddEntityPacket add) {
                    send(new ClientboundRemoveEntitiesPacket(add.getId()));
                    this.liveEntityIds.add(add.getId());
                }
                if (packet instanceof ClientboundRemoveEntitiesPacket rem) {
                    for (int id : rem.getEntityIds()) this.liveEntityIds.remove(id);
                }
                if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
                    this.liveChunks.add(net.minecraft.world.level.ChunkPos.asLong(chunk.getX(), chunk.getZ()));
                }
                if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
                    this.liveChunks.remove(forget.pos().toLong());
                }
                send(packet);
            } else if (type.equals(LEVEL_CHUNK_CACHED)) {
                FriendlyByteBuf buf = wrap(action.payload());
                int idx = buf.readVarInt();
                byte[] cached = this.replay.cachedChunk(idx);
                Packet<? super ClientGamePacketListener> packet = decodeGame(cached);
                if (!this.loadStartSent) {
                    sendChunkCacheConfig();
                    send(new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F));
                    send(ClientboundChunkBatchStartPacket.INSTANCE);
                    this.loadStartSent = true;
                    this.chunkBatchOpen = true;
                }
                if (this.dispatchingSnapshot) {
                    this.snapshotChunkCount++;
                }
                if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
                    this.liveChunks.add(net.minecraft.world.level.ChunkPos.asLong(chunk.getX(), chunk.getZ()));
                }
                send(packet);
            } else if (type.equals(MOVE_ENTITIES)) {
                dispatchMoveEntities(action.payload());
            } else if (type.equals(CONFIGURATION_PACKET)
                    || type.equals(CREATE_LOCAL_PLAYER)
                    || type.equals(ACCURATE_PLAYER_POSITION)
                    || type.equals(NEXT_TICK)) {
                // Skipped: configuration handled by player's existing PLAY phase, local-player
                // identity is conveyed by the snapshot's login packet, accurate position is
                // optional, next_tick is consumed by the chunk parser.
            } else {
                // Unknown action: log once.
                this.plugin.getLogger().fine("Unhandled action type: " + type);
            }
        } catch (Throwable t) {
            this.plugin.getLogger().warning("Failed to dispatch " + type + ": " + t);
        }
    }

    private void dispatchMoveEntities(byte[] payload) {
        FriendlyByteBuf buf = wrap(payload);
        int levels = buf.readVarInt();
        for (int li = 0; li < levels; li++) {
            buf.readResourceKey(net.minecraft.core.registries.Registries.DIMENSION);
            int count = buf.readVarInt();
            for (int i = 0; i < count; i++) {
                int id = buf.readVarInt();
                double x = buf.readDouble();
                double y = buf.readDouble();
                double z = buf.readDouble();
                float yaw = buf.readFloat();
                float pitch = buf.readFloat();
                float headYRot = buf.readFloat();
                boolean onGround = buf.readBoolean();

                PositionMoveRotation pmr = new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch);
                send(new ClientboundEntityPositionSyncPacket(id, pmr, onGround));
                // Head yaw via a wire-format synthesized RotateHead packet.
                FriendlyByteBuf rh = new FriendlyByteBuf(Unpooled.buffer());
                rh.writeVarInt(id);
                rh.writeByte(encodeAngle(headYRot));
                send(ClientboundRotateHeadPacket.STREAM_CODEC.decode(rh));
            }
        }
    }

    private static byte encodeAngle(float deg) {
        return (byte) Math.floor(deg * 256.0f / 360.0f);
    }

    private static boolean isUltraCarsCameraPayload(Packet<?> packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket cp)) return false;
        var id = cp.payload().type().id();
        return "ultracars3000".equals(id.getNamespace()) && "vehicle_camera".equals(id.getPath());
    }

    /**
     * The recording can carry SetEntityData with field ids above any vanilla
     * entity's data-array length: server-side entity-id reuse makes mid-stream
     * updates target a different type than the snapshot's AddEntity, and
     * ModelEngine injects custom slots well past vanilla's max. Threshold > 24
     * is conservative (drops vanilla TextDisplay text updates whose id is 25-27,
     * which are visually unimportant for trailers) but reliably catches the
     * actual kicker (Index 27 / length 25 from real recordings).
     */
    private static boolean hasOutOfBoundsField(ClientboundSetEntityDataPacket sed) {
        for (var dv : sed.packedItems()) {
            if (dv.id() > 24) return true;
        }
        return false;
    }

    /**
     * Defense in depth on top of {@link #wipeClientWorld()}. The respawn-clear
     * sets the client to a clean slate before the snapshot replays, but the
     * recorder snapshot only restores chunks + entities — it doesn't restore
     * mid-recording transient state (an open chest GUI, a boss bar created
     * after recording start, a team modified during the recording window).
     * Mid-stream UPDATE/REMOVE actions for those still slip in, so this drop
     * list keeps catching them without the cinematic trailer caring.
     */
    private static boolean shouldDropForScene(Packet<?> packet, boolean inSnapshot) {
        // Always-drop: stateful packets the recorder snapshot doesn't reconstruct.
        // Letting them through during scene playback corrupts the client's local
        // maps (team / objective / boss-bar / tab-list / advancement / player-info)
        // and the vanilla handler hits Map.get(name).method(...) -> NPE -> "Network
        // Protocol Error" disconnect. None matter for a cinematic trailer.
        if (packet instanceof ClientboundSetPlayerTeamPacket
                || packet instanceof ClientboundSetObjectivePacket
                || packet instanceof ClientboundSetScorePacket
                || packet instanceof ClientboundResetScorePacket
                || packet instanceof ClientboundSetDisplayObjectivePacket
                || packet instanceof ClientboundBossEventPacket
                || packet instanceof ClientboundPlayerInfoUpdatePacket
                || packet instanceof ClientboundPlayerInfoRemovePacket
                || packet instanceof ClientboundTabListPacket
                || packet instanceof ClientboundUpdateAdvancementsPacket
                // Chat / action-bar / boss-bar / UltraCars camera plugin-message:
                // visual chrome that would distract from the trailer view.
                || packet instanceof ClientboundSystemChatPacket
                || packet instanceof ClientboundPlayerChatPacket
                || packet instanceof ClientboundDisguisedChatPacket
                || packet instanceof ClientboundSetActionBarTextPacket
                || isUltraCarsCameraPayload(packet)
                // Recorder sometimes captures attribute updates for non-living entities
                // (Item Display, Block Display, etc). Vanilla client throws
                // IllegalStateException on those. Drop wholesale for trailer use.
                || packet instanceof ClientboundUpdateAttributesPacket
                // Container packets can crash with AIOOBE when the recorded container's
                // slot count differs from the viewer's open menu (or no menu is open).
                // Trailers don't need GUIs to render, drop wholesale.
                || packet instanceof ClientboundContainerSetContentPacket
                || packet instanceof ClientboundContainerSetSlotPacket
                || packet instanceof ClientboundContainerSetDataPacket
                || packet instanceof ClientboundContainerClosePacket
                || packet instanceof ClientboundOpenScreenPacket
                // Recorded keep-alives would make the client echo a stale id, which Paper
                // checks against its own pending ping -> "out-of-order" disconnect. The
                // real server keep-alive is allowed through the filter on write side.
                || packet instanceof ClientboundKeepAlivePacket) {
            return true;
        }

        // Mid-stream entity-data is required by ModelEngine and friends to drive
        // animations / poses on display entities. We don't drop them - instead
        // the dispatcher pre-emits RemoveEntities before each AddEntity so the
        // client's tracker is fresh for the right entity type at each id.
        return false;
    }

    @SuppressWarnings("unchecked")
    private Packet<? super ClientGamePacketListener> decodeGame(byte[] payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), this.registryAccess);
        return (Packet<? super ClientGamePacketListener>) this.gamePacketCodec.decode(buf);
    }

    private FriendlyByteBuf wrap(byte[] payload) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
    }

    private void send(Packet<?> packet) {
        this.channel.writeAndFlush(new PlaybackPacket(packet));
    }

    public void finish(String reason) {
        if (this.finished) return;
        this.finished = true;
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        try { this.replay.close(); } catch (Exception ignored) {}

        // Hand camera back to the viewer's player and restore game mode while our
        // PlaybackPacket path is still authoritative on the wire.
        try { uninstallCameraAnchor(); } catch (Throwable t) {
            this.plugin.getLogger().warning("Failed to uninstall camera anchor: " + t);
        }
        // Reattach any boss bars we pulled off at scene start.
        try { restoreViewerBossBars(); } catch (Throwable t) {
            this.plugin.getLogger().warning("Failed to restore boss bars: " + t);
        }

        EndBehavior end = this.plan != null ? this.plan.endBehavior() : EndBehavior.KICK;
        this.filter.setActive(false);

        if (end == EndBehavior.KICK) {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                try {
                    this.bukkitPlayer.kick(Component.text(reason + " — please reconnect"));
                } catch (Exception ignored) {}
            });
        } else {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                try {
                    PlayerStateRestore.restore(this.serverPlayer);
                } catch (Throwable t) {
                    this.plugin.getLogger().warning("Restore failed for " + this.bukkitPlayer.getName() + ": " + t);
                    try {
                        this.bukkitPlayer.kick(Component.text("Playback ended; please reconnect"));
                    } catch (Exception ignored) {}
                }
            });
        }

        // Intentionally do NOT remove the filter from the pipeline. With active=false
        // it acts as a permanent safety net dropping ME-incompatible bundles that
        // would otherwise crash the vanilla client during regular post-scene play.
        // The filter goes away naturally when the connection closes.

        if (this.onFinish != null) {
            try { this.onFinish.run(); } catch (Throwable ignored) {}
        }
    }
}
