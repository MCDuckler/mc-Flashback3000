package duckduck.flashback3000.cache;

import duckduck.flashback3000.record.Recorder;
import org.jetbrains.annotations.Nullable;

/**
 * Per-player runtime state owned by {@link PacketCacheManager}. Holds the entity-state cache
 * (always-on observer of the outbound packet stream) and a slot for the player's currently
 * active recorder, if any. The single channel handler reads from this container to decide
 * whether to forward live packets to a recorder.
 */
public final class PerPlayerContext {
    public final EntityStateCache cache = new EntityStateCache();
    public volatile @Nullable Recorder activeRecorder;
}
