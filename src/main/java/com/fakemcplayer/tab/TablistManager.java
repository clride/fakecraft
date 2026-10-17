package com.fakemcplayer.tab;

import com.fakemcplayer.FakeMCPlayer;
import com.fakemcplayer.config.BotConfig;
import com.fakemcplayer.dashboard.DashboardEvents;
import com.fakemcplayer.dashboard.DashboardState;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Shows the bot in the server tab-list without a real login, via NMS
 * ClientboundPlayerInfoUpdatePacket (ADD_PLAYER / UPDATE_LISTED).
 *
 * <p>Fully reflective so one jar works across Paper/Purpur 1.20-1.21.
 * If packets can't be built (unsupported version), it logs once and
 * the bot still chats normally.</p>
 */
public class TablistManager implements Listener {

    private final FakeMCPlayer plugin;
    private BotConfig config;
    private SkinService skins;
    private DashboardEvents events;
    private DashboardState dashState;
    private volatile boolean nmsFailed = false;
    private int refreshTask = -1;
    private volatile boolean joinAnnounced = false;

    public TablistManager(FakeMCPlayer plugin, BotConfig config, SkinService skins,
                          DashboardEvents events, DashboardState leftFlag) {
        this.plugin = plugin;
        this.config = config;
        this.skins = skins;
        this.events = events;
        this.dashState = leftFlag;
    }

    public void setConfig(BotConfig config) {
        this.config = config;
        this.nmsFailed = false;
    }

    public void setSkins(SkinService skins) {
        this.skins = skins;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!config.isShowInTab() || isLeft()) return;
        Player joining = event.getPlayer();
        // Silent inject for the joiner only — a real player does NOT
        // re-announce "joined the game" when someone else joins.
        Bukkit.getScheduler().runTaskLater(plugin, () -> addToViewer(joining), 20L);
    }

    /** First show after enable: inject to everyone + one vanilla-like join message. */
    public void addToAll() {
        addToAllSilent();
        if (config.isShowInTab() && config.isFakeJoinMessage() && !joinAnnounced) {
            joinAnnounced = true;
            Bukkit.getScheduler().runTaskLater(plugin, this::announceFakeJoin, 40L);
        }
        // refresh periodically: some tab plugins clear custom entries.
        // Also re-applies realistic ping jitter so the entry never looks frozen.
        restartTask();
    }

    /** Re-arm the periodic silent re-inject / ping-jitter task. */
    private void restartTask() {
        if (refreshTask != -1) Bukkit.getScheduler().cancelTask(refreshTask);
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::addToAllSilent,
                20L * 120, 20L * 120).getTaskId();
    }

    /** Operator-triggered rejoin: silent inject + refresh, no announce (PresenceManager announces). */
    public void showAll() {
        restartTask();
        addToAllSilent();
    }

    /** True while the bot is "left" (leave/rejoin toggle deciding all presence). */
    public boolean isLeft() {
        return dashState != null && dashState.isBotLeft();
    }

    /** The one vanilla-like "<Bot> joined the game" line. Also feeds the console feed. */
    private void announceFakeJoin() {
        Bukkit.getServer().sendMessage(
                Component.text(config.getBotName() + " joined the game",
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        if (events != null) {
            events.add("player_message", DashboardEvents.ev(config.getBotName(),
                    config.getBotName() + " joined the game").bool("system", true));
        }
    }

    /** Silent re-inject (joins, skin refresh, ping jitter). No chat output. */
    public void addToAllSilent() {
        if (isLeft() || !config.isShowInTab()) return;
        for (Player p : Bukkit.getOnlinePlayers()) addToViewer(p);
    }

    public void removeFromAll() {
        if (refreshTask != -1) {
            try { Bukkit.getScheduler().cancelTask(refreshTask); } catch (Exception ignored) {}
            refreshTask = -1;
        }
        for (Player p : Bukkit.getOnlinePlayers()) removeFromViewer(p);
    }

    // ---------- packet work ----------

    private void addToViewer(Player viewer) {
        if (nmsFailed || !config.isShowInTab()) return;
        try {
            Object packet = buildAddPacket();
            if (packet == null) return;
            sendPacket(viewer, packet);
        } catch (Exception e) {
            failOnce(e);
        }
    }

    private void removeFromViewer(Player viewer) {
        if (nmsFailed) return;
        try {
            Object packet = buildRemovePacket();
            if (packet == null) return;
            sendPacket(viewer, packet);
        } catch (Exception ignored) {}
    }

    private void failOnce(Exception e) {
        if (!nmsFailed) {
            nmsFailed = true;
            plugin.getLogger().warning("Tab-list spoof unavailable on this version ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "). Bot will still chat normally. "
                    + "Consider adding ProtocolLib or setting bot.show-in-tab: false.");
        }
    }

    private void sendPacket(Player viewer, Object packet) throws Exception {
        // CraftPlayer.getHandle().connection.send(packet)
        Method getHandle = viewer.getClass().getMethod("getHandle");
        Object serverPlayer = getHandle.invoke(viewer);
        Object connection = getConnection(serverPlayer);
        Method send = findSendMethod(connection, packet.getClass());
        send.invoke(connection, packet);
    }

    private static Object getConnection(Object serverPlayer) throws Exception {
        // 1.21+: field 'connection'; older: 'b' / get 'connection()' method
        for (String name : new String[]{"connection", "b", "c"}) {
            try {
                Field f = serverPlayer.getClass().getField(name);
                f.setAccessible(true);
                Object v = f.get(serverPlayer);
                if (v != null) return v;
            } catch (NoSuchFieldException ignored) {}
        }
        for (Field f : serverPlayer.getClass().getDeclaredFields()) {
            if (f.getType().getName().contains("ServerGamePacketListenerImpl")) {
                f.setAccessible(true);
                return f.get(serverPlayer);
            }
        }
        // try method
        try {
            Method m = serverPlayer.getClass().getMethod("connection");
            return m.invoke(serverPlayer);
        } catch (NoSuchMethodException ignored) {}
        throw new IllegalStateException("Cannot find player connection");
    }

    private static Method findSendMethod(Object connection, Class<?> packetClass) throws Exception {
        // send(Packet) — parameter type is the base Packet class
        for (Method m : connection.getClass().getMethods()) {
            if (!m.getName().equals("send")) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0].isAssignableFrom(packetClass)) return m;
        }
        // fallback: send(Packet, ...) variants
        for (Method m : connection.getClass().getMethods()) {
            if (m.getName().equals("send") && m.getParameterCount() >= 1
                    && m.getParameterTypes()[0].isAssignableFrom(packetClass)) return m;
        }
        throw new IllegalStateException("Cannot find connection.send method");
    }

    private Object buildAddPacket() throws Exception {
        Class<?> infoPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
        Class<?> actionEnum = null;
        for (Class<?> c : infoPacket.getDeclaredClasses()) {
            if (c.getSimpleName().equals("Action") || c.isEnum()) {
                if (c.isEnum()) { actionEnum = c; break; }
            }
        }
        if (actionEnum == null) throw new IllegalStateException("Action enum not found");

        @SuppressWarnings({"unchecked", "rawtypes"})
        EnumSet actions = EnumSet.of(
                Enum.valueOf((Class<Enum>) actionEnum, "ADD_PLAYER"),
                Enum.valueOf((Class<Enum>) actionEnum, "UPDATE_LISTED"));
        // add display-name/latency actions when present (older versions have fewer)
        for (String extra : new String[]{"UPDATE_DISPLAY_NAME", "UPDATE_LATENCY", "UPDATE_GAME_MODE"}) {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Enum e = Enum.valueOf((Class<Enum>) actionEnum, extra);
                actions.add(e);
            } catch (IllegalArgumentException ignored) {}
        }

        Object infoData = buildInfoData();
        List<Object> entries = Collections.singletonList(infoData);

        // ctor variants across versions: (EnumSet, Collection) is 1.20.2+
        for (Constructor<?> ctor : infoPacket.getDeclaredConstructors()) {
            Class<?>[] ps = ctor.getParameterTypes();
            if (ps.length == 2 && EnumSet.class.isAssignableFrom(ps[0])
                    && Collection.class.isAssignableFrom(ps[1])) {
                ctor.setAccessible(true);
                return ctor.newInstance(actions, entries);
            }
        }
        throw new IllegalStateException("Compatible ClientboundPlayerInfoUpdatePacket ctor not found");
    }

    private Object buildRemovePacket() throws Exception {
        Class<?> removePacket = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");
        UUID uuid = config.getBotUuid();
        for (Constructor<?> ctor : removePacket.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 1 && List.class.isAssignableFrom(ctor.getParameterTypes()[0])) {
                ctor.setAccessible(true);
                return ctor.newInstance(List.of(uuid));
            }
        }
        throw new IllegalStateException("Compatible ClientboundPlayerInfoRemovePacket ctor not found");
    }

    /** Builds PlayerInfoData, filling unknown record components with safe defaults. */
    private Object buildInfoData() throws Exception {
        Class<?> infoPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
        Class<?> dataClass = null;
        for (Class<?> c : infoPacket.getDeclaredClasses()) {
            if (c.getSimpleName().equals("PlayerInfoData") || c.getSimpleName().equals("Entry")) {
                dataClass = c;
                break;
            }
        }
        if (dataClass == null) {
            for (Class<?> c : infoPacket.getDeclaredClasses()) {
                // PlayerInfoData is the nested class holding a GameProfile
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    for (Class<?> p : ctor.getParameterTypes()) {
                        if (p.getName().equals("com.mojang.authlib.GameProfile")) {
                            dataClass = c;
                            break;
                        }
                    }
                }
            }
        }
        if (dataClass == null) throw new IllegalStateException("PlayerInfoData class not found");

        Object profile = buildGameProfile();
        Object gameMode = enumValue("net.minecraft.world.level.GameType", "SURVIVAL");
        Object displayName = literalComponent(config.getBotName());
        if (gameMode == null) throw new IllegalStateException("GameType.SURVIVAL not found");
        if (displayName == null) throw new IllegalStateException("Cannot build display Component");

        for (Constructor<?> ctor : dataClass.getDeclaredConstructors()) {
            Object[] args = matchArgs(ctor.getParameterTypes(), profile, gameMode, displayName);
            if (args != null) {
                ctor.setAccessible(true);
                Object entry = ctor.newInstance(args);
                validateEntry(entry);
                return entry;
            }
        }
        throw new IllegalStateException("Compatible PlayerInfoData ctor not found");
    }

    private Object[] matchArgs(Class<?>[] types, Object profile, Object gameMode, Object displayName) {
        Object[] args = new Object[types.length];
        boolean sawProfile = false;
        UUID botId = config.getBotUuid();
        int latency = effectiveLatency();
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            String tn = t.getName();
            if (tn.equals("com.mojang.authlib.GameProfile")) {
                args[i] = profile;
                sawProfile = true;
            } else if (t == UUID.class) {
                // 1.20.2+ Entry carries a separate profileId UUID alongside the GameProfile.
                // Leaving it null encodes to writeUUID(null) -> kicks the viewer (see #1).
                args[i] = botId;
                sawProfile = true;
            } else if (t == boolean.class || t == Boolean.class) {
                args[i] = true; // listed = true
            } else if (t == int.class || t == Integer.class) {
                args[i] = latency;
            } else if (tn.equals("net.minecraft.world.level.GameType")) {
                args[i] = gameMode;
            } else if (tn.equals("net.minecraft.network.chat.Component")) {
                args[i] = displayName;
            } else if (tn.equals("net.minecraft.network.chat.numbers.FixedFormat")) {
                args[i] = null;
            } else if (t == String.class) {
                // Some versions carry a separate profile name field.
                args[i] = config.getBotName();
                sawProfile = true;
            } else if (t.isEnum()) {
                // e.g. ListOrder / other enums: use first constant
                Object[] constants = t.getEnumConstants();
                args[i] = constants.length > 0 ? constants[0] : null;
            } else {
                args[i] = null; // chat session, etc. — null = no signature, fine
            }
        }
        return sawProfile ? args : null;
    }

    /** Fixed config latency, or human-like 18–55ms jitter when realistic-ping is on. */
    private int effectiveLatency() {
        if (!config.isRealisticPing()) return config.getTabLatency();
        return 18 + java.util.concurrent.ThreadLocalRandom.current().nextInt(38);
    }

    /**
     * Pre-send guard: a null profileId UUID passes reflection but fails at encode
     * time on the netty thread, kicking the viewer with
     * "Failed to encode packet 'clientbound/minecraft:player_info_update'".
     * Validate here so a bad entry only logs instead of disconnecting players.
     */
    private void validateEntry(Object entry) throws Exception {
        for (Method m : entry.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            String n = m.getName();
            if (n.equals("profileId") || n.equals("uuid") || n.equals("id")
                    || n.equals("profileID") || n.equals("getProfileId")) {
                if (UUID.class.isAssignableFrom(m.getReturnType()) && m.invoke(entry) == null) {
                    throw new IllegalStateException("Built tab entry has null profileId UUID");
                }
            }
        }
        for (Field f : entry.getClass().getDeclaredFields()) {
            if (UUID.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                if (f.get(entry) == null) {
                    throw new IllegalStateException("Built tab entry has null UUID field '" + f.getName() + "'");
                }
            }
        }
    }

    private Object buildGameProfile() throws Exception {
        Class<?> gpClass = Class.forName("com.mojang.authlib.GameProfile");
        Constructor<?> ctor = gpClass.getConstructor(UUID.class, String.class);
        Object profile = ctor.newInstance(config.getBotUuid(), config.getBotName());

        String value = skins != null ? skins.getValue() : config.getSkinValue();
        String signature = skins != null ? skins.getSignature() : config.getSkinSignature();
        // backwards compat: manual config override still wins if skins service is empty
        if ((value == null || value.isBlank()) && config.getSkinValue() != null
                && !config.getSkinValue().isBlank()) {
            value = config.getSkinValue();
            signature = config.getSkinSignature();
        }
        if (value != null && !value.isBlank()) {
            try {
                Method getProps = gpClass.getMethod("getProperties");
                Object propMap = getProps.invoke(profile);
                Class<?> propClass = Class.forName("com.mojang.authlib.properties.Property");
                Object prop;
                try {
                    Constructor<?> pc = propClass.getConstructor(String.class, String.class, String.class);
                    prop = pc.newInstance("textures",
                            value, (signature == null || signature.isBlank()) ? null : signature);
                } catch (NoSuchMethodException e) {
                    Constructor<?> pc = propClass.getConstructor(String.class, String.class);
                    prop = pc.newInstance("textures", value);
                }
                Method put = propMap.getClass().getMethod("put", Object.class, Object.class);
                put.invoke(propMap, "textures", prop);
            } catch (Exception e) {
                plugin.getLogger().fine("Could not apply bot skin: " + e.getMessage());
            }
        }
        return profile;
    }

    private static Object enumValue(String className, String constant) {
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Enum> c = (Class<Enum>) Class.forName(className);
            return Enum.valueOf(c, constant);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object literalComponent(String text) {
        try {
            Class<?> comp = Class.forName("net.minecraft.network.chat.Component");
            return comp.getMethod("literal", String.class).invoke(null, text);
        } catch (Exception e) {
            return null;
        }
    }
}
