package com.fakemcplayer.command;

import com.fakemcplayer.FakeMCPlayer;
import com.fakemcplayer.chat.BotChatService;
import com.fakemcplayer.config.BotConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BotCommand implements CommandExecutor, TabCompleter {

    private final FakeMCPlayer plugin;
    private BotConfig config;
    private final BotChatService chatService;

    public BotCommand(FakeMCPlayer plugin, BotConfig config, BotChatService chatService) {
        this.plugin = plugin;
        this.config = config;
        this.chatService = chatService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "ask" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use /bot ask.");
                    return true;
                }
                if (!sender.hasPermission("fakemcplayer.use")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /" + label + " ask <message>", NamedTextColor.YELLOW));
                    return true;
                }
                String question = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                // show the question in chat so others see context? No — direct ask.
                if (!chatService.checkCooldown(player.getName())) {
                    sender.sendMessage(Component.text("The bot needs a moment... try again in a few seconds.",
                            NamedTextColor.GRAY));
                    return true;
                }
                sender.sendMessage(Component.text("You -> " + config.getBotName() + ": " + question,
                        NamedTextColor.GRAY));
                chatService.respondTo(player.getName(), question);
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("fakemcplayer.admin")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                    return true;
                }
                plugin.reloadBot();
                this.config = plugin.getBotConfig();
                sender.sendMessage(Component.text("FakeMCPlayer reloaded.", NamedTextColor.GREEN));
                return true;
            }
            case "status" -> {
                sender.sendMessage(Component.text("Bot: " + config.getBotName()
                        + " | provider=" + config.getProvider()
                        + " | tab=" + config.isShowInTab()
                        + " | enabled=" + config.isEnabled()
                        + " | left=" + plugin.getDashboardState().isBotLeft(), NamedTextColor.AQUA));
                return true;
            }
            case "leave", "rejoin" -> {
                if (!sender.hasPermission("fakemcplayer.admin")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                    return true;
                }
                boolean wantLeft = args[0].equalsIgnoreCase("leave");
                boolean isLeft = plugin.getDashboardState().isBotLeft();
                if (wantLeft && isLeft) {
                    sender.sendMessage(Component.text("The bot already left the server.",
                            NamedTextColor.YELLOW));
                    return true;
                }
                if (!wantLeft && !isLeft) {
                    sender.sendMessage(Component.text("The bot is already on the server.",
                            NamedTextColor.YELLOW));
                    return true;
                }
                var pm = plugin.getPresenceManager();
                if (wantLeft) pm.leave(); else pm.rejoin();
                sender.sendMessage(Component.text(wantLeft
                        ? "The bot left the server (rejoin with /" + label + " rejoin)."
                        : "The bot rejoined the server.", NamedTextColor.GREEN));
                return true;
            }
            case "dashboard" -> {
                if (!sender.hasPermission("fakemcplayer.admin")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                    return true;
                }
                var ds = plugin.getDashboardServer();
                if (ds == null || !ds.isRunning()) {
                    sender.sendMessage(Component.text("Dashboard is disabled (dashboard.enabled).",
                            NamedTextColor.YELLOW));
                    return true;
                }
                sender.sendMessage(Component.text("Dashboard: " + ds.getUrl(), NamedTextColor.AQUA));
                sender.sendMessage(Component.text("Token: " + ds.getToken(), NamedTextColor.GRAY));
                return true;
            }
            case "mode" -> {
                if (!sender.hasPermission("fakemcplayer.admin")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /" + label + " mode <auto|approve|manual>",
                            NamedTextColor.YELLOW));
                    return true;
                }
                String mode = com.fakemcplayer.dashboard.DashboardState.normalize(args[1]);
                plugin.getDashboardState().setMode(mode);
                plugin.getDashboardEvents().add("mode",
                        com.fakemcplayer.dashboard.DashboardEvents.p().str("mode", mode));
                sender.sendMessage(Component.text("Bot mode: " + mode
                        + " (auto = replies itself, approve = drafts for review, manual = you write)",
                        NamedTextColor.GREEN));
                return true;
            }
            case "on", "off" -> {
                if (!sender.hasPermission("fakemcplayer.admin")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                    return true;
                }
                boolean enable = args[0].equalsIgnoreCase("on");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getConfig().set("enabled", enable);
                    plugin.saveConfig();
                    plugin.reloadBot();
                    this.config = plugin.getBotConfig();
                });
                sender.sendMessage(Component.text("Bot " + (enable ? "enabled." : "disabled."),
                        NamedTextColor.GREEN));
                return true;
            }
            default -> {
                sendHelp(sender, label);
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.text("FakeMCPlayer (" + config.getBotName() + ")", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/" + label + " ask <message> - talk to the bot", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " status - show config", NamedTextColor.GRAY));
        if (sender.hasPermission("fakemcplayer.admin")) {
            sender.sendMessage(Component.text("/" + label + " reload - reload config", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("/" + label + " on|off - enable/disable", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("/" + label + " dashboard - show the console URL + token", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("/" + label + " mode <auto|approve|manual> - reply mode", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("/" + label + " leave|rejoin - remove the bot from the server (tab + chat) or bring it back", NamedTextColor.GRAY));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("ask", "status"));
            if (sender.hasPermission("fakemcplayer.admin")) {
                base.addAll(List.of("reload", "on", "off", "dashboard", "mode", "leave", "rejoin"));
            }
            String prefix = args[0].toLowerCase();
            return base.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            String prefix = args[1].toLowerCase();
            return List.of("auto", "approve", "manual").stream()
                    .filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
