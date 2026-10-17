package com.fakemcplayer.dashboard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator-console live state: reply mode, hold switch, per-player mutes.
 * Persisted to dashboard-state.yml so restarts and /bot reload keep your
 * choice without ever rewriting the commented config.yml.
 */
public class DashboardState {

    public static final String MODE_AUTO = "auto";
    public static final String MODE_APPROVE = "approve";
    public static final String MODE_MANUAL = "manual";

    private final JavaPlugin plugin;
    private final File file;
    private volatile String mode;
    private volatile boolean paused = false;
    private volatile boolean botLeft = false;
    private volatile int playersOnline = 0;
    private final Set<String> muted = ConcurrentHashMap.newKeySet();

    public DashboardState(JavaPlugin plugin, String defaultMode) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "dashboard-state.yml");
        this.mode = normalize(defaultMode);
        load();
    }

    public static String normalize(String m) {
        if (m == null) return MODE_AUTO;
        return switch (m.trim().toLowerCase(Locale.ROOT)) {
            case MODE_MANUAL -> MODE_MANUAL;
            case MODE_APPROVE -> MODE_APPROVE;
            default -> MODE_AUTO;
        };
    }

    private void load() {
        try {
            if (!file.exists()) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            this.mode = normalize(y.getString("mode", mode));
            this.paused = y.getBoolean("paused", false);
            this.botLeft = y.getBoolean("bot-left", false);
            muted.clear();
            for (String s : y.getStringList("muted")) {
                if (s != null && !s.isBlank()) muted.add(s.toLowerCase(Locale.ROOT));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load dashboard-state.yml: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("mode", mode);
            y.set("paused", paused);
            y.set("bot-left", botLeft);
            y.set("muted", new ArrayList<>(muted));
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            y.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not save dashboard-state.yml: " + e.getMessage());
        }
    }

    public String getMode() { return mode; }

    public void setMode(String m) {
        this.mode = normalize(m);
        save();
    }

    public boolean isAuto() { return MODE_AUTO.equals(mode); }
    public boolean isApprove() { return MODE_APPROVE.equals(mode); }
    public boolean isManual() { return MODE_MANUAL.equals(mode); }

    public boolean isPaused() { return paused; }

    public void setPaused(boolean p) {
        this.paused = p;
        save();
    }

    public boolean isMuted(String player) {
        return player != null && muted.contains(player.toLowerCase(Locale.ROOT));
    }

    /** True while the bot has left the server (leave/rejoin toggle). */
    public boolean isBotLeft() { return botLeft; }

    public void setBotLeft(boolean left) {
        this.botLeft = left;
        save();
    }

    public void setMuted(String player, boolean m) {
        if (player == null || player.isBlank()) return;
        if (m) muted.add(player.toLowerCase(Locale.ROOT));
        else muted.remove(player.toLowerCase(Locale.ROOT));
        save();
    }

    public Set<String> mutedPlayers() { return Set.copyOf(muted); }

    public int getPlayersOnline() { return playersOnline; }

    /** Snapshot updated by a main-thread task every couple of seconds. */
    public void setPlayersOnline(int n) { this.playersOnline = n; }
}
