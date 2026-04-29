package duckduck.flashback3000.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import duckduck.flashback3000.Flashback3000;
import duckduck.flashback3000.action.ActionCreateLocalPlayer;
import duckduck.flashback3000.action.ActionMoveEntities;
import duckduck.flashback3000.action.ActionNextTick;
import duckduck.flashback3000.compat.ModelEngineCompat;
import duckduck.flashback3000.io.AsyncReplaySaver;
import duckduck.flashback3000.io.ReplayWriter;
import duckduck.flashback3000.netty.PacketCaptureHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

public class Recorder {

    public static final int CHUNK_LENGTH_TICKS = 5 * 60 * 20;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final org.bukkit.entity.Player bukkitPlayer;
    private final ServerPlayer serverPlayer;
    private final RegistryAccess registryAccess;
    private final AsyncReplaySaver asyncReplaySaver;

    private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gamePacketCodec;
    private final StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> configurationPacketCodec;

    private final FlashbackMeta metadata = new FlashbackMeta();
    private final Path recordFolder;
    private PacketCaptureHandler captureHandler;
    private @Nullable BukkitTask tickTask;

    private final ConcurrentLinkedQueue<Packet<?>> pendingPackets = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Throwable> capturedError = new AtomicReference<>();
    private final java.util.WeakHashMap<Entity, EntityPos> lastPositions = new java.util.WeakHashMap<>();

    private int writtenTicksInChunk = 0;
    private int writtenTicks = 0;
    private boolean needsInitialSnapshot = true;
    private volatile boolean stopped = false;

    public Recorder(org.bukkit.entity.Player bukkitPlayer, Path recordFolder, String name) {
        this.bukkitPlayer = bukkitPlayer;
        this.serverPlayer = ((CraftPlayer) bukkitPlayer).getHandle();
        this.registryAccess = serverPlayer.registryAccess();
        this.recordFolder = recordFolder;
        this.asyncReplaySaver = new AsyncReplaySaver(this.registryAccess, recordFolder);

        this.configurationPacketCodec = ConfigurationProtocols.CLIENTBOUND.codec();
        this.gamePacketCodec = GameProtocols.CLIENTBOUND_TEMPLATE
                .bind(RegistryFriendlyByteBuf.decorator(this.registryAccess)).codec();

        this.metadata.replayIdentifier = UUID.randomUUID();
        this.metadata.name = name;
        this.metadata.dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        this.metadata.protocolVersion = SharedConstants.getProtocolVersion();
        this.metadata.versionString = SharedConstants.getCurrentVersion().name();
        this.metadata.worldName = bukkitPlayer.getWorld().getName();
    }

    public Path recordFolder() {
        return this.recordFolder;
    }

    public FlashbackMeta metadata() {
        return this.metadata;
    }

    public org.bukkit.entity.Player bukkitPlayer() {
        return this.bukkitPlayer;
    }

    public void start(Flashback3000 plugin) {
        ServerCommonPacketListenerImpl listener = this.serverPlayer.connection;
        Channel channel = listener.connection.channel;
        this.captureHandler = new PacketCaptureHandler(this);
        channel.eventLoop().execute(() -> {
            try {
                channel.pipeline().addBefore("unbundler", PacketCaptureHandler.NAME, this.captureHandler);
            } catch (Throwable t) {
                this.onError(t);
            }
        });

        this.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void onError(Throwable t) {
        this.capturedError.compareAndSet(null, t);
        Flashback3000.getInstance().getLogger().severe("Recorder error for " + this.bukkitPlayer.getName() + ": " + t);
    }

    public void acceptOutboundPacket(Packet<?> packet) {
        if (this.stopped) return;
        if (IgnoredPackets.isIgnored(packet)) return;
        if (packet instanceof ClientboundCustomPayloadPacket cp) {
            String ns = cp.payload().type().id().getNamespace();
            if (duckduck.flashback3000.protocol.PacketIds.CHANNEL_NAMESPACE.equals(ns)) return;
        }
        // Drop packets targeting the recording player's own entity — the local-player view is
        // reconstructed by the snapshot + create_local_player action, so per-tick mutations
        // would double up and flicker on playback.
        int selfId = this.serverPlayer.getId();
        if (packet instanceof ClientboundSetEntityDataPacket data && data.id() == selfId) return;
        if (packet instanceof ClientboundSetEquipmentPacket equip && equip.getEntity() == selfId) return;
        this.pendingPackets.add(packet);
    }

    public void tick() {
        if (this.stopped) return;
        try {
            if (this.needsInitialSnapshot) {
                this.needsInitialSnapshot = false;
                this.writeInitialSnapshot();
            }
            this.flushPendingPackets();
            this.writeEntityPositions();
            this.asyncReplaySaver.submit(w -> w.startAndFinishAction(ActionNextTick.INSTANCE));
            this.writtenTicksInChunk++;
            this.writtenTicks++;

            if (this.writtenTicksInChunk >= CHUNK_LENGTH_TICKS) {
                this.flushChunk(false);
            }
        } catch (Throwable t) {
            this.onError(t);
        }
    }

    private void flushPendingPackets() {
        if (this.pendingPackets.isEmpty()) return;
        List<Packet<? super ClientGamePacketListener>> gamePackets = new ArrayList<>();
        Packet<?> p;
        while ((p = this.pendingPackets.poll()) != null) {
            @SuppressWarnings("unchecked")
            Packet<? super ClientGamePacketListener> casted = (Packet<? super ClientGamePacketListener>) p;
            gamePackets.add(casted);
        }
        this.asyncReplaySaver.writeGamePackets(this.gamePacketCodec, gamePackets);
    }

    private void flushChunk(boolean closing) {
        int chunkId = this.metadata.chunks.size();
        String chunkName = "c" + chunkId + ".flashback";
        FlashbackChunkMeta chunkMeta = new FlashbackChunkMeta();
        chunkMeta.duration = this.writtenTicksInChunk;
        this.metadata.chunks.put(chunkName, chunkMeta);
        this.metadata.totalTicks = this.writtenTicks;
        String json = GSON.toJson(this.metadata.toJson());
        this.asyncReplaySaver.writeReplayChunk(chunkName, json);
        this.writtenTicksInChunk = 0;
        if (!closing) {
            this.writeInitialSnapshot();
        }
    }

    public Path stop() {
        if (this.stopped) return this.recordFolder;
        this.stopped = true;
        if (this.tickTask != null) {
            this.tickTask.cancel();
            this.tickTask = null;
        }
        try {
            ServerCommonPacketListenerImpl listener = this.serverPlayer.connection;
            Channel channel = listener.connection.channel;
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(PacketCaptureHandler.NAME) != null) {
                        channel.pipeline().remove(PacketCaptureHandler.NAME);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}

        try {
            this.flushPendingPackets();
            if (this.writtenTicksInChunk == 0) {
                this.asyncReplaySaver.submit(w -> w.startAndFinishAction(ActionNextTick.INSTANCE));
                this.writtenTicksInChunk = 1;
                this.writtenTicks++;
            }
            this.flushChunk(true);
        } catch (Throwable t) {
            this.onError(t);
        }
        return this.asyncReplaySaver.finish();
    }

    // -------- snapshot generation --------

    private void writeInitialSnapshot() {
        this.asyncReplaySaver.submit(ReplayWriter::startSnapshot);
        try {
            this.writeConfigurationSnapshot();
            this.writeGameSnapshot();
        } catch (Throwable t) {
            this.onError(t);
        }
        this.asyncReplaySaver.submit(ReplayWriter::endSnapshot);
    }

    private void writeConfigurationSnapshot() {
        ServerLevel level = this.serverPlayer.level();
        List<Packet<? super ClientConfigurationPacketListener>> configurationPackets = new ArrayList<>();

        configurationPackets.add(new ClientboundUpdateEnabledFeaturesPacket(
                FeatureFlags.REGISTRY.toNames(level.enabledFeatures())));

        RegistryOps<Tag> dynamicOps = this.registryAccess.createSerializationContext(NbtOps.INSTANCE);
        RegistrySynchronization.packRegistries(dynamicOps, this.registryAccess, Set.of(), (resourceKey, list) ->
                configurationPackets.add(new ClientboundRegistryDataPacket(resourceKey, list)));

        MinecraftServer tagServer = MinecraftServer.getServer();
        if (tagServer != null) {
            Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> serializedTags =
                    TagNetworkSerialization.serializeTagsToNetwork(tagServer.registries());
            configurationPackets.add(new ClientboundUpdateTagsPacket(serializedTags));
        }

        this.asyncReplaySaver.writeConfigurationPackets(this.configurationPacketCodec, configurationPackets);
    }

    private void writeGameSnapshot() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        ServerLevel level = this.serverPlayer.level();
        List<Packet<? super ClientGamePacketListener>> gamePackets = new ArrayList<>();

        gamePackets.add(buildLoginPacket(server, level));
        this.asyncReplaySaver.writeGamePackets(this.gamePacketCodec, gamePackets);
        gamePackets.clear();

        writeCreateLocalPlayer();

        gamePackets.add(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(server.getPlayerList().getPlayers()));

        WorldBorder border = level.getWorldBorder();
        gamePackets.add(new ClientboundInitializeBorderPacket(border));
        gamePackets.add(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(),
                level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
        gamePackets.add(new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()));
        gamePackets.add(new ClientboundGameEventPacket(
                level.isRaining() ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0.0f));
        gamePackets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1.0f)));
        gamePackets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1.0f)));

        int viewDistance = Math.max(2, this.serverPlayer.requestedViewDistance());
        ChunkPos centerPos = this.serverPlayer.chunkPosition();
        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        Set<Long> seen = new HashSet<>();
        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                int cx = centerPos.x + dx;
                int cz = centerPos.z + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                if (!seen.add(ChunkPos.asLong(cx, cz))) continue;
                gamePackets.add(new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null));
            }
        }

        net.minecraft.server.level.ServerEntity.Synchronizer noopSync = new net.minecraft.server.level.ServerEntity.Synchronizer() {
            @Override public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> p) {}
            @Override public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> p) {}
            @Override public void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> p, java.util.function.Predicate<net.minecraft.server.level.ServerPlayer> filter) {}
        };
        for (Entity entity : level.getEntities().getAll()) {
            if (entity == this.serverPlayer) continue;
            if (shouldIgnoreEntity(entity)) continue;
            // ModelEngine fully suppresses packets for entities whose base mob is hidden,
            // so we drop them from the snapshot too — otherwise the recording shows the
            // raw cow/zombie underneath the model.
            if (ModelEngineCompat.shouldHideBase(entity)) continue;
            // Display entities (Item/Block/Text) carry custom models and use a per-instance
            // view range that the static EntityType.clientTrackingRange doesn't reflect.
            // Always include them so ModelEngine model parts aren't culled out of snapshots.
            if (!(entity instanceof net.minecraft.world.entity.Display)) {
                int dx = entity.chunkPosition().x - centerPos.x;
                int dz = entity.chunkPosition().z - centerPos.z;
                int range = Math.max(viewDistance, entity.getType().clientTrackingRange());
                if (Math.abs(dx) > range || Math.abs(dz) > range) continue;
            }
            try {
                net.minecraft.server.level.ServerEntity se = new net.minecraft.server.level.ServerEntity(
                        level, entity, 0, false, noopSync, java.util.Set.of());
                se.sendPairingData(this.serverPlayer, gamePackets::add);
            } catch (Throwable t) {
                Flashback3000.getInstance().getLogger().fine("Skipping entity " + entity.getId() + " in snapshot: " + t);
            }
        }

        this.asyncReplaySaver.writeGamePackets(this.gamePacketCodec, gamePackets);
    }

    private ClientboundLoginPacket buildLoginPacket(MinecraftServer server, ServerLevel level) {
        Set<ResourceKey<Level>> levels = new HashSet<>();
        for (ServerLevel sl : server.getAllLevels()) levels.add(sl.dimension());

        CommonPlayerSpawnInfo commonSpawn = this.serverPlayer.createCommonSpawnInfo(level);
        boolean showDeathScreen = !level.getGameRules().getBoolean(GameRules.RULE_DO_IMMEDIATE_RESPAWN);
        boolean limitedCrafting = level.getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING);

        return new ClientboundLoginPacket(
                this.serverPlayer.getId(),
                level.getLevelData().isHardcore(),
                levels,
                server.getPlayerList().getMaxPlayers(),
                this.serverPlayer.requestedViewDistance(),
                server.getPlayerList().getSimulationDistance(),
                this.serverPlayer.isReducedDebugInfo(),
                showDeathScreen,
                limitedCrafting,
                commonSpawn,
                false);
    }

    private void writeCreateLocalPlayer() {
        UUID uuid = this.serverPlayer.getUUID();
        double x = this.serverPlayer.getX();
        double y = this.serverPlayer.getY();
        double z = this.serverPlayer.getZ();
        float xRot = this.serverPlayer.getXRot();
        float yRot = this.serverPlayer.getYRot();
        float yHeadRot = this.serverPlayer.getYHeadRot();
        var dm = this.serverPlayer.getDeltaMovement();
        GameProfile profile = this.serverPlayer.getGameProfile();
        int gameModeId = this.serverPlayer.gameMode.getGameModeForPlayer().getId();

        this.asyncReplaySaver.submit(writer -> {
            writer.startAction(ActionCreateLocalPlayer.INSTANCE);
            RegistryFriendlyByteBuf buf = writer.friendlyByteBuf();
            buf.writeUUID(uuid);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeFloat(xRot);
            buf.writeFloat(yRot);
            buf.writeFloat(yHeadRot);
            buf.writeDouble(dm.x);
            buf.writeDouble(dm.y);
            buf.writeDouble(dm.z);
            ByteBufCodecs.GAME_PROFILE.encode(buf, profile);
            buf.writeVarInt(gameModeId);
            writer.finishAction(ActionCreateLocalPlayer.INSTANCE);
        });
    }

    private void writeEntityPositions() {
        ServerLevel level = this.serverPlayer.level();
        List<EntityPos> changed = new ArrayList<>();
        for (Entity entity : level.getEntities().getAll()) {
            // Local player is included so their position tracks during playback. Their entity
            // id matches what the snapshot's create_local_player action assigned.
            if (shouldIgnoreEntity(entity)) continue;
            if (entity != this.serverPlayer && ModelEngineCompat.shouldHideBase(entity)) continue;
            net.minecraft.world.phys.Vec3 pos = entity.trackingPosition();
            EntityPos current = new EntityPos(entity.getId(),
                    pos.x, pos.y, pos.z,
                    entity.getYRot(), entity.getXRot(), entity.getYHeadRot(),
                    entity.onGround());
            EntityPos last = this.lastPositions.get(entity);
            if (current.equals(last)) continue;
            this.lastPositions.put(entity, current);
            changed.add(current);
        }
        if (changed.isEmpty()) return;
        ResourceKey<Level> dim = level.dimension();
        this.asyncReplaySaver.submit(writer -> {
            writer.startAction(ActionMoveEntities.INSTANCE);
            RegistryFriendlyByteBuf buf = writer.friendlyByteBuf();
            buf.writeVarInt(1);
            buf.writeResourceKey(dim);
            buf.writeVarInt(changed.size());
            for (EntityPos ep : changed) {
                buf.writeVarInt(ep.id);
                buf.writeDouble(ep.x);
                buf.writeDouble(ep.y);
                buf.writeDouble(ep.z);
                buf.writeFloat(ep.yaw);
                buf.writeFloat(ep.pitch);
                buf.writeFloat(ep.headYRot);
                buf.writeBoolean(ep.onGround);
            }
            writer.finishAction(ActionMoveEntities.INSTANCE);
        });
    }

    private record EntityPos(int id, double x, double y, double z,
                             float yaw, float pitch, float headYRot, boolean onGround) {}

    private static boolean shouldIgnoreEntity(Entity entity) {
        return entity == null
                || entity.isRemoved()
                || entity instanceof net.minecraft.world.entity.boss.EnderDragonPart
                || entity.getType().clientTrackingRange() <= 0;
    }
}
