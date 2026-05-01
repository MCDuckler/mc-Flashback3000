package duckduck.flashback3000.protocol;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.UUID;

/**
 * Chunked-upload assembler for whole recording .zip files. Writes chunks to a
 * temp file directly so we don't hold the full payload in RAM (recordings can
 * be hundreds of MiB).
 */
public final class ReplayUploadSession {

    private final UUID receiptId;
    private final long totalSize;
    private final int chunkCount;
    private final String suggestedName;
    private final Path tmpFile;
    private final RandomAccessFile raf;
    private final BitSet present;
    private long receivedBytes;
    private boolean closed;

    public ReplayUploadSession(UUID receiptId, long totalSize, int chunkCount,
                               String suggestedName, Path tmpDir) throws IOException {
        this.receiptId = receiptId;
        this.totalSize = totalSize;
        this.chunkCount = chunkCount;
        this.suggestedName = suggestedName;
        Files.createDirectories(tmpDir);
        this.tmpFile = tmpDir.resolve("upload-" + receiptId + ".tmp");
        Files.deleteIfExists(this.tmpFile);
        this.raf = new RandomAccessFile(this.tmpFile.toFile(), "rw");
        this.raf.setLength(totalSize);
        this.present = new BitSet(chunkCount);
    }

    public UUID receiptId() { return this.receiptId; }
    public long totalSize() { return this.totalSize; }
    public int chunkCount() { return this.chunkCount; }
    public String suggestedName() { return this.suggestedName; }
    public Path tmpFile() { return this.tmpFile; }
    public synchronized long receivedBytes() { return this.receivedBytes; }
    public synchronized boolean isComplete() { return this.present.cardinality() == this.chunkCount; }

    public synchronized boolean acceptChunk(int index, byte[] data, int chunkSize) throws IOException {
        if (this.closed) return false;
        if (index < 0 || index >= this.chunkCount) return false;
        if (this.present.get(index)) return true; // duplicate — already-acked, ignore
        long offset = (long) index * chunkSize;
        if (offset + data.length > this.totalSize) return false;
        this.raf.seek(offset);
        this.raf.write(data);
        this.present.set(index);
        this.receivedBytes += data.length;
        return true;
    }

    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        try { this.raf.close(); } catch (IOException ignored) {}
        try { Files.deleteIfExists(this.tmpFile); } catch (IOException ignored) {}
    }

    /** Close the file handle but keep the tmp file on disk so caller can move/inspect it. */
    public synchronized void closeKeepingFile() {
        if (this.closed) return;
        this.closed = true;
        try { this.raf.close(); } catch (IOException ignored) {}
    }
}
