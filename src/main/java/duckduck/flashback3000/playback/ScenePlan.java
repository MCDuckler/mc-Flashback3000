package duckduck.flashback3000.playback;

import duckduck.flashback3000.api.EndBehavior;
import duckduck.flashback3000.scene.ParsedScenes;

import java.util.List;

public record ScenePlan(String sceneId,
                        int startTick,
                        int endTick,
                        List<ParsedScenes.CameraSample> samples,
                        EndBehavior endBehavior,
                        boolean overrideCamera) {

    public ParsedScenes.CameraSample sampleAt(int globalTick) {
        int idx = globalTick - this.startTick;
        if (idx < 0 || idx >= this.samples.size()) return null;
        return this.samples.get(idx);
    }
}
