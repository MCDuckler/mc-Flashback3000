package duckduck.flashback3000.action;

import duckduck.flashback3000.Flashback3000;
import net.minecraft.resources.ResourceLocation;

public final class ActionCreateLocalPlayer implements Action {
    public static final ActionCreateLocalPlayer INSTANCE = new ActionCreateLocalPlayer();
    private static final ResourceLocation NAME = Flashback3000.id("action/create_local_player");
    private ActionCreateLocalPlayer() {}
    @Override public ResourceLocation name() { return NAME; }
}
