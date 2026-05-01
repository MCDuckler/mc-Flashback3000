package duckduck.flashback3000;

import duckduck.flashback3000.action.ActionRegistry;
import duckduck.flashback3000.api.PlaybackApi;
import duckduck.flashback3000.cache.PacketCacheManager;
import duckduck.flashback3000.command.FlashbackCommand;
import duckduck.flashback3000.playback.PlaybackManager;
import duckduck.flashback3000.protocol.ServerProtocol;
import duckduck.flashback3000.scene.SceneStore;
import lombok.Getter;
import net.minecraft.resources.ResourceLocation;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class Flashback3000 extends JavaPlugin implements Listener {

    public static final String NAMESPACE = "flashback";
    public static final int MAGIC = 0xD780E884;
    /** Verbose logging of ME-relevant packets reaching the captureHandler. Disable in prod. */
    public static final boolean DEBUG_TRACE_PACKETS = false;

    @Getter
    private static Flashback3000 instance;

    @Getter
    private RecordingManager recordingManager;

    @Getter
    private ServerProtocol serverProtocol;

    @Getter
    private PlaybackManager playbackManager;

    @Getter
    private PacketCacheManager packetCacheManager;

    @Getter
    private SceneStore sceneStore;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        ActionRegistry.bootstrap();

        // Register the cache manager FIRST so its PlayerJoinEvent listener catches every join.
        // attachAlreadyOnline() handles the corner case of /reload where players are already in.
        this.packetCacheManager = new PacketCacheManager(this);
        this.packetCacheManager.attachAlreadyOnline();

        this.recordingManager = new RecordingManager(this);
        this.sceneStore = new SceneStore(this.recordingManager.outputRoot(), getLogger());
        this.serverProtocol = new ServerProtocol(this, this.sceneStore);
        this.serverProtocol.register();
        this.playbackManager = new PlaybackManager(this, this.sceneStore);
        getServer().getPluginManager().registerEvents(this.playbackManager, this);

        PlaybackApi.register(this, this.playbackManager, this.sceneStore);

        var cmd = getCommand("flashback");
        if (cmd != null) {
            FlashbackCommand executor = new FlashbackCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("Flashback3000 enabled. Replay output dir: " + this.recordingManager.outputRoot());
    }

    @Override
    public void onDisable() {
        PlaybackApi.unregister();
        if (this.playbackManager != null) this.playbackManager.shutdown();
        if (this.serverProtocol != null) this.serverProtocol.shutdown();
        if (this.recordingManager != null) this.recordingManager.shutdown();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.recordingManager.handlePlayerQuit(event.getPlayer());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }
}
