package duckduck.flashback3000.compat;

import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;

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

    private static Object getModeled(Entity nmsEntity) {
        if (!PRESENT) return null;
        try {
            return GET_MODELED_BUKKIT.invoke(null, nmsEntity.getBukkitEntity());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Mirrors ModelEngineChannelHandler.shouldShow — returns true if ModelEngine
     * would suppress every clientbound packet for this entity from the recording
     * player's view.
     */
    public static boolean shouldHideBase(Entity nmsEntity) {
        if (!PRESENT) return false;
        try {
            if ((Boolean) IS_RENDER_CANCELED.invoke(null, nmsEntity.getId())) return true;
            Object modeled = getModeled(nmsEntity);
            if (modeled == null) return false;
            return !(Boolean) IS_BASE_VISIBLE.invoke(modeled);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True if the entity is part of a ModelEngine model assembly — either a
     * Display rendering a bone, or a base mob with an associated ModeledEntity.
     * Such entities need to be re-paired through the netty pipeline so
     * ModelEngine's handler injects the corresponding pivot AddEntity and
     * SetPassengers tree; sendPairingData alone bypasses ModelEngine and the
     * model never assembles client-side.
     */
    public static boolean isMERelated(Entity nmsEntity) {
        if (nmsEntity instanceof net.minecraft.world.entity.Display) return true;
        return getModeled(nmsEntity) != null;
    }
}
