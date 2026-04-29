package duckduck.flashback3000.record;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomReportDetailsPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.network.protocol.game.ClientboundDebugSamplePacket;
import net.minecraft.network.protocol.game.ClientboundDeleteChatPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEndPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEnterPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookSettingsPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSelectAdvancementsTabPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStepPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;

import java.util.Set;

public final class IgnoredPackets {

    private IgnoredPackets() {}

    public static boolean isIgnored(Packet<?> packet) {
        return IGNORED.contains(packet.getClass());
    }

    private static final Set<Class<?>> IGNORED = Set.of(
            // Entity movement — replaced by ActionMoveEntities, Flashback rejects raw forms
            ClientboundMoveEntityPacket.Pos.class,
            ClientboundMoveEntityPacket.Rot.class,
            ClientboundMoveEntityPacket.PosRot.class,
            ClientboundRotateHeadPacket.class,
            ClientboundPlayerPositionPacket.class,
            ClientboundMoveMinecartPacket.class,

            // Sound / level events — Flashback expects these via dedicated capture
            ClientboundLevelEventPacket.class,
            ClientboundSoundPacket.class,
            ClientboundSoundEntityPacket.class,

            // Common housekeeping
            ClientboundStoreCookiePacket.class,
            ClientboundCustomReportDetailsPacket.class,
            ClientboundServerLinksPacket.class,
            ClientboundCookieRequestPacket.class,
            ClientboundDisconnectPacket.class,
            ClientboundPingPacket.class,
            ClientboundKeepAlivePacket.class,
            ClientboundTransferPacket.class,

            // Configuration phase tail
            ClientboundFinishConfigurationPacket.class,

            // Chat lifecycle (Flashback converts player chat to system chat itself)
            ClientboundPlayerChatPacket.class,
            ClientboundDeleteChatPacket.class,

            // Per-player UI / state (snapshot regenerates equivalents)
            ClientboundAwardStatsPacket.class,
            ClientboundRecipeBookAddPacket.class,
            ClientboundRecipeBookRemovePacket.class,
            ClientboundRecipeBookSettingsPacket.class,
            ClientboundOpenSignEditorPacket.class,
            ClientboundContainerClosePacket.class,
            ClientboundContainerSetContentPacket.class,
            ClientboundContainerSetDataPacket.class,
            ClientboundContainerSetSlotPacket.class,
            ClientboundForgetLevelChunkPacket.class,
            ClientboundPlayerAbilitiesPacket.class,
            ClientboundSetCursorItemPacket.class,
            ClientboundSetExperiencePacket.class,
            ClientboundSetHealthPacket.class,
            ClientboundSetPlayerInventoryPacket.class,
            ClientboundTickingStatePacket.class,
            ClientboundTickingStepPacket.class,
            ClientboundPlayerCombatEndPacket.class,
            ClientboundPlayerCombatEnterPacket.class,
            ClientboundPlayerCombatKillPacket.class,
            ClientboundSetCameraPacket.class,
            ClientboundCooldownPacket.class,
            ClientboundUpdateAdvancementsPacket.class,
            ClientboundSelectAdvancementsTabPacket.class,
            ClientboundPlaceGhostRecipePacket.class,
            ClientboundCommandsPacket.class,
            ClientboundCommandSuggestionsPacket.class,
            ClientboundUpdateRecipesPacket.class,
            ClientboundTagQueryPacket.class,
            ClientboundOpenBookPacket.class,
            ClientboundOpenScreenPacket.class,
            ClientboundMerchantOffersPacket.class,
            ClientboundSetChunkCacheRadiusPacket.class,
            ClientboundSetSimulationDistancePacket.class,
            ClientboundSetChunkCacheCenterPacket.class,
            ClientboundBlockChangedAckPacket.class,
            ClientboundCustomChatCompletionsPacket.class,
            ClientboundStartConfigurationPacket.class,
            ClientboundChunkBatchStartPacket.class,
            ClientboundChunkBatchFinishedPacket.class,
            ClientboundDebugSamplePacket.class,
            ClientboundPongResponsePacket.class
    );
}
