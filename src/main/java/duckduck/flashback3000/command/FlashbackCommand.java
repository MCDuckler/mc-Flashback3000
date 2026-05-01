package duckduck.flashback3000.command;

import duckduck.flashback3000.Flashback3000;
import duckduck.flashback3000.RecordingManager;
import duckduck.flashback3000.api.EndBehavior;
import duckduck.flashback3000.api.ScenePlaybackOptions;
import duckduck.flashback3000.record.Recorder;
import duckduck.flashback3000.scene.ParsedScenes;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class FlashbackCommand implements CommandExecutor, TabCompleter {

    private final Flashback3000 plugin;

    public FlashbackCommand(Flashback3000 plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /flashback <start|stop|cancel|list|files|play|playscene|playtrailer|playstop|scenes> [...]");
            return true;
        }
        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "start" -> handleStart(sender, args);
            case "stop" -> handleStop(sender, args);
            case "cancel" -> handleCancel(sender, args);
            case "list" -> handleList(sender);
            case "files" -> handleFiles(sender);
            case "play" -> handlePlay(sender, args);
            case "playscene" -> handlePlayScene(sender, args);
            case "playtrailer" -> handlePlayTrailer(sender, args);
            case "playstop" -> handlePlayStop(sender, args);
            case "scenes" -> handleScenes(sender, args);
            default -> {
                sender.sendMessage("Unknown subcommand: " + sub);
                yield true;
            }
        };
    }

    private boolean handlePlayScene(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("Usage: /flashback playscene <replay-uuid> <scene-id> [player] [--kick|--restore]");
            return true;
        }
        UUID replayId;
        try {
            replayId = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("First argument must be a replay UUID. Use /flashback scenes list to find one.");
            return true;
        }
        String sceneId = args[2];
        EndBehavior end = EndBehavior.RESTORE;
        Player target = null;
        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if (a.equalsIgnoreCase("--kick")) {
                end = EndBehavior.KICK;
            } else if (a.equalsIgnoreCase("--restore")) {
                end = EndBehavior.RESTORE;
            } else if (target == null) {
                target = Bukkit.getPlayerExact(a);
                if (target == null) {
                    sender.sendMessage("Unknown player: " + a);
                    return true;
                }
            }
        }
        if (target == null && sender instanceof Player p) target = p;
        if (target == null) {
            sender.sendMessage("Specify a target player.");
            return true;
        }
        ScenePlaybackOptions opts = new ScenePlaybackOptions(end, true);
        try {
            this.plugin.getPlaybackManager().startScene(target, replayId, sceneId, opts);
            sender.sendMessage("Started scene " + sceneId + " for " + target.getName() + " (end=" + end + ")");
        } catch (Exception e) {
            sender.sendMessage("Failed to play scene: " + e.getMessage());
        }
        return true;
    }

    private boolean handlePlayTrailer(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("Usage: /flashback playtrailer <player> <replay-uuid>:<scene-id> [<replay-uuid>:<scene-id> ...] [--kick|--restore]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("Unknown player: " + args[1]);
            return true;
        }
        EndBehavior end = EndBehavior.RESTORE;
        java.util.List<duckduck.flashback3000.api.PlaybackApi.TrailerEntry> entries = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (a.equalsIgnoreCase("--kick")) { end = EndBehavior.KICK; continue; }
            if (a.equalsIgnoreCase("--restore")) { end = EndBehavior.RESTORE; continue; }
            int colon = a.indexOf(':');
            if (colon <= 0 || colon >= a.length() - 1) {
                sender.sendMessage("Bad segment: " + a + " (expected <replay-uuid>:<scene-id>)");
                return true;
            }
            UUID replayId;
            try { replayId = UUID.fromString(a.substring(0, colon)); }
            catch (IllegalArgumentException e) {
                sender.sendMessage("Bad UUID in segment: " + a.substring(0, colon));
                return true;
            }
            String sceneId = a.substring(colon + 1);
            entries.add(duckduck.flashback3000.api.PlaybackApi.TrailerEntry.of(replayId, sceneId));
        }
        if (entries.isEmpty()) {
            sender.sendMessage("Need at least one segment.");
            return true;
        }
        ScenePlaybackOptions opts = new ScenePlaybackOptions(end, true);
        java.util.List<duckduck.flashback3000.playback.PlaybackManager.TrailerEntry> mapped = new ArrayList<>();
        for (var e : entries) mapped.add(new duckduck.flashback3000.playback.PlaybackManager.TrailerEntry(e.replayId(), e.sceneId()));
        try {
            this.plugin.getPlaybackManager().startTrailer(target, mapped, opts);
            sender.sendMessage("Started trailer for " + target.getName()
                    + " (" + entries.size() + " segments, end=" + end + ")");
        } catch (Exception e) {
            sender.sendMessage("Failed to play trailer: " + e.getMessage());
        }
        return true;
    }

    private boolean handleScenes(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /flashback scenes <replay-uuid>");
            return true;
        }
        UUID replayId;
        try {
            replayId = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("Replay UUID required.");
            return true;
        }
        ParsedScenes parsed = this.plugin.getSceneStore().load(replayId);
        if (parsed == null) {
            sender.sendMessage("No scenes uploaded for " + replayId);
            return true;
        }
        sender.sendMessage("Scenes for " + replayId + ":");
        for (ParsedScenes.Summary s : parsed.summaries()) {
            sender.sendMessage(" - " + s.id() + " \"" + s.name() + "\" ticks=[" + s.startTick() + ".." + s.endTick() + "] samples=" + s.sampleCount());
        }
        return true;
    }

    private boolean handlePlay(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /flashback play <replay-id-or-filename> [player]");
            return true;
        }
        String identifier = args[1];
        Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2])
                : (sender instanceof Player p ? p : null);
        if (target == null) {
            sender.sendMessage("Specify a target player.");
            return true;
        }
        try {
            java.nio.file.Path path = this.plugin.getPlaybackManager().resolveReplay(identifier);
            if (path == null) {
                sender.sendMessage("Replay not found: " + identifier);
                return true;
            }
            this.plugin.getPlaybackManager().start(target, path);
            sender.sendMessage("Started playback for " + target.getName() + " (" + path.getFileName() + ")");
        } catch (Exception e) {
            sender.sendMessage("Failed to play: " + e.getMessage());
        }
        return true;
    }

    private boolean handlePlayStop(CommandSender sender, String[] args) {
        Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1])
                : (sender instanceof Player p ? p : null);
        if (target == null) {
            sender.sendMessage("Specify a target player.");
            return true;
        }
        this.plugin.getPlaybackManager().cancel(target);
        sender.sendMessage("Cancelled playback for " + target.getName());
        return true;
    }

    private boolean handleStart(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 1);
        if (target == null) return true;
        String name = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
        RecordingManager mgr = this.plugin.getRecordingManager();
        if (mgr.isRecording(target)) {
            sender.sendMessage("Already recording " + target.getName());
            return true;
        }
        try {
            Recorder recorder = mgr.start(target, name);
            sender.sendMessage("Started recording " + target.getName() + " (" + recorder.metadata().name + ")");
        } catch (Exception e) {
            sender.sendMessage("Failed to start: " + e.getMessage());
        }
        return true;
    }

    private boolean handleStop(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 1);
        if (target == null) return true;
        RecordingManager mgr = this.plugin.getRecordingManager();
        if (!mgr.isRecording(target)) {
            sender.sendMessage("Not recording " + target.getName());
            return true;
        }
        sender.sendMessage("Stopping and exporting recording for " + target.getName() + "...");
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            Path out = mgr.stopAndExport(target);
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (out != null) {
                    sender.sendMessage("Saved replay: " + out);
                } else {
                    sender.sendMessage("Failed to save replay (see console).");
                }
            });
        });
        return true;
    }

    private boolean handleCancel(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 1);
        if (target == null) return true;
        this.plugin.getRecordingManager().cancel(target);
        sender.sendMessage("Cancelled recording for " + target.getName());
        return true;
    }

    private boolean handleList(CommandSender sender) {
        var active = this.plugin.getRecordingManager().activeRecorders();
        if (active.isEmpty()) {
            sender.sendMessage("No active recordings.");
            return true;
        }
        sender.sendMessage("Active recordings:");
        for (var entry : active.entrySet()) {
            UUID uuid = entry.getKey();
            Recorder r = entry.getValue();
            sender.sendMessage(" - " + r.bukkitPlayer().getName() + " [" + r.metadata().name + "]");
        }
        return true;
    }

    private boolean handleFiles(CommandSender sender) {
        Path root = this.plugin.getRecordingManager().outputRoot();
        sender.sendMessage("Replays in " + root + ":");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, "*.zip")) {
            int count = 0;
            for (Path p : ds) {
                long size = Files.size(p);
                sender.sendMessage(" - " + p.getFileName() + " (" + (size / 1024) + " KiB)");
                count++;
            }
            if (count == 0) sender.sendMessage(" (none)");
        } catch (IOException e) {
            sender.sendMessage("Failed to list replays: " + e.getMessage());
        }
        return true;
    }

    private Player resolveTarget(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player target = Bukkit.getPlayerExact(args[index]);
            if (target == null) sender.sendMessage("Unknown player: " + args[index]);
            return target;
        }
        if (sender instanceof Player p) return p;
        sender.sendMessage("You must specify a player from console.");
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("start", "stop", "cancel", "list", "files", "play", "playscene", "playtrailer", "playstop", "scenes"));
            subs.removeIf(s -> !s.startsWith(args[0].toLowerCase()));
            return subs;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("start")
                || args[0].equalsIgnoreCase("stop")
                || args[0].equalsIgnoreCase("cancel"))) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) names.add(p.getName());
            }
            return names;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("playscene")
                || args[0].equalsIgnoreCase("scenes"))) {
            List<String> ids = new ArrayList<>();
            try {
                this.plugin.getServerProtocol().library().list().forEach(e -> ids.add(e.uuid().toString()));
            } catch (IOException ignored) {}
            ids.removeIf(s -> !s.startsWith(args[1]));
            return ids;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("playscene")) {
            List<String> ids = new ArrayList<>();
            try {
                UUID replayId = UUID.fromString(args[1]);
                ParsedScenes parsed = this.plugin.getSceneStore().load(replayId);
                if (parsed != null) {
                    for (ParsedScenes.Summary s : parsed.summaries()) ids.add(s.id());
                }
            } catch (IllegalArgumentException ignored) {}
            ids.removeIf(s -> !s.toLowerCase().startsWith(args[2].toLowerCase()));
            return ids;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("playscene")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[3].toLowerCase())) names.add(p.getName());
            }
            return names;
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("playscene")) {
            List<String> opts = new ArrayList<>(List.of("--restore", "--kick"));
            opts.removeIf(s -> !s.startsWith(args[4].toLowerCase()));
            return opts;
        }
        if (args[0].equalsIgnoreCase("playtrailer")) {
            if (args.length == 2) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) names.add(p.getName());
                }
                return names;
            }
            if (args.length >= 3) {
                String token = args[args.length - 1];
                int colon = token.indexOf(':');
                List<String> out = new ArrayList<>();
                if (colon < 0) {
                    // suggest replay UUIDs + colon
                    try {
                        for (var entry : this.plugin.getServerProtocol().library().list()) {
                            String s = entry.uuid() + ":";
                            if (s.startsWith(token)) out.add(s);
                        }
                    } catch (IOException ignored) {}
                    if ("--restore".startsWith(token)) out.add("--restore");
                    if ("--kick".startsWith(token)) out.add("--kick");
                } else {
                    String prefix = token.substring(0, colon + 1);
                    String partial = token.substring(colon + 1);
                    try {
                        UUID replayId = UUID.fromString(token.substring(0, colon));
                        var parsed = this.plugin.getSceneStore().load(replayId);
                        if (parsed != null) {
                            for (var s : parsed.summaries()) {
                                if (s.id().toLowerCase().startsWith(partial.toLowerCase())) {
                                    out.add(prefix + s.id());
                                }
                            }
                        }
                    } catch (IllegalArgumentException ignored) {}
                }
                return out;
            }
        }
        return List.of();
    }
}
