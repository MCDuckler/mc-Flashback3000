package duckduck.flashback3000.api;

public enum EndBehavior {
    /** Disconnect viewer with a kick message; client must reconnect. */
    KICK,
    /** Disable playback filter, respawn viewer to their real world position. */
    RESTORE
}
