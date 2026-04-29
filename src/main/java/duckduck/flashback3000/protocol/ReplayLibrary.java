package duckduck.flashback3000.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ReplayLibrary {

    private final Path root;

    public ReplayLibrary(Path root) {
        this.root = root;
    }

    public Path root() {
        return this.root;
    }

    public List<Entry> list() throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(this.root)) return entries;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(this.root, "*.zip")) {
            for (Path zip : ds) {
                Entry e = read(zip);
                if (e != null) entries.add(e);
            }
        }
        return entries;
    }

    public Entry read(Path zip) {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry meta = zf.getEntry("metadata.json");
            if (meta == null) return null;
            String json;
            try (InputStream is = zf.getInputStream(meta)) {
                json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
            String name = obj.has("name") ? obj.get("name").getAsString() : zip.getFileName().toString();
            int totalTicks = obj.has("total_ticks") ? obj.get("total_ticks").getAsInt() : -1;
            int dataVersion = obj.has("data_version") ? obj.get("data_version").getAsInt() : 0;
            int protocolVersion = obj.has("protocol_version") ? obj.get("protocol_version").getAsInt() : 0;
            long size = Files.size(zip);
            FileTime mtime = Files.getLastModifiedTime(zip);
            return new Entry(uuid, name, zip, size, totalTicks, dataVersion, protocolVersion, mtime.toMillis());
        } catch (Exception e) {
            return null;
        }
    }

    public Entry findById(UUID id) throws IOException {
        for (Entry e : list()) {
            if (e.uuid().equals(id)) return e;
        }
        return null;
    }

    public boolean delete(UUID id) throws IOException {
        Entry e = findById(id);
        if (e == null) return false;
        return Files.deleteIfExists(e.path());
    }

    public Entry rename(UUID id, String newName) throws IOException {
        Entry e = findById(id);
        if (e == null) return null;
        // Update metadata.json inside the zip is heavy; for v1 we rename only the file on disk.
        String safe = newName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) safe = id.toString();
        Path target = this.root.resolve(safe + ".zip");
        int dedupe = 1;
        while (Files.exists(target) && !target.equals(e.path())) {
            target = this.root.resolve(safe + "-" + (dedupe++) + ".zip");
        }
        if (!target.equals(e.path())) {
            Files.move(e.path(), target);
        }
        return read(target);
    }

    public record Entry(UUID uuid, String name, Path path, long sizeBytes,
                        int totalTicks, int dataVersion, int protocolVersion,
                        long modifiedMillis) {}
}
