package duckduck.flashback3000.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ActionRegistry {

    private static final List<Action> ACTIONS = new ArrayList<>();
    private static final Set<Class<? extends Action>> REGISTERED = new HashSet<>();
    private static boolean bootstrapped = false;

    private ActionRegistry() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        // Order is part of the on-disk format. Do not reorder.
        register(ActionNextTick.INSTANCE);
        register(ActionGamePacket.INSTANCE);
        register(ActionConfigurationPacket.INSTANCE);
        register(ActionCreateLocalPlayer.INSTANCE);
        register(ActionMoveEntities.INSTANCE);
        register(ActionLevelChunkCached.INSTANCE);
        register(ActionAccuratePlayerPosition.INSTANCE);
    }

    private static void register(Action action) {
        if (!REGISTERED.add(action.getClass())) {
            throw new IllegalArgumentException("Action already registered: " + action.getClass());
        }
        ACTIONS.add(action);
    }

    public static List<Action> getActions() {
        return Collections.unmodifiableList(ACTIONS);
    }
}
