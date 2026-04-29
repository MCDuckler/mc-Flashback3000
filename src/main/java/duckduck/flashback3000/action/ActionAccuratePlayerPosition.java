package duckduck.flashback3000.action;

import duckduck.flashback3000.Flashback3000;
import net.minecraft.resources.ResourceLocation;

public final class ActionAccuratePlayerPosition implements Action {
    public static final ActionAccuratePlayerPosition INSTANCE = new ActionAccuratePlayerPosition();
    private static final ResourceLocation NAME = Flashback3000.id("action/accurate_player_position_optional");
    private ActionAccuratePlayerPosition() {}
    @Override public ResourceLocation name() { return NAME; }
}
