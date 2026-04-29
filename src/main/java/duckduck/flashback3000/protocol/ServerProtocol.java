package duckduck.flashback3000.protocol;

import duckduck.flashback3000.Flashback3000;
import duckduck.flashback3000.RecordingManager;
import duckduck.flashback3000.record.Recorder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ServerProtocol implements PluginMessageListener, Listener {

    private final Flashback3000 plugin;
    private final ReplayLibrary library;
    private final Map<UUID, DownloadSession> downloads = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> pumpTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Flashback3000-DownloadPump");
        t.setDaemon(true);
        return t;
    });

    public ServerProtocol(Flashback3000 plugin) {
        this.plugin = plugin;
        this.library = new ReplayLibrary(plugin.getRecordingManager().outputRoot());
    }

    public ReplayLibrary library() {
        return this.library;
    }

    public void register() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(this.plugin, PacketIds.CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(this.plugin, PacketIds.CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void shutdown() {
        for (DownloadSession ds : this.downloads.values()) ds.close();
        this.downloads.clear();
        this.pumpTasks.values().forEach(f -> f.cancel(false));
        this.pumpTasks.clear();
        this.scheduler.shutdownNow();
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte[] message) {
        if (!channel.equals(PacketIds.CHANNEL)) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            byte op = in.readByte();
            handleInbound(player, op, in);
        } catch (Exception e) {
            this.plugin.getLogger().warning("Bad protocol message from " + player.getName() + ": " + e);
        }
    }

    private void handleInbound(Player player, byte op, DataInputStream in) throws IOException {
        switch (op) {
            case PacketIds.HELLO -> handleHello(player, in.readInt());
            case PacketIds.LIST_REPLAYS -> handleListReplays(player);
            case PacketIds.START_RECORDING -> handleStart(player, in.readUTF());
            case PacketIds.STOP_RECORDING -> handleStop(player);
            case PacketIds.CANCEL_RECORDING -> handleCancel(player);
            case PacketIds.RENAME_REPLAY -> handleRename(player, Wire.readUUID(in), in.readUTF());
            case PacketIds.DELETE_REPLAY -> handleDelete(player, Wire.readUUID(in));
            case PacketIds.DOWNLOAD_REQUEST -> handleDownloadRequest(player, Wire.readUUID(in));
            case PacketIds.DOWNLOAD_ACK -> handleDownloadAck(player, Wire.readUUID(in), in.readInt());
            default -> this.plugin.getLogger().warning("Unknown opcode 0x" + Integer.toHexString(op & 0xFF));
        }
    }

    private boolean checkAdmin(Player player) {
        if (player.hasPermission("flashback3000.admin")) return true;
        send(player, Wire.build(PacketIds.OPERATION_RESULT, out -> {
            try {
                out.writeByte(0);
                out.writeBoolean(false);
                out.writeUTF("Permission denied");
            } catch (IOException ignored) {}
        }));
        return false;
    }

    private void send(Player player, byte[] data) {
        try {
            player.sendPluginMessage(this.plugin, PacketIds.CHANNEL, data);
        } catch (Exception e) {
            this.plugin.getLogger().warning("Failed sending to " + player.getName() + ": " + e);
        }
    }

    // -------- handlers --------

    private void handleHello(Player player, int clientVersion) {
        byte perms = player.hasPermission("flashback3000.admin") ? PacketIds.PERM_ADMIN : 0;
        send(player, Wire.build(PacketIds.WELCOME, out -> {
            try {
                out.writeInt(PacketIds.PROTOCOL_VERSION);
                out.writeByte(perms);
            } catch (IOException ignored) {}
        }));
        sendRecordingStatus(player);
    }

    private void handleListReplays(Player player) {
        if (!checkAdmin(player)) return;
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                List<ReplayLibrary.Entry> entries = this.library.list();
                send(player, Wire.build(PacketIds.REPLAY_LIST, out -> {
                    try {
                        out.writeInt(entries.size());
                        for (ReplayLibrary.Entry e : entries) {
                            Wire.writeUUID(out, e.uuid());
                            out.writeUTF(e.name());
                            out.writeLong(e.sizeBytes());
                            out.writeInt(e.totalTicks());
                            out.writeInt(e.dataVersion());
                            out.writeInt(e.protocolVersion());
                            out.writeLong(e.modifiedMillis());
                        }
                    } catch (IOException ignored) {}
                }));
            } catch (IOException e) {
                operationResult(player, PacketIds.LIST_REPLAYS, false, e.getMessage());
            }
        });
    }

    private void handleStart(Player player, String name) {
        if (!checkAdmin(player)) return;
        RecordingManager mgr = this.plugin.getRecordingManager();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            try {
                mgr.start(player, name.isBlank() ? null : name);
                operationResult(player, PacketIds.START_RECORDING, true, "Recording started");
                sendRecordingStatus(player);
            } catch (Exception e) {
                operationResult(player, PacketIds.START_RECORDING, false, e.getMessage());
            }
        });
    }

    private void handleStop(Player player) {
        if (!checkAdmin(player)) return;
        RecordingManager mgr = this.plugin.getRecordingManager();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            Path out = mgr.stopAndExport(player);
            if (out != null) {
                operationResult(player, PacketIds.STOP_RECORDING, true, "Saved: " + out.getFileName());
            } else {
                operationResult(player, PacketIds.STOP_RECORDING, false, "Not recording");
            }
            sendRecordingStatus(player);
        });
    }

    private void handleCancel(Player player) {
        if (!checkAdmin(player)) return;
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            this.plugin.getRecordingManager().cancel(player);
            operationResult(player, PacketIds.CANCEL_RECORDING, true, "Cancelled");
            sendRecordingStatus(player);
        });
    }

    private void handleRename(Player player, UUID id, String newName) {
        if (!checkAdmin(player)) return;
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                ReplayLibrary.Entry e = this.library.rename(id, newName);
                if (e == null) {
                    operationResult(player, PacketIds.RENAME_REPLAY, false, "Not found");
                } else {
                    operationResult(player, PacketIds.RENAME_REPLAY, true, "Renamed");
                    handleListReplays(player);
                }
            } catch (IOException ex) {
                operationResult(player, PacketIds.RENAME_REPLAY, false, ex.getMessage());
            }
        });
    }

    private void handleDelete(Player player, UUID id) {
        if (!checkAdmin(player)) return;
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                boolean ok = this.library.delete(id);
                operationResult(player, PacketIds.DELETE_REPLAY, ok, ok ? "Deleted" : "Not found");
                if (ok) handleListReplays(player);
            } catch (IOException ex) {
                operationResult(player, PacketIds.DELETE_REPLAY, false, ex.getMessage());
            }
        });
    }

    private void handleDownloadRequest(Player player, UUID replayId) {
        if (!checkAdmin(player)) return;
        // Cancel any prior download by this player.
        DownloadSession existing = this.downloads.remove(player.getUniqueId());
        if (existing != null) existing.close();
        ScheduledFuture<?> oldPump = this.pumpTasks.remove(player.getUniqueId());
        if (oldPump != null) oldPump.cancel(false);

        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                ReplayLibrary.Entry entry = this.library.findById(replayId);
                if (entry == null) {
                    sendDownloadEnd(player, replayId, false, "Not found");
                    return;
                }
                DownloadSession session = new DownloadSession(replayId, entry.path());
                this.downloads.put(player.getUniqueId(), session);
                send(player, Wire.build(PacketIds.DOWNLOAD_START, out -> {
                    try {
                        Wire.writeUUID(out, replayId);
                        out.writeLong(session.totalSize());
                        out.writeInt(session.totalChunks());
                        out.writeInt(PacketIds.DOWNLOAD_CHUNK_SIZE);
                    } catch (IOException ignored) {}
                }));
                schedulePump(player, session);
            } catch (IOException ex) {
                sendDownloadEnd(player, replayId, false, ex.getMessage());
            }
        });
    }

    private void schedulePump(Player player, DownloadSession session) {
        ScheduledFuture<?> task = this.scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!player.isOnline()) {
                    closeDownload(player, session, false, "Player offline");
                    return;
                }
                while (session.outstandingChunks() < PacketIds.DOWNLOAD_WINDOW && !session.isSendingComplete()) {
                    int idx = session.currentSendIndex() + 1;
                    byte[] chunk = session.readNextChunk();
                    if (chunk == null) break;
                    send(player, Wire.build(PacketIds.DOWNLOAD_CHUNK, out -> {
                        try {
                            Wire.writeUUID(out, session.replayId());
                            out.writeInt(idx);
                            Wire.writeBytes(out, chunk);
                        } catch (IOException ignored) {}
                    }));
                }
                if (session.isSendingComplete() && session.outstandingChunks() <= 0) {
                    closeDownload(player, session, true, "Done");
                }
            } catch (IOException ex) {
                closeDownload(player, session, false, ex.getMessage());
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
        this.pumpTasks.put(player.getUniqueId(), task);
    }

    private void closeDownload(Player player, DownloadSession session, boolean ok, String msg) {
        ScheduledFuture<?> task = this.pumpTasks.remove(player.getUniqueId());
        if (task != null) task.cancel(false);
        DownloadSession current = this.downloads.remove(player.getUniqueId());
        if (current != null) current.close();
        sendDownloadEnd(player, session.replayId(), ok, msg);
    }

    private void handleDownloadAck(Player player, UUID replayId, int chunkIndex) {
        DownloadSession session = this.downloads.get(player.getUniqueId());
        if (session != null && session.replayId().equals(replayId)) {
            session.onAck(chunkIndex);
        }
    }

    private void sendDownloadEnd(Player player, UUID replayId, boolean ok, String msg) {
        send(player, Wire.build(PacketIds.DOWNLOAD_END, out -> {
            try {
                Wire.writeUUID(out, replayId);
                out.writeBoolean(ok);
                out.writeUTF(msg == null ? "" : msg);
            } catch (IOException ignored) {}
        }));
    }

    private void sendRecordingStatus(Player player) {
        Recorder recorder = this.plugin.getRecordingManager().activeRecorders().get(player.getUniqueId());
        boolean active = recorder != null;
        String name = active ? recorder.metadata().name : "";
        int ticks = active ? recorder.metadata().totalTicks : 0;
        int chunks = active ? recorder.metadata().chunks.size() : 0;
        send(player, Wire.build(PacketIds.RECORDING_STATUS, out -> {
            try {
                out.writeBoolean(active);
                out.writeUTF(name);
                out.writeInt(Math.max(0, ticks));
                out.writeInt(chunks);
            } catch (IOException ignored) {}
        }));
    }

    private void operationResult(Player player, byte opcode, boolean ok, String msg) {
        send(player, Wire.build(PacketIds.OPERATION_RESULT, out -> {
            try {
                out.writeByte(opcode);
                out.writeBoolean(ok);
                out.writeUTF(msg == null ? "" : msg);
            } catch (IOException ignored) {}
        }));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        DownloadSession ds = this.downloads.remove(event.getPlayer().getUniqueId());
        if (ds != null) ds.close();
        ScheduledFuture<?> task = this.pumpTasks.remove(event.getPlayer().getUniqueId());
        if (task != null) task.cancel(false);
    }
}
