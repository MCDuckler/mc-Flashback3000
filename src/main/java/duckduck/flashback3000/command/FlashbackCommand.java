package duckduck.flashback3000.command;

import duckduck.flashback3000.Flashback3000;
import duckduck.flashback3000.RecordingManager;
import duckduck.flashback3000.record.Recorder;
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
            sender.sendMessage("Usage: /flashback <start|stop|cancel|list|files> [player] [name]");
            return true;
        }
        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "start" -> handleStart(sender, args);
            case "stop" -> handleStop(sender, args);
            case "cancel" -> handleCancel(sender, args);
            case "list" -> handleList(sender);
            case "files" -> handleFiles(sender);
            default -> {
                sender.sendMessage("Unknown subcommand: " + sub);
                yield true;
            }
        };
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
            List<String> subs = new ArrayList<>(List.of("start", "stop", "cancel", "list", "files"));
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
        return List.of();
    }
}
