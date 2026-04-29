package duckduck.flashback3000.action;

import duckduck.flashback3000.Flashback3000;
import net.minecraft.resources.ResourceLocation;

public final class ActionNextTick implements Action {
    public static final ActionNextTick INSTANCE = new ActionNextTick();
    private static final ResourceLocation NAME = Flashback3000.id("action/next_tick");
    private ActionNextTick() {}
    @Override public ResourceLocation name() { return NAME; }
}
