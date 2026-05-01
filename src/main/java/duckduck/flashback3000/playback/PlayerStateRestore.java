package duckduck.flashback3000.playback;

import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;

public final class PlayerStateRestore {

    private PlayerStateRestore() {}

    /**
     * Bring the client back in sync with the server after a hijacked playback.
     * Re-sends the world view, re-tracks chunks/entities, and resyncs HUD state.
     * Mirrors the bits of {@code ServerPlayer#changeDimension} that handle
     * client world reload, applied to the player's current real dimension.
     */
    public static void restore(ServerPlayer player) {
        ServerLevel level = player.level();
        MinecraftServer server = MinecraftServer.getServer();
        PlayerList playerList = server.getPlayerList();
        LevelData levelData = level.getLevelData();

        // 1. Wipe client-side world; dataToKeep=3 keeps attrs + chat history.
        player.connection.send(new ClientboundRespawnPacket(player.createCommonSpawnInfo(level), (byte) 3));
        player.connection.send(new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
        playerList.sendPlayerPermissionLevel(player);

        // 2. Force chunk + entity re-track by removing and re-adding into the level.
        Vec3 currentPos = player.position();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        level.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION);
        player.unsetRemoved();
        level.addDuringTeleport(player);

        // 2a. Kick the chunk system so visible chunks + entity trackers re-broadcast.
        // addDuringTeleport adds the player back to the ChunkMap, but ChunkMap.move
        // is what actually walks the tracker set and pushes ClientboundLevelChunkWith*
        // / AddEntity packets for everything in render distance.
        level.getChunkSource().chunkMap.move(player);

        // 3. Teleport to current real position so client snaps to server view.
        PositionMoveRotation pmr = new PositionMoveRotation(currentPos, Vec3.ZERO, yaw, pitch);
        player.connection.internalTeleport(pmr, Collections.<Relative>emptySet());
        player.connection.resetPosition();

        // 4. HUD + abilities + level info + per-player full sync.
        player.connection.send(new ClientboundPlayerAbilitiesPacket(player.getAbilities()));
        playerList.sendLevelInfo(player, level);
        playerList.sendAllPlayerInfo(player);
        playerList.sendActivePlayerEffects(player);

        // 5. Inventory + active menu state.
        player.inventoryMenu.broadcastFullState();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastFullState();
        }
        player.onUpdateAbilities();
    }
}
