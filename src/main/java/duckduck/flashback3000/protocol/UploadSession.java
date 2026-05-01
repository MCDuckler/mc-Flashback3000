package duckduck.flashback3000.protocol;

import java.util.BitSet;
import java.util.UUID;

public final class UploadSession {

    private final UUID replayId;
    private final long totalSize;
    private final int chunkCount;
    private final byte[][] chunks;
    private final BitSet present;
    private long receivedBytes;
    private boolean closed;

    public UploadSession(UUID replayId, long totalSize, int chunkCount) {
        this.replayId = replayId;
        this.totalSize = totalSize;
        this.chunkCount = chunkCount;
        this.chunks = new byte[chunkCount][];
        this.present = new BitSet(chunkCount);
    }

    public UUID replayId() { return this.replayId; }
    public long totalSize() { return this.totalSize; }
    public int chunkCount() { return this.chunkCount; }

    public synchronized boolean acceptChunk(int index, byte[] data) {
        if (this.closed) return false;
        if (index < 0 || index >= this.chunkCount) return false;
        if (this.present.get(index)) return true; // duplicate, idempotent ack
        this.chunks[index] = data;
        this.present.set(index);
        this.receivedBytes += data.length;
        return true;
    }

    public synchronized boolean isComplete() {
        return this.present.cardinality() == this.chunkCount;
    }

    public synchronized long receivedBytes() {
        return this.receivedBytes;
    }

    public synchronized byte[] assemble() {
        if (!isComplete()) throw new IllegalStateException("Upload incomplete");
        if (this.receivedBytes != this.totalSize) {
            throw new IllegalStateException("Size mismatch: got " + this.receivedBytes + " expected " + this.totalSize);
        }
        byte[] out = new byte[(int) this.totalSize];
        int offset = 0;
        for (byte[] chunk : this.chunks) {
            System.arraycopy(chunk, 0, out, offset, chunk.length);
            offset += chunk.length;
        }
        return out;
    }

    public synchronized void close() {
        this.closed = true;
        for (int i = 0; i < this.chunks.length; i++) this.chunks[i] = null;
    }
}
