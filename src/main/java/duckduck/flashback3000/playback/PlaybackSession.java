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
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
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
    private final ReplayFile replay;
    private final RegistryAccess registryAccess;
    private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gamePacketCodec;
    private final PlaybackFilter filter;
    private final @Nullable ScenePlan plan;
    private final @Nullable Runnable onFinish;

    private int chunkIndex = 0;
    private @Nullable ReplayFile.ParsedChunk currentChunk;
    private int tickInChunk = 0;
    private int globalTick = 0;
    private int teleportSeq = 0;
    private boolean snapshotSent = false;
    private boolean finished = false;
    private boolean dispatchingSnapshot = false;
    private @Nullable BukkitTask task;
    private final Set<String> droppedClassesSeen = new HashSet<>();

    // Camera anchor: an invisible MARKER entity the client interpolates between
    // server EntityPositionSync updates. We lock the client's view to it via
    // ClientboundSetCameraPacket. Player's own gamemode is switched to spectator
    // during the scene so movement keys don't interfere visually.
    private static final int CAMERA_ENTITY_ID_OFFSET = 0x4F3B0000;
    private int cameraEntityId = -1;
    private @Nullable GameType originalGameMode;

    public PlaybackSession(Flashback3000 plugin, Player bukkitPlayer, ReplayFile replay) {
        this(plugin, bukkitPlayer, replay, null, null);
    }

    public PlaybackSession(Flashback3000 plugin, Player bukkitPlayer, ReplayFile replay,
                           @Nullable ScenePlan plan, @Nullable Runnable onFinish) {
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
                if (this.channel.pipeline().get(PlaybackFilter.NAME) == null) {
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
                }
            } catch (Throwable t) {
                this.plugin.getLogger().severe("Failed to install playback filter: " + t);
                this.finish("Failed to install filter");
                return;
            }
            try {
                wipeClientWorld();
                this.currentChunk = this.replay.readChunk(this.replay.chunkOrder().get(0));
                this.dispatchSnapshot();
                // Tell the client to switch from "Loading terrain" to in-game view.
                // Recorder snapshot doesn't include this; PlayerList.sendLevelInfo
                // sends it on a regular join.
                send(new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F));
                if (this.plan != null && this.plan.overrideCamera()) {
                    installCameraAnchor();
                }
                this.snapshotSent = true;
            } catch (Throwable t) {
                this.plugin.getLogger().severe("Failed to dispatch initial snapshot: " + t);
                this.finish("Snapshot dispatch failed");
                return;
            }
            if (this.plan != null && this.plan.startTick() > 0) {
                runSkipAheadPass();
            } else {
                scheduleTickTimer();
            }
        });
    }

    /**
     * Sliced skip-ahead: dispatch up to SKIP_TICKS_PER_PASS ticks then yield the
     * netty event loop. Reschedules itself until {@code globalTick} reaches
     * {@code plan.startTick()}, then hands off to the Bukkit per-tick task.
     */
    private void runSkipAheadPass() {
        if (this.finished) return;
        int target = this.plan.startTick();
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
        try {
            for (RawAction action : this.currentChunk.snapshot()) {
                dispatch(action);
            }
        } finally {
            this.dispatchingSnapshot = false;
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
    private void wipeClientWorld() {
        try {
            var info = this.serverPlayer.createCommonSpawnInfo(this.serverPlayer.level());
            send(new ClientboundRespawnPacket(info, (byte) 0));
        } catch (Throwable t) {
            this.plugin.getLogger().warning("wipeClientWorld failed (continuing): " + t);
        }
    }

    private void tick() {
        if (this.finished) return;
        if (this.plan != null && this.globalTick > this.plan.endTick()) {
            finish("Scene complete");
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

    private void dispatchTick(boolean applyCameraOverride) {
        List<RawAction> tickActions = this.currentChunk.ticks().get(this.tickInChunk);
        this.tickInChunk++;
        for (RawAction action : tickActions) {
            dispatch(action);
        }
        if (applyCameraOverride && this.plan != null && this.plan.overrideCamera()) {
            ParsedScenes.CameraSample sample = this.plan.sampleAt(this.globalTick);
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
        ParsedScenes.CameraSample first = this.plan.samples().isEmpty() ? null : this.plan.samples().get(0);
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

        this.originalGameMode = this.serverPlayer.gameMode.getGameModeForPlayer();
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
            this.finish(this.plan != null ? "Scene complete" : "Trailer complete");
            return;
        }
        try {
            this.currentChunk = this.replay.readChunk(this.replay.chunkOrder().get(this.chunkIndex));
            this.tickInChunk = 0;
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
                if (this.plan != null && shouldDropForScene(packet, this.dispatchingSnapshot)) {
                    String name = packet.getClass().getSimpleName();
                    if (this.droppedClassesSeen.add(name)) {
                        this.plugin.getLogger().info("Scene playback dropped " + name + " (filter active)");
                    }
                    return;
                }
                send(packet);
            } else if (type.equals(LEVEL_CHUNK_CACHED)) {
                FriendlyByteBuf buf = wrap(action.payload());
                int idx = buf.readVarInt();
                byte[] cached = this.replay.cachedChunk(idx);
                Packet<? super ClientGamePacketListener> packet = decodeGame(cached);
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

        // Mid-stream-only drops: keep these for the snapshot (so entities have their
        // initial state), drop them after snapshot finishes. Reason: server reuses
        // entity ids over the recording window. If id 3903 was Type A at snapshot
        // time and Type B mid-stream, the recorded SetEntityData carries fields
        // that exceed Type A's field-array bounds -> AIOOBE -> kick.
        if (!inSnapshot && packet instanceof ClientboundSetEntityDataPacket) {
            return true;
        }

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

        this.channel.eventLoop().execute(() -> {
            try {
                if (this.channel.pipeline().get(PlaybackFilter.NAME) != null) {
                    this.channel.pipeline().remove(PlaybackFilter.NAME);
                }
            } catch (Throwable ignored) {}
        });

        if (this.onFinish != null) {
            try { this.onFinish.run(); } catch (Throwable ignored) {}
        }
    }
}
