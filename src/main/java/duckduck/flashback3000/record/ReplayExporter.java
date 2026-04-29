package duckduck.flashback3000.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ReplayExporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ReplayExporter() {}

    public static void export(Path recordFolder, FlashbackMeta meta, Path outputZip, @Nullable String name) throws IOException {
        if (name != null) meta.name = name;

        meta.chunks.keySet().removeIf(chunkName -> !Files.exists(recordFolder.resolve(chunkName)));
        if (meta.chunks.isEmpty()) {
            throw new IOException("No chunk files exist for recording in " + recordFolder);
        }

        Files.createDirectories(outputZip.getParent());

        try (FileOutputStream fos = new FileOutputStream(outputZip.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.setLevel(Deflater.BEST_SPEED);

            putEntry(zip, "metadata.json", GSON.toJson(meta.toJson()).getBytes(StandardCharsets.UTF_8));

            Path caches = recordFolder.resolve("level_chunk_caches");
            if (Files.isDirectory(caches)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(caches)) {
                    for (Path p : ds) {
                        zip.putNextEntry(new ZipEntry("level_chunk_caches/" + p.getFileName().toString()));
                        Files.copy(p, zip);
                        zip.closeEntry();
                    }
                }
            }

            Path icon = recordFolder.resolve("icon.png");
            if (Files.exists(icon)) {
                zip.putNextEntry(new ZipEntry("icon.png"));
                Files.copy(icon, zip);
                zip.closeEntry();
            }

            for (String chunkName : meta.chunks.keySet()) {
                zip.putNextEntry(new ZipEntry(chunkName));
                Files.copy(recordFolder.resolve(chunkName), zip);
                zip.closeEntry();
            }
        }
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
