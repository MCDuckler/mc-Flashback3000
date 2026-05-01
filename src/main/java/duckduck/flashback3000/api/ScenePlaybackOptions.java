package duckduck.flashback3000.api;

public record ScenePlaybackOptions(EndBehavior end, boolean overrideCamera) {

    public static final ScenePlaybackOptions RESTORE_DEFAULT = new ScenePlaybackOptions(EndBehavior.RESTORE, true);
    public static final ScenePlaybackOptions KICK_DEFAULT = new ScenePlaybackOptions(EndBehavior.KICK, true);

    public static ScenePlaybackOptions restore() { return RESTORE_DEFAULT; }
    public static ScenePlaybackOptions kick() { return KICK_DEFAULT; }
}
