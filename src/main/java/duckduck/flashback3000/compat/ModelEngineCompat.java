package duckduck.flashback3000.compat;

import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;

/**
 * Tiny compat layer for ModelEngine. Used only to skip ME-hidden base entities in
 * {@code writeEntityPositions} so we don't emit {@code ActionMoveEntities} for
 * entities the client doesn't know about — those AddEntity packets are dropped
 * upstream by ME's channel handler before reaching our packet observer, so the
 * snapshot's entity-state cache never spawns them on playback either.
 *
 * <p>The previous reflective spawn-bundle synthesis (DisplayParser / MountParser /
 * fire-display reflection chains) was removed once we switched to
 * {@code EntityStateCache}: the cache observes whatever ME / UltraCars / CTA /
 * any-other-plugin actually sends to the client and replays it verbatim, with no
 * plugin-specific knowledge required.
 */
public final class ModelEngineCompat {

    private static final boolean PRESENT;
    private static final Method GET_MODELED_BUKKIT;
    private static final Method IS_BASE_VISIBLE;
    private static final Method IS_RENDER_CANCELED;

    static {
        boolean present = false;
        Method getModeled = null;
        Method isVisible = null;
        Method renderCanceled = null;
        try {
            Class<?> apiClass = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
            getModeled = apiClass.getMethod("getModeledEntity", org.bukkit.entity.Entity.class);
            renderCanceled = apiClass.getMethod("isRenderCanceled", int.class);
            Class<?> modeledClass = Class.forName("com.ticxo.modelengine.api.model.ModeledEntity");
            isVisible = modeledClass.getMethod("isBaseEntityVisible");
            present = true;
        } catch (Throwable ignored) {}
        PRESENT = present;
        GET_MODELED_BUKKIT = getModeled;
        IS_BASE_VISIBLE = isVisible;
        IS_RENDER_CANCELED = renderCanceled;
    }

    private ModelEngineCompat() {}

    public static boolean isPresent() { return PRESENT; }

    /**
     * True if ME is suppressing all clientbound packets for this entity from the
     * recording player's view (either {@code ModelEngineAPI.isRenderCanceled} for
     * the entity's id, or {@code ModeledEntity.isBaseEntityVisible} returns false).
     */
    public static boolean shouldHideBase(Entity nmsEntity) {
        if (!PRESENT) return false;
        try {
            if ((Boolean) IS_RENDER_CANCELED.invoke(null, nmsEntity.getId())) return true;
            Object modeled = GET_MODELED_BUKKIT.invoke(null, nmsEntity.getBukkitEntity());
            if (modeled == null) return false;
            return !(Boolean) IS_BASE_VISIBLE.invoke(modeled);
        } catch (Throwable t) {
            return false;
        }
    }
}
