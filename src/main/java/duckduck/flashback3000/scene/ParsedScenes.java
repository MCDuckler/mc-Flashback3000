package duckduck.flashback3000.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ParsedScenes {

    public static final int SUPPORTED_VERSION = 1;

    private final int version;
    private final UUID replayUuid;
    private final List<Scene> scenes;

    private ParsedScenes(int version, UUID replayUuid, List<Scene> scenes) {
        this.version = version;
        this.replayUuid = replayUuid;
        this.scenes = scenes;
    }

    public int version() { return this.version; }
    public UUID replayUuid() { return this.replayUuid; }
    public List<Scene> scenes() { return Collections.unmodifiableList(this.scenes); }

    public Optional<Scene> findById(String sceneId) {
        for (Scene s : this.scenes) {
            if (s.id().equals(sceneId)) return Optional.of(s);
        }
        return Optional.empty();
    }

    public static ParsedScenes parse(byte[] json) {
        return parse(new String(json, StandardCharsets.UTF_8));
    }

    public static ParsedScenes parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int version = root.has("version") ? root.get("version").getAsInt() : 0;
        if (version != SUPPORTED_VERSION) {
            throw new IllegalArgumentException("Unsupported scenes version: " + version);
        }
        UUID replayUuid = UUID.fromString(root.get("replayUuid").getAsString());
        List<Scene> scenes = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("scenes");
        if (arr != null) {
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String id = obj.get("id").getAsString();
                String name = obj.has("name") ? obj.get("name").getAsString() : id;
                int startTick = obj.get("startTick").getAsInt();
                int endTick = obj.get("endTick").getAsInt();
                List<CameraSample> samples = new ArrayList<>();
                JsonArray sa = obj.getAsJsonArray("samples");
                if (sa != null) {
                    for (JsonElement se : sa) {
                        JsonObject so = se.getAsJsonObject();
                        samples.add(new CameraSample(
                                so.get("t").getAsInt(),
                                so.get("x").getAsDouble(),
                                so.get("y").getAsDouble(),
                                so.get("z").getAsDouble(),
                                so.get("yaw").getAsFloat(),
                                so.get("pitch").getAsFloat()));
                    }
                }
                scenes.add(new Scene(id, name, startTick, endTick, samples));
            }
        }
        return new ParsedScenes(version, replayUuid, scenes);
    }

    public record Scene(String id, String name, int startTick, int endTick, List<CameraSample> samples) {
        public int expectedSampleCount() {
            return Math.max(0, this.endTick - this.startTick + 1);
        }
        public boolean isWellFormed() {
            return this.endTick >= this.startTick && this.samples.size() == expectedSampleCount();
        }
        public @Nullable CameraSample sampleAt(int globalTick) {
            int idx = globalTick - this.startTick;
            if (idx < 0 || idx >= this.samples.size()) return null;
            return this.samples.get(idx);
        }
    }

    public record CameraSample(int t, double x, double y, double z, float yaw, float pitch) {}

    public record Summary(String id, String name, int startTick, int endTick, int sampleCount) {}

    public List<Summary> summaries() {
        List<Summary> out = new ArrayList<>(this.scenes.size());
        for (Scene s : this.scenes) {
            out.add(new Summary(s.id(), s.name(), s.startTick(), s.endTick(), s.samples().size()));
        }
        return out;
    }
}
