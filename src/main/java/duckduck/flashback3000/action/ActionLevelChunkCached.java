package duckduck.flashback3000.action;

import duckduck.flashback3000.Flashback3000;
import net.minecraft.resources.ResourceLocation;

public final class ActionLevelChunkCached implements Action {
    public static final ActionLevelChunkCached INSTANCE = new ActionLevelChunkCached();
    private static final ResourceLocation NAME = Flashback3000.id("action/level_chunk_cached");
    private ActionLevelChunkCached() {}
    @Override public ResourceLocation name() { return NAME; }
}
