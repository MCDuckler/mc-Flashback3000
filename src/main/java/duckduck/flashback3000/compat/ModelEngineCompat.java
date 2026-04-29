package duckduck.flashback3000.compat;

import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;

public final class ModelEngineCompat {

    private static final boolean PRESENT;
    private static final Method GET_MODELED;
    private static final Method IS_VISIBLE;

    static {
        boolean present = false;
        Method getModeled = null;
        Method isVisible = null;
        try {
            Class<?> apiClass = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
            getModeled = apiClass.getMethod("getModeledEntity", org.bukkit.entity.Entity.class);
            Class<?> modeledClass = Class.forName("com.ticxo.modelengine.api.model.ModeledEntity");
            isVisible = modeledClass.getMethod("isBaseEntityVisible");
            present = true;
        } catch (Throwable ignored) {}
        PRESENT = present;
        GET_MODELED = getModeled;
        IS_VISIBLE = isVisible;
    }

    private ModelEngineCompat() {}

    public static boolean isPresent() {
        return PRESENT;
    }

    /**
     * True if ModelEngine is loaded, the entity has an associated ModeledEntity,
     * and ModelEngine considers the base mob hidden from clients. Such entities
     * are entirely suppressed from per-player packet streams by ModelEngine, so
     * we mirror that and skip them in the recording snapshot.
     */
    public static boolean shouldHideBase(Entity nmsEntity) {
        if (!PRESENT) return false;
        try {
            org.bukkit.entity.Entity bukkit = nmsEntity.getBukkitEntity();
            Object modeled = GET_MODELED.invoke(null, bukkit);
            if (modeled == null) return false;
            return !(Boolean) IS_VISIBLE.invoke(modeled);
        } catch (Throwable t) {
            return false;
        }
    }
}
