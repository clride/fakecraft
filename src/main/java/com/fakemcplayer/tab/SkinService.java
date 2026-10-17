package com.fakemcplayer.tab;

import com.fakemcplayer.FakeMCPlayer;
import com.fakemcplayer.config.BotConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Resolves the tab-list skin from a real Java account.
 *
 * <p>Priority: manual {@code bot.skin.value} override &gt; cached fetch &gt;
 * live Mojang fetch from {@code bot.skin.source-username} / {@code source-uuid}.
 * The bot keeps its own fake UUID — only the texture property is copied, so
 * there is never a UUID clash if the source account joins.</p>
 */
public class SkinService {

    private final FakeMCPlayer plugin;
    private final HttpClient http;
    private volatile String value = "";
    private volatile String signature = "";
    private volatile String resolvedName = "";

    public SkinService(FakeMCPlayer plugin, HttpClient http) {
        this.plugin = plugin;
        this.http = http;
    }

    public String getValue() { return value == null ? "" : value; }
    public String getSignature() { return signature == null ? "" : signature; }
    public boolean hasSkin() { return value != null && !value.isBlank(); }

    /** Blocking cache load + kick off async Mojang fetch if needed. Call from enable/reload. */
    public void loadFromConfig(BotConfig config) {
        String manualValue = nullToEmpty(config.getSkinValue());
        String manualSig = nullToEmpty(config.getSkinSignature());
        if (!manualValue.isBlank()) {
            this.value = manualValue;
            this.signature = manualSig;
            this.resolvedName = "";
            plugin.getLogger().info("Using manual skin override from config.");
            return;
        }

        String sourceUuid = nullToEmpty(config.getSkinSourceUuid());
        String sourceName = nullToEmpty(config.getSkinSourceUsername());

        // 1. try disk cache first so tab looks right immediately after restart
        if (loadCache(sourceUuid, sourceName)) {
            plugin.getLogger().info("Using cached skin"
                    + (resolvedName.isBlank() ? "" : " of " + resolvedName) + ". Refreshing in background...");
        }

        if (sourceUuid.isBlank() && sourceName.isBlank()) {
            if (!hasSkin()) plugin.getLogger().info("No skin source configured; bot uses Steve/Alex. "
                    + "Set bot.skin.source-username to copy a real skin.");
            return;
        }

        // 2. async refresh from Mojang (never blocks enable)
        refreshFromMojangAsync(sourceUuid, sourceName);
    }

    public void refreshFromConfig(BotConfig config) {
        loadFromConfig(config);
    }

    // ---------- cache ----------

    private File cacheFile() {
        return new File(plugin.getDataFolder(), "skin-cache.yml");
    }

    private boolean loadCache(String sourceUuid, String sourceName) {
        try {
            File f = cacheFile();
            if (!f.exists()) return false;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            String cValue = y.getString("value", "");
            if (cValue == null || cValue.isBlank()) return false;
            String cUuid = y.getString("source-uuid", "");
            String cName = y.getString("source-username", "");
            // only reuse cache if it matches the currently configured source
            if (!sourceUuid.isBlank() && !sourceUuid.equalsIgnoreCase(cUuid)
                    && !normalizeUuid(sourceUuid).equalsIgnoreCase(normalizeUuid(cUuid))) {
                // configured uuid changed — still allow if username also matches? be strict:
                if (sourceName.isBlank() || !sourceName.equalsIgnoreCase(cName)) return false;
            } else if (sourceUuid.isBlank() && !sourceName.isBlank()
                    && !sourceName.equalsIgnoreCase(cName)) {
                return false;
            }
            this.value = cValue;
            this.signature = nullToEmpty(y.getString("signature", ""));
            this.resolvedName = nullToEmpty(y.getString("resolved-name", cName));
            return true;
        } catch (Exception e) {
            plugin.getLogger().fine("Skin cache read failed: " + e.getMessage());
            return false;
        }
    }

    private void saveCache(String sourceUuid, String sourceName, String resolved,
                           String v, String s) {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("source-uuid", sourceUuid);
            y.set("source-username", sourceName);
            y.set("resolved-name", resolved);
            y.set("value", v);
            y.set("signature", s);
            y.set("fetched-at", System.currentTimeMillis());
            y.save(cacheFile());
        } catch (Exception e) {
            plugin.getLogger().fine("Skin cache save failed: " + e.getMessage());
        }
    }

    // ---------- mojang ----------

    private void refreshFromMojangAsync(String sourceUuid, String sourceName) {
        Logger log = plugin.getLogger();
        // resolve username -> uuid first (skip if uuid given)
        java.util.concurrent.CompletableFuture<String> hexFuture;
        final String[] resolvedHolder = {""};
        if (!sourceUuid.isBlank()) {
            hexFuture = java.util.concurrent.CompletableFuture.completedFuture(normalizeUuid(sourceUuid));
        } else {
            String name = sourceName;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();
            hexFuture = http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
                if (resp.statusCode() == 404 || resp.statusCode() == 204) {
                    throw new RuntimeException("Minecraft account '" + name + "' not found (check spelling, Java Edition only)");
                }
                if (resp.statusCode() == 429) throw new RuntimeException("Mojang rate limit, keeping cached skin");
                if (resp.statusCode() != 200) throw new RuntimeException("Mojang HTTP " + resp.statusCode());
                JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                resolvedHolder[0] = o.has("name") ? o.get("name").getAsString() : name;
                return o.get("id").getAsString();
            });
        }

        hexFuture.thenCompose(hex -> {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + hex + "?unsigned=false"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
                if (resp.statusCode() != 200) throw new RuntimeException("Session server HTTP " + resp.statusCode());
                JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                String rName = o.has("name") ? o.get("name").getAsString() : resolvedHolder[0];
                JsonArray props = o.getAsJsonArray("properties");
                if (props == null) throw new RuntimeException("No skin properties in Mojang response");
                for (int i = 0; i < props.size(); i++) {
                    JsonObject p = props.get(i).getAsJsonObject();
                    if ("textures".equals(p.get("name").getAsString())) {
                        String v = p.get("value").getAsString();
                        String s = p.has("signature") && !p.get("signature").isJsonNull()
                                ? p.get("signature").getAsString() : "";
                        return new String[]{rName == null ? "" : rName, v, s};
                    }
                }
                throw new RuntimeException("No textures property in Mojang response");
            });
        }).thenAccept(arr -> {
            this.value = arr[1];
            this.signature = arr[2];
            this.resolvedName = arr[0];
            saveCache(sourceUuid, sourceName, arr[0], arr[1], arr[2]);
            log.info("Skin copied from " + (arr[0].isBlank() ? "Mojang profile" : arr[0]) + ". Refreshing tab-list...");
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                // re-inject tab entries now that the skin is known
                try {
                    java.lang.reflect.Method m = plugin.getTablistManager().getClass()
                            .getMethod("addToAllSilent");
                    m.invoke(plugin.getTablistManager());
                } catch (Exception ignored) {}
            });
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (hasSkin()) {
                log.warning("Skin refresh failed (" + cause.getMessage() + "); keeping cached skin.");
            } else {
                log.warning("Skin fetch failed (" + cause.getMessage() + "); bot uses default Steve/Alex. "
                        + "It will still show the right name in tab.");
            }
            return null;
        });
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    /** Accepts dashed or undashed UUID, returns 32-char hex for Mojang URLs. */
    private static String normalizeUuid(String raw) {
        String hex = raw.replace("-", "");
        // allow plain usernames to pass through untouched (caller decides path)
        if (hex.length() != 32) {
            try {
                UUID u = UUID.fromString(raw);
                return u.toString().replace("-", "");
            } catch (IllegalArgumentException e) {
                return raw;
            }
        }
        return hex;
    }
}
