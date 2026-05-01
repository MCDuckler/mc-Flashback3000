package duckduck.flashback3000.record;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public class FlashbackMeta {

    private static final Gson MARKER_GSON = new Gson();

    public UUID replayIdentifier = UUID.randomUUID();
    public String name = "Unnamed";
    public String versionString = null;
    public String worldName = null;
    public String bobbyWorldName = null;
    public String voxyStoragePath = null;
    public int dataVersion = 0;
    public int protocolVersion = 0;

    public TreeMap<Integer, ReplayMarker> replayMarkers = new TreeMap<>();

    public int totalTicks = -1;
    public LinkedHashMap<String, FlashbackChunkMeta> chunks = new LinkedHashMap<>();

    public LinkedHashMap<String, LinkedHashSet<String>> namespacesForRegistries = null;

    public Map<String, File> distantHorizonPaths = new HashMap<>();

    public JsonObject toJson() {
        JsonObject meta = new JsonObject();
        meta.addProperty("uuid", this.replayIdentifier.toString());
        meta.addProperty("name", this.name);

        if (this.versionString != null) meta.addProperty("version_string", this.versionString);
        if (this.worldName != null) meta.addProperty("world_name", this.worldName);
        if (this.dataVersion != 0) meta.addProperty("data_version", this.dataVersion);
        if (this.protocolVersion != 0) meta.addProperty("protocol_version", this.protocolVersion);
        if (this.bobbyWorldName != null) meta.addProperty("bobby_world_name", this.bobbyWorldName);
        if (this.voxyStoragePath != null) meta.addProperty("voxy_storage_path", this.voxyStoragePath);
        if (this.totalTicks > 0) meta.addProperty("total_ticks", this.totalTicks);

        if (!this.replayMarkers.isEmpty()) {
            JsonObject jsonMarkers = new JsonObject();
            for (Map.Entry<Integer, ReplayMarker> entry : this.replayMarkers.entrySet()) {
                jsonMarkers.add("" + entry.getKey(), MARKER_GSON.toJsonTree(entry.getValue()));
            }
            meta.add("markers", jsonMarkers);
        }

        if (!this.distantHorizonPaths.isEmpty()) {
            JsonObject dh = new JsonObject();
            for (Map.Entry<String, File> entry : this.distantHorizonPaths.entrySet()) {
                dh.addProperty(entry.getKey(), entry.getValue().getPath());
            }
            meta.add("distantHorizonPaths", dh);
        }

        if (this.namespacesForRegistries != null) {
            JsonObject reg = new JsonObject();
            for (Map.Entry<String, LinkedHashSet<String>> entry : this.namespacesForRegistries.entrySet()) {
                JsonArray arr = new JsonArray();
                for (String ns : entry.getValue()) arr.add(ns);
                reg.add(entry.getKey(), arr);
            }
            meta.add("customNamespacesForRegistries", reg);
        }

        JsonObject chunksJson = new JsonObject();
        for (Map.Entry<String, FlashbackChunkMeta> entry : this.chunks.entrySet()) {
            chunksJson.add(entry.getKey(), entry.getValue().toJson());
        }
        meta.add("chunks", chunksJson);

        return meta;
    }
}
