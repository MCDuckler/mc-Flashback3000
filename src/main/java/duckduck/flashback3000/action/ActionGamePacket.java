package duckduck.flashback3000.action;

import duckduck.flashback3000.Flashback3000;
import net.minecraft.resources.ResourceLocation;

public final class ActionGamePacket implements Action {
    public static final ActionGamePacket INSTANCE = new ActionGamePacket();
    private static final ResourceLocation NAME = Flashback3000.id("action/game_packet");
    private ActionGamePacket() {}
    @Override public ResourceLocation name() { return NAME; }
}
