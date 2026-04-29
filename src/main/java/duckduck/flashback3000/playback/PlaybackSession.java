package duckduck.flashback3000.playback;

import duckduck.flashback3000.Flashback3000;
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
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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

    private int chunkIndex = 0;
    private @Nullable ReplayFile.ParsedChunk currentChunk;
    private int tickInChunk = 0;
    private boolean snapshotSent = false;
    private boolean finished = false;
    private @Nullable BukkitTask task;

    public PlaybackSession(Flashback3000 plugin, Player bukkitPlayer, ReplayFile replay) {
        this.plugin = plugin;
        this.bukkitPlayer = bukkitPlayer;
        this.serverPlayer = ((CraftPlayer) bukkitPlayer).getHandle();
        this.registryAccess = this.serverPlayer.registryAccess();
        this.channel = ((ServerCommonPacketListenerImpl) this.serverPlayer.connection).connection.channel;
        this.replay = replay;
        this.gamePacketCodec = GameProtocols.CLIENTBOUND_TEMPLATE
                .bind(RegistryFriendlyByteBuf.decorator(this.registryAccess)).codec();
        this.filter = new PlaybackFilter();
    }

    public Player player() {
        return this.bukkitPlayer;
    }

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
        try {
            if (this.tickInChunk >= this.currentChunk.ticks().size()) {
                advanceChunk();
                if (this.finished) return;
            }
            List<RawAction> tickActions = this.currentChunk.ticks().get(this.tickInChunk);
            this.tickInChunk++;
            for (RawAction action : tickActions) {
                dispatch(action);
            }
        } catch (Throwable t) {
            this.plugin.getLogger().severe("Playback tick failed: " + t);
            this.finish("Playback error");
        }
    }

    private void advanceChunk() {
        this.chunkIndex++;
        if (this.chunkIndex >= this.replay.chunkOrder().size()) {
            this.finish("Trailer complete");
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

        // Disable filter so subsequent traffic flows normally, then kick the player so a fresh
        // join rebuilds their actual world view. Crude but reliable.
        this.filter.setActive(false);
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            try {
                this.bukkitPlayer.kick(Component.text(reason + " — please reconnect"));
            } catch (Exception ignored) {}
        });

        // Remove filter handler from pipeline (safe even after kick).
        this.channel.eventLoop().execute(() -> {
            try {
                if (this.channel.pipeline().get(PlaybackFilter.NAME) != null) {
                    this.channel.pipeline().remove(PlaybackFilter.NAME);
                }
            } catch (Throwable ignored) {}
        });
    }
}
