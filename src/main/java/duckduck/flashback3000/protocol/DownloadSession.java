package duckduck.flashback3000.protocol;

import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadSession implements AutoCloseable {

    private final UUID replayId;
    private final Path file;
    private final long totalSize;
    private final int totalChunks;
    private final InputStream stream;
    private final AtomicInteger nextChunkToSend = new AtomicInteger(0);
    private final AtomicInteger highestAcked = new AtomicInteger(-1);
    private volatile boolean closed = false;

    public DownloadSession(UUID replayId, Path file) throws IOException {
        this.replayId = replayId;
        this.file = file;
        this.totalSize = Files.size(file);
        long chunks = (this.totalSize + PacketIds.DOWNLOAD_CHUNK_SIZE - 1) / PacketIds.DOWNLOAD_CHUNK_SIZE;
        this.totalChunks = (int) Math.max(1L, chunks);
        this.stream = Files.newInputStream(file);
    }

    public UUID replayId() { return this.replayId; }
    public long totalSize() { return this.totalSize; }
    public int totalChunks() { return this.totalChunks; }

    public synchronized byte[] readNextChunk() throws IOException {
        if (this.closed) return null;
        int idx = this.nextChunkToSend.get();
        if (idx >= this.totalChunks) return null;
        byte[] buf = new byte[PacketIds.DOWNLOAD_CHUNK_SIZE];
        int read = this.stream.read(buf);
        if (read <= 0) return null;
        this.nextChunkToSend.incrementAndGet();
        if (read < buf.length) {
            byte[] trimmed = new byte[read];
            System.arraycopy(buf, 0, trimmed, 0, read);
            return trimmed;
        }
        return buf;
    }

    public int currentSendIndex() {
        return this.nextChunkToSend.get() - 1;
    }

    public boolean isSendingComplete() {
        return this.nextChunkToSend.get() >= this.totalChunks;
    }

    public void onAck(int chunkIndex) {
        this.highestAcked.updateAndGet(prev -> Math.max(prev, chunkIndex));
    }

    public int outstandingChunks() {
        return this.nextChunkToSend.get() - this.highestAcked.get() - 1;
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        try { this.stream.close(); } catch (IOException ignored) {}
    }
}
