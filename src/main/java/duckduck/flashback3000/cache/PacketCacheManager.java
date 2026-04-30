package duckduck.flashback3000.cache;

import duckduck.flashback3000.Flashback3000;
import duckduck.flashback3000.netty.PacketCaptureHandler;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one {@link PerPlayerContext} per online player, attaches the always-on
 * {@link PacketCaptureHandler} when a player joins, and cleans up when they leave.
 *
 * <p>Why join (not record-start)? An entity ever told to a client persists in the client's
 * world until removed. Flashback's stock client recorder iterates that local entity list to
 * snapshot. We don't have one server-side for ME's fake entities, so we accumulate the same
 * state by observing every outbound packet from the moment the player enters Play protocol.
 * If we attached only on {@code /flashback record}, we'd miss the AddEntity for every entity
 * in view at recording start (ME emits each model's spawn bundle once, on tracker pairing).
 */
public final class PacketCacheManager implements Listener {

    private final Flashback3000 plugin;
    private final ConcurrentHashMap<UUID, PerPlayerContext> contexts = new ConcurrentHashMap<>();

    public PacketCacheManager(Flashback3000 plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public @Nullable PerPlayerContext get(UUID playerId) {
        return contexts.get(playerId);
    }

    /**
     * LOWEST priority so this listener runs BEFORE other plugins' join handlers — critical for
     * CTA's RidingCosmeticPacketAdapter, which emits ProtocolLib packets during its own onJoin
     * to spawn the player's cosmetic-mount chain (SetPassengers chain anchored to the player).
     * If F3K attached later in the dispatch order, those packets would fire before our channel
     * handler is in the pipeline and the chain would be missing from every snapshot taken
     * during this play session.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        attach(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        contexts.remove(event.getPlayer().getUniqueId());
        // Channel handler is removed automatically when the channel closes; nothing to do here.
    }

    /**
     * Reattach contexts and handlers for any players already online at plugin enable. Without
     * this, players who joined before the plugin loaded miss cache initialization until reconnect.
     */
    public void attachAlreadyOnline() {
        for (Player p : plugin.getServer().getOnlinePlayers()) attach(p);
    }

    private void attach(Player player) {
        PerPlayerContext ctx = contexts.computeIfAbsent(player.getUniqueId(), k -> new PerPlayerContext());

        Channel channel;
        try {
            channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        } catch (Throwable t) {
            plugin.getLogger().warning("F3K: failed to access channel for " + player.getName() + ": " + t);
            return;
        }
        if (channel.pipeline().get(PacketCaptureHandler.NAME) != null) return; // already attached

        PacketCaptureHandler handler = new PacketCaptureHandler(ctx);

        // Block until the handler is in the pipeline. Paper's PlayerList.placeNewPlayer holds
        // suppressTrackerForLogin=true through PlayerJoinEvent and then immediately calls
        // chunkMap.addEntity(player), which kicks off tracking + AddEntity emission. If we
        // queued the addBefore async, the tracker could fire before our handler attaches and
        // we'd miss the very entity spawns we built this whole architecture to capture.
        try {
            if (channel.eventLoop().inEventLoop()) {
                if (channel.pipeline().get(PacketCaptureHandler.NAME) == null) {
                    channel.pipeline().addBefore("unbundler", PacketCaptureHandler.NAME, handler);
                }
            } else {
                channel.eventLoop().submit(() -> {
                    if (channel.pipeline().get(PacketCaptureHandler.NAME) == null) {
                        channel.pipeline().addBefore("unbundler", PacketCaptureHandler.NAME, handler);
                    }
                    return null;
                }).get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("F3K: pipeline attach failed for " + player.getName() + ": " + t);
        }
    }
}
