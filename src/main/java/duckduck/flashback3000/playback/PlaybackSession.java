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
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

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
    private @Nullable BukkitTask task;

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
        this.channel.eventLoop().execute(() -> {
            try {
                if (this.channel.pipeline().get(PlaybackFilter.NAME) == null) {
                    this.channel.pipeline().addBefore("unbundler", PlaybackFilter.NAME, this.filter);
                }
            } catch (Throwable t) {
                this.plugin.getLogger().severe("Failed to install playback filter: " + t);
                this.finish("Failed to install filter");
                return;
            }
            try {
                this.currentChunk = this.replay.readChunk(this.replay.chunkOrder().get(0));
                this.dispatchSnapshot();
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
        for (RawAction action : this.currentChunk.snapshot()) {
            dispatch(action);
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
        PositionMoveRotation pmr = new PositionMoveRotation(
                new Vec3(sample.x(), sample.y(), sample.z()),
                Vec3.ZERO,
                sample.yaw(),
                sample.pitch());
        ClientboundPlayerPositionPacket pkt = new ClientboundPlayerPositionPacket(
                ++this.teleportSeq, pmr, Set.<Relative>of());
        send(pkt);
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
                if (this.plan != null && shouldDropForScene(packet)) {
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
     * Packets the recorder captures mid-stream that reference state the snapshot
     * doesn't reconstruct. Letting them through during scene playback corrupts
     * the client's local maps (e.g. team UPDATE with no prior ADD) and triggers
     * a "Network Protocol Error" disconnect. None of these matter for a
     * cinematic trailer, so drop them.
     */
    private static boolean shouldDropForScene(Packet<?> packet) {
        return packet instanceof ClientboundSetPlayerTeamPacket
                || packet instanceof ClientboundSetObjectivePacket
                || packet instanceof ClientboundSetScorePacket
                || packet instanceof ClientboundSetDisplayObjectivePacket;
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
