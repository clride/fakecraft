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
                        + " | enabled=" + config.isEnabled(), NamedTextColor.AQUA));
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
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("ask", "status"));
            if (sender.hasPermission("fakemcplayer.admin")) base.addAll(List.of("reload", "on", "off"));
            String prefix = args[0].toLowerCase();
            return base.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
