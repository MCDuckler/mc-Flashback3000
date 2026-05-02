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
                List<TextDisplay> textDisplays = new ArrayList<>();
                JsonArray td = obj.has("textDisplays") ? obj.getAsJsonArray("textDisplays") : null;
                if (td != null) {
                    for (JsonElement te : td) {
                        textDisplays.add(parseTextDisplay(te.getAsJsonObject()));
                    }
                }
                scenes.add(new Scene(id, name, startTick, endTick, samples, textDisplays));
            }
        }
        return new ParsedScenes(version, replayUuid, scenes);
    }

    private static TextDisplay parseTextDisplay(JsonObject o) {
        return new TextDisplay(
                o.get("id").getAsString(),
                o.get("startTick").getAsInt(),
                o.get("endTick").getAsInt(),
                o.has("text") ? o.get("text").getAsString() : "",
                o.get("x").getAsDouble(),
                o.get("y").getAsDouble(),
                o.get("z").getAsDouble(),
                o.get("yaw").getAsFloat(),
                o.get("pitch").getAsFloat(),
                readFloats(o, "translation", new float[]{0, 0, 0}),
                readFloats(o, "leftRotation", new float[]{0, 0, 0}),
                readFloats(o, "scale", new float[]{1, 1, 1}),
                readFloats(o, "rightRotation", new float[]{0, 0, 0}),
                o.has("billboard") ? o.get("billboard").getAsString() : "CENTER",
                o.has("backgroundColor") ? o.get("backgroundColor").getAsInt() : 0x40000000,
                (byte) (o.has("textOpacity") ? o.get("textOpacity").getAsInt() : -1),
                o.has("lineWidth") ? o.get("lineWidth").getAsInt() : 200,
                o.has("shadow") && o.get("shadow").getAsBoolean(),
                o.has("seeThrough") && o.get("seeThrough").getAsBoolean(),
                o.has("defaultBackground") && o.get("defaultBackground").getAsBoolean(),
                o.has("alignment") ? o.get("alignment").getAsString() : "CENTER",
                o.has("viewRange") ? o.get("viewRange").getAsFloat() : 1.0f,
                o.has("shadowRadius") ? o.get("shadowRadius").getAsFloat() : 0f,
                o.has("shadowStrength") ? o.get("shadowStrength").getAsFloat() : 1.0f,
                o.has("glowColorOverride") ? o.get("glowColorOverride").getAsInt() : -1
        );
    }

    private static float[] readFloats(JsonObject o, String key, float[] fallback) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return fallback;
        JsonArray a = o.getAsJsonArray(key);
        if (a.size() < fallback.length) return fallback;
        float[] out = new float[fallback.length];
        for (int i = 0; i < fallback.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    public record TextDisplay(String id, int startTick, int endTick, String text,
                              double x, double y, double z, float yaw, float pitch,
                              float[] translation, float[] leftRotation, float[] scale, float[] rightRotation,
                              String billboard, int backgroundColor, byte textOpacity, int lineWidth,
                              boolean shadow, boolean seeThrough, boolean defaultBackground, String alignment,
                              float viewRange, float shadowRadius, float shadowStrength, int glowColorOverride) {}

    public record Scene(String id, String name, int startTick, int endTick,
                        List<CameraSample> samples, List<TextDisplay> textDisplays) {
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
