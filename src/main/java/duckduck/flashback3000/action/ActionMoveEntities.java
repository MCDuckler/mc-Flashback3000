package duckduck.flashback3000.action;

import duckduck.flashback3000.Flashback3000;
import net.minecraft.resources.ResourceLocation;

public final class ActionMoveEntities implements Action {
    public static final ActionMoveEntities INSTANCE = new ActionMoveEntities();
    private static final ResourceLocation NAME = Flashback3000.id("action/move_entities");
    private ActionMoveEntities() {}
    @Override public ResourceLocation name() { return NAME; }
}
