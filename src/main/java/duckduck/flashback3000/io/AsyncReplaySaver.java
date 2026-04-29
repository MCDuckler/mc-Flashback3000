package duckduck.flashback3000.io;

import duckduck.flashback3000.action.ActionConfigurationPacket;
import duckduck.flashback3000.action.ActionGamePacket;
import duckduck.flashback3000.action.ActionLevelChunkCached;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

public class AsyncReplaySaver {

    public static final int CHUNK_CACHE_SIZE = 10000;

    private final ArrayBlockingQueue<Consumer<ReplayWriter>> tasks = new ArrayBlockingQueue<>(1024);
    private final AtomicReference<Throwable> error = new AtomicReference<>(null);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    private final AtomicBoolean hasStopped = new AtomicBoolean(false);

    private final Path recordFolder;

    public AsyncReplaySaver(RegistryAccess registryAccess, Path recordFolder) {
        this.recordFolder = recordFolder;
        try {
            Files.createDirectories(recordFolder);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ReplayWriter replayWriter = new ReplayWriter(registryAccess);
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Consumer<ReplayWriter> task = this.tasks.poll(10, TimeUnit.MILLISECONDS);
                    if (task == null) {
                        if (this.shouldStop.get()) {
                            this.hasStopped.set(true);
                            return;
                        }
                        continue;
                    }
                    task.accept(replayWriter);
                } catch (Throwable th) {
                    this.error.set(th);
                    this.hasStopped.set(true);
                    return;
                }
            }
        }, "Flashback3000-AsyncReplaySaver");
        t.setDaemon(true);
        t.start();
    }

    public Path recordFolder() {
        return this.recordFolder;
    }

    public void submit(Consumer<ReplayWriter> consumer) {
        checkForError();
        if (this.hasStopped.get()) {
            throw new IllegalStateException("Cannot submit task to stopped AsyncReplaySaver");
        }
        while (true) {
            try {
                this.tasks.put(consumer);
                return;
            } catch (InterruptedException ignored) {}
        }
    }

    private final Long2ObjectOpenHashMap<List<CachedChunkPacket>> cachedChunkPackets = new Long2ObjectOpenHashMap<>();
    private int totalWrittenChunkPackets = 0;

    public void writeGamePackets(StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gamePacketCodec,
                                 List<Packet<? super ClientGamePacketListener>> packets) {
        List<Packet<? super ClientGamePacketListener>> packetCopy = new ArrayList<>(packets);
        this.submit(writer -> {
            RegistryFriendlyByteBuf chunkCacheOutput = null;
            int lastChunkCacheIndex = -1;

            for (Packet<? super ClientGamePacketListener> packet : packetCopy) {
                if (packet instanceof ClientboundLevelChunkWithLightPacket levelChunkPacket) {
                    int index = -1;

                    long key = chunkKey(levelChunkPacket.getX(), levelChunkPacket.getZ());
                    List<CachedChunkPacket> cached = this.cachedChunkPackets.computeIfAbsent(key, l -> new ArrayList<>());
                    boolean add = true;
                    for (CachedChunkPacket existing : cached) {
                        if (existing.x == levelChunkPacket.getX() && existing.z == levelChunkPacket.getZ()) {
                            // Always re-cache; chunk content may have changed. Simpler than equality test.
                        }
                    }
                    // For now always insert a new entry; perfect dedup requires hashing the chunk bytes.
                    if (add) {
                        index = this.totalWrittenChunkPackets++;
                        int cacheIndex = index / CHUNK_CACHE_SIZE;
                        if (lastChunkCacheIndex >= 0 && cacheIndex != lastChunkCacheIndex) {
                            this.writeChunkCacheFile(chunkCacheOutput, lastChunkCacheIndex);
                            chunkCacheOutput = null;
                        }
                        lastChunkCacheIndex = cacheIndex;
                        if (chunkCacheOutput == null) {
                            chunkCacheOutput = new RegistryFriendlyByteBuf(Unpooled.buffer(), writer.registryAccess());
                        }
                        int startIdx = chunkCacheOutput.writerIndex();
                        chunkCacheOutput.writeInt(-1);
                        gamePacketCodec.encode(chunkCacheOutput, packet);
                        int endIdx = chunkCacheOutput.writerIndex();
                        int size = endIdx - startIdx - 4;
                        chunkCacheOutput.writerIndex(startIdx);
                        chunkCacheOutput.writeInt(size);
                        chunkCacheOutput.writerIndex(endIdx);

                        cached.add(new CachedChunkPacket(levelChunkPacket.getX(), levelChunkPacket.getZ(), index));
                    }

                    writer.startAction(ActionLevelChunkCached.INSTANCE);
                    writer.friendlyByteBuf().writeVarInt(index);
                    writer.finishAction(ActionLevelChunkCached.INSTANCE);
                    continue;
                }

                writer.startAction(ActionGamePacket.INSTANCE);
                gamePacketCodec.encode(writer.friendlyByteBuf(), packet);
                writer.finishAction(ActionGamePacket.INSTANCE);
            }

            if (lastChunkCacheIndex >= 0) {
                writeChunkCacheFile(chunkCacheOutput, lastChunkCacheIndex);
            }
        });
    }

    private void writeChunkCacheFile(RegistryFriendlyByteBuf out, int index) {
        if (out == null || out.writerIndex() == 0) return;
        try {
            byte[] bytes = new byte[out.writerIndex()];
            out.getBytes(0, bytes);
            Path path = this.recordFolder.resolve("level_chunk_caches").resolve(Integer.toString(index));
            Files.createDirectories(path.getParent());
            OpenOption[] opts = new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND};
            Files.write(path, bytes, opts);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeConfigurationPackets(StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> codec,
                                          List<Packet<? super ClientConfigurationPacketListener>> packets) {
        List<Packet<? super ClientConfigurationPacketListener>> copy = new ArrayList<>(packets);
        this.submit(writer -> {
            for (Packet<? super ClientConfigurationPacketListener> packet : copy) {
                writer.startAction(ActionConfigurationPacket.INSTANCE);
                codec.encode(writer.friendlyByteBuf(), packet);
                writer.finishAction(ActionConfigurationPacket.INSTANCE);
            }
        });
    }

    public void writeReplayChunk(String chunkName, String metadata) {
        this.submit(writer -> {
            try {
                Path chunkFile = this.recordFolder.resolve(chunkName);
                Files.write(chunkFile, writer.popBytes());

                Path metaFile = this.recordFolder.resolve("metadata.json");
                if (Files.exists(metaFile)) {
                    Files.move(metaFile, this.recordFolder.resolve("metadata.json.old"),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(metaFile, metadata);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void waitForTasks() {
        checkForError();
        if (this.hasStopped.get()) return;
        while (!this.tasks.isEmpty()) {
            checkForError();
            LockSupport.parkNanos(100_000L);
        }
    }

    public Path finish() {
        waitForTasks();
        this.shouldStop.set(true);
        while (!this.hasStopped.get()) {
            checkForError();
            LockSupport.parkNanos(100_000L);
        }
        checkForError();
        return this.recordFolder;
    }

    private void checkForError() {
        Throwable t = error.get();
        if (t != null) {
            throw new RuntimeException(t);
        }
    }

    private static long chunkKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    private record CachedChunkPacket(int x, int z, int index) {}
}
