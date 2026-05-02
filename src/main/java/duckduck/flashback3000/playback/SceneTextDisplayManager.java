package duckduck.flashback3000.playback;

import duckduck.flashback3000.scene.ParsedScenes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spawns and despawns Bukkit TextDisplay entities for a scene segment, driven
 * by per-tick lifecycle in {@link PlaybackSession}.
 */
final class SceneTextDisplayManager {

    private final Plugin plugin;
    private final World world;
    private final Map<String, TextDisplay> spawned = new HashMap<>();

    SceneTextDisplayManager(Plugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    /** Walk this tick's lifecycle: spawn anything starting now, despawn anything ending now. */
    void onTick(int globalTick, List<ParsedScenes.TextDisplay> blocks) {
        for (ParsedScenes.TextDisplay block : blocks) {
            if (block.startTick() == globalTick && !this.spawned.containsKey(block.id())) {
                TextDisplay entity = spawn(block);
                if (entity != null) this.spawned.put(block.id(), entity);
            }
            if (block.endTick() == globalTick && this.spawned.containsKey(block.id())) {
                TextDisplay entity = this.spawned.remove(block.id());
                if (entity != null) entity.remove();
            }
        }
    }

    /** Despawn all currently active text displays (segment unwind / cancel). */
    void clear() {
        for (TextDisplay td : this.spawned.values()) {
            try { td.remove(); } catch (Throwable ignored) {}
        }
        this.spawned.clear();
    }

    private @Nullable TextDisplay spawn(ParsedScenes.TextDisplay block) {
        try {
            Location loc = new Location(this.world, block.x(), block.y(), block.z(),
                    block.yaw(), block.pitch());
            return this.world.spawn(loc, TextDisplay.class, td -> applyProps(td, block));
        } catch (Throwable t) {
            this.plugin.getLogger().warning("Failed to spawn text display " + block.id() + ": " + t);
            return null;
        }
    }

    private static void applyProps(TextDisplay td, ParsedScenes.TextDisplay b) {
        td.text(toComponent(b.text()));
        td.setLineWidth(b.lineWidth());
        td.setTextOpacity(b.textOpacity());
        td.setBackgroundColor(Color.fromARGB(b.backgroundColor()));
        td.setShadowed(b.shadow());
        td.setSeeThrough(b.seeThrough());
        td.setDefaultBackground(b.defaultBackground());
        td.setAlignment(parseAlignment(b.alignment()));
        td.setBillboard(parseBillboard(b.billboard()));
        td.setViewRange(b.viewRange());
        td.setShadowRadius(b.shadowRadius());
        td.setShadowStrength(b.shadowStrength());
        td.setGlowColorOverride(b.glowColorOverride() == -1 ? null : Color.fromRGB(b.glowColorOverride() & 0xFFFFFF));

        Vector3f translation = vec(b.translation());
        Vector3f scale = vec(b.scale());
        Quaternionf left = quatFromEuler(b.leftRotation());
        Quaternionf right = quatFromEuler(b.rightRotation());
        td.setTransformation(new Transformation(translation, left, scale, right));
    }

    private static Component toComponent(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        return LegacyComponentSerializer.legacySection().deserialize(raw);
    }

    private static Vector3f vec(float[] v) {
        return new Vector3f(v[0], v[1], v[2]);
    }

    private static Quaternionf quatFromEuler(float[] eulerDeg) {
        Quaternionf q = new Quaternionf();
        q.rotateXYZ((float) Math.toRadians(eulerDeg[0]),
                (float) Math.toRadians(eulerDeg[1]),
                (float) Math.toRadians(eulerDeg[2]));
        return q;
    }

    private static TextDisplay.TextAlignment parseAlignment(String s) {
        try { return TextDisplay.TextAlignment.valueOf(s); }
        catch (Exception e) { return TextDisplay.TextAlignment.CENTER; }
    }

    private static Display.Billboard parseBillboard(String s) {
        try { return Display.Billboard.valueOf(s); }
        catch (Exception e) { return Display.Billboard.CENTER; }
    }
}
