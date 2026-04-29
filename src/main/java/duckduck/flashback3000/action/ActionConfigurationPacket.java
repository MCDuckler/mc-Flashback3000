package duckduck.flashback3000.action;

import duckduck.flashback3000.Flashback3000;
import net.minecraft.resources.ResourceLocation;

public final class ActionConfigurationPacket implements Action {
    public static final ActionConfigurationPacket INSTANCE = new ActionConfigurationPacket();
    private static final ResourceLocation NAME = Flashback3000.id("action/configuration_packet");
    private ActionConfigurationPacket() {}
    @Override public ResourceLocation name() { return NAME; }
}
