package duckduck.flashback3000.record;

import com.google.gson.JsonObject;

public class FlashbackChunkMeta {
    public int duration = 0;
    public boolean forcePlaySnapshot = false;

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("duration", this.duration);
        o.addProperty("forcePlaySnapshot", this.forcePlaySnapshot);
        return o;
    }
}
