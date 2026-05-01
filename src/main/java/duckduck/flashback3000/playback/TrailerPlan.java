package duckduck.flashback3000.playback;

import duckduck.flashback3000.api.EndBehavior;
import duckduck.flashback3000.scene.ParsedScenes;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Sequence of scene segments to play back-to-back. Each segment can come from a
 * different recording. The PlaybackSession walks segments in order, doing a
 * full wipe-Respawn + snapshot + camera-anchor reinstall between them so
 * entity-id collisions across recordings cannot corrupt client state.
 *
 * Single-scene playback is represented as a one-segment TrailerPlan.
 */
public record TrailerPlan(List<TrailerSegment> segments,
                          EndBehavior endBehavior,
                          boolean overrideCamera) {

    public TrailerSegment first() {
        return this.segments.get(0);
    }

    public boolean isLast(int segmentIndex) {
        return segmentIndex >= this.segments.size() - 1;
    }

    public record TrailerSegment(Path replayPath,
                                 UUID replayId,
                                 String sceneId,
                                 int startTick,
                                 int endTick,
                                 List<ParsedScenes.CameraSample> samples) {

        public ParsedScenes.CameraSample sampleAt(int globalTick) {
            int idx = globalTick - this.startTick;
            if (idx < 0 || idx >= this.samples.size()) return null;
            return this.samples.get(idx);
        }
    }
}
