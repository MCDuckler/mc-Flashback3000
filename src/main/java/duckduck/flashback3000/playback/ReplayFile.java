package duckduck.flashback3000.playback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import duckduck.flashback3000.Flashback3000;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ReplayFile implements AutoCloseable {

    private final ZipFile zip;
    private final JsonObject metadata;
    private final List<String> chunkOrder = new ArrayList<>();
    private final List<byte[]> cachedChunkPackets = new ArrayList<>();

    public ReplayFile(java.nio.file.Path path) throws IOException {
        this.zip = new ZipFile(path.toFile());

        ZipEntry metaEntry = this.zip.getEntry("metadata.json");
        if (metaEntry == null) throw new IOException("metadata.json missing");
        try (InputStream is = this.zip.getInputStream(metaEntry)) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            this.metadata = JsonParser.parseString(json).getAsJsonObject();
        }
        if (!this.metadata.has("chunks")) throw new IOException("metadata.json has no chunks");
        for (String key : this.metadata.getAsJsonObject("chunks").keySet()) {
            this.chunkOrder.add(key);
        }

        loadChunkCaches();
    }

    public JsonObject metadata() {
        return this.metadata;
    }

    public List<String> chunkOrder() {
        return this.chunkOrder;
    }

    public byte[] cachedChunk(int index) {
        if (index < 0 || index >= this.cachedChunkPackets.size()) {
            throw new IndexOutOfBoundsException("cached chunk " + index + " of " + this.cachedChunkPackets.size());
        }
        return this.cachedChunkPackets.get(index);
    }

    public ParsedChunk readChunk(String name) throws IOException {
        ZipEntry entry = this.zip.getEntry(name);
        if (entry == null) throw new IOException("chunk file missing: " + name);
        byte[] all;
        try (InputStream is = this.zip.getInputStream(entry)) {
            all = is.readAllBytes();
        }

        ByteBuf buf = Unpooled.wrappedBuffer(all);
        FriendlyByteBuf friendly = new FriendlyByteBuf(buf);

        int magic = friendly.readInt();
        if (magic != Flashback3000.MAGIC) throw new IOException("bad magic in " + name);

        int actionCount = friendly.readVarInt();
        List<ResourceLocation> actionTable = new ArrayList<>(actionCount);
        for (int i = 0; i < actionCount; i++) {
            actionTable.add(friendly.readResourceLocation());
        }

        // Snapshot block: int size | size bytes (nested action stream)
        int snapshotSize = friendly.readInt();
        List<RawAction> snapshotActions = new ArrayList<>();
        if (snapshotSize > 0) {
            int end = friendly.readerIndex() + snapshotSize;
            readActions(friendly, actionTable, end, snapshotActions);
        }

        // Main stream — split into ticks at action/next_tick
        ResourceLocation nextTickId = ResourceLocation.fromNamespaceAndPath("flashback", "action/next_tick");
        List<List<RawAction>> ticks = new ArrayList<>();
        List<RawAction> current = new ArrayList<>();
        while (friendly.readableBytes() > 0) {
            int id = friendly.readVarInt();
            int size = friendly.readInt();
            byte[] payload = new byte[size];
            friendly.readBytes(payload);
            ResourceLocation actionType = actionTable.get(id);
            if (actionType.equals(nextTickId)) {
                ticks.add(current);
                current = new ArrayList<>();
            } else {
                current.add(new RawAction(actionType, payload));
            }
        }
        if (!current.isEmpty()) ticks.add(current);

        return new ParsedChunk(actionTable, snapshotActions, ticks);
    }

    private static void readActions(FriendlyByteBuf buf, List<ResourceLocation> table, int end, List<RawAction> out) {
        while (buf.readerIndex() < end) {
            int id = buf.readVarInt();
            int size = buf.readInt();
            byte[] payload = new byte[size];
            buf.readBytes(payload);
            out.add(new RawAction(table.get(id), payload));
        }
    }

    private void loadChunkCaches() throws IOException {
        // Files are named level_chunk_caches/N. Each file is a sequence of (int size, size bytes).
        // Files are read in numeric order; entries within a file are sequential.
        Map<Integer, byte[]> files = new HashMap<>();
        Enumeration<? extends ZipEntry> entries = this.zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String name = e.getName();
            if (!name.startsWith("level_chunk_caches/")) continue;
            String idx = name.substring("level_chunk_caches/".length());
            try {
                int n = Integer.parseInt(idx);
                try (InputStream is = this.zip.getInputStream(e)) {
                    files.put(n, is.readAllBytes());
                }
            } catch (NumberFormatException ignored) {}
        }
        Map<Integer, byte[]> sorted = new java.util.TreeMap<>(files);
        for (byte[] data : sorted.values()) {
            ByteBuf buf = Unpooled.wrappedBuffer(data);
            while (buf.readableBytes() > 0) {
                int size = buf.readInt();
                byte[] packet = new byte[size];
                buf.readBytes(packet);
                this.cachedChunkPackets.add(packet);
            }
        }
    }

    @Override
    public void close() throws IOException {
        this.zip.close();
    }

    public record ParsedChunk(List<ResourceLocation> actionTable, List<RawAction> snapshot, List<List<RawAction>> ticks) {}
}
