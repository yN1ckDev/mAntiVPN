package it.mattiolservices.mantivpn.alert.manager;

import com.velocitypowered.api.proxy.ProxyServer;
import it.mattiolservices.mantivpn.MAntiVPN;
import it.mattiolservices.mantivpn.discord.DiscordWebhookManager;
import it.mattiolservices.mantivpn.alert.info.AlertInfo;
import it.mattiolservices.mantivpn.antivpn.core.IPCheckResult;
import it.mattiolservices.mantivpn.antivpn.type.CheckType;
import it.mattiolservices.mantivpn.config.ConfigManager;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AlertManager {

    private final ProxyServer proxyServer;
    private final ConcurrentHashMap<String, AlertInfo> alertCache;
    private final ScheduledExecutorService cleanupExecutor;
    private final DiscordWebhookManager discordWebhookManager;
    private final int maxCacheSize;
    private final int expireMinutes;

    public AlertManager(ProxyServer proxyServer) {
        this.proxyServer = proxyServer;

        ConfigManager config = MAntiVPN.getConfigManager();
        this.maxCacheSize = config.getConfig().getInt("alerts.cache.max-size", 1000);
        this.expireMinutes = config.getConfig().getInt("alerts.cache.expire-minutes", 5);

        this.alertCache = new ConcurrentHashMap<>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AlertManager-Cleanup");
            t.setDaemon(true);
            return t;
        });

        this.discordWebhookManager = new DiscordWebhookManager();

        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredEntries, 1, 1, TimeUnit.MINUTES);
    }

    public void sendAlert(String username, String playerIP, IPCheckResult result) {
        ConfigManager config = MAntiVPN.getConfigManager();

        if (!config.getConfig().getBoolean("alerts.enable", true)) {
            return;
        }

        if (username == null || playerIP == null || result == null) {
            if (config.getConfig().getBoolean("alerts.logging.warn-null-parameters", true)) {
                log.warn("[!] Cannot send alert: null parameter detected (username: {}, playerIP: {}, result: {})",
                        username, playerIP, result);
            }
            return;
        }

        String cacheKey = playerIP + ":" + username;
        AlertInfo cachedAlert = alertCache.get(cacheKey);

        if (cachedAlert != null && !isExpired(cachedAlert)) {
            if (config.getConfig().getBoolean("alerts.logging.debug-cached-alerts", false)) {
                log.info("[!] Alert for player {} (IP: {}) is cached, skipping duplicate alert", username, playerIP);
            }
            return;
        }

        AlertInfo alertInfo = AlertInfo.builder()
                .username(username)
                .playerIP(playerIP)
                .result(result)
                .timestamp(LocalDateTime.now())
                .build();

        if (alertCache.size() >= maxCacheSize) {
            cleanupOldestEntries();
        }

        alertCache.put(cacheKey, alertInfo);

        CompletableFuture.runAsync(() -> {
            try {
                sendAlertToStaff(alertInfo);
            } catch (Exception e) {
                if (config.getConfig().getBoolean("alerts.logging.error-send-failures", true)) {
                    log.error("[!] Failed to send alert for player {}: {}", username, e.getMessage());
                }
            }
        });
    }

    private void sendAlertToStaff(AlertInfo alertInfo) {
        ConfigManager config = MAntiVPN.getConfigManager();

        sendInGameAlert(alertInfo, config);

        sendDiscordAlert(alertInfo, config);
    }

    private void sendInGameAlert(AlertInfo alertInfo, ConfigManager config) {
        String alertMessage = config.getMessages().getString("alerts.message",
                "&c[AntiVPN] &e%player% &7tried to connect from a suspicious IP: &c%ip%\n" +
                        "&7Detection: &e%detection% &7| Score: &c%score% &7| Time: &e%time%");

        String detectionTypes = buildDetectionTypes(alertInfo.result(), config);

        String finalMessage = alertMessage
                .replace("%player%", alertInfo.username())
                .replace("%ip%", alertInfo.playerIP())
                .replace("%detection%", detectionTypes)
                .replace("%score%", String.valueOf(alertInfo.result().threatScore()))
                .replace("%time%", alertInfo.timestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss")));

        Component alertComponent = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(finalMessage);

        String alertPermission = config.getConfig().getString("alerts.permission", "mantivpn.alerts");

        proxyServer.getAllPlayers().stream()
                .filter(player -> player.hasPermission(alertPermission))
                .forEach(player -> player.sendMessage(alertComponent));

        if (config.getConfig().getBoolean("alerts.logging.log-to-console", true)) {
            log.warn("[ALERT] Player {} from IP {} triggered VPN/Proxy detection: {} (Score: {})",
                    alertInfo.username(), alertInfo.playerIP(), detectionTypes, alertInfo.result().threatScore());
        }
    }

    private void sendDiscordAlert(AlertInfo alertInfo, ConfigManager config) {
        try {
            CompletableFuture<Void> discordFuture = discordWebhookManager.sendAlertAsync(alertInfo);

            if (config.getDiscord().getBoolean("discord.timeout-enabled", true)) {
                int timeoutSeconds = config.getConfig().getInt("discord.timeout-seconds", 30);
                discordFuture.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .exceptionally(throwable -> {
                            if (config.getDiscord().getBoolean("discord.logging.error-timeout", true)) {
                                log.error("[Discord] Webhook timeout for player {}: {}",
                                        alertInfo.username(), throwable.getMessage());
                            }
                            return null;
                        });
            }

            if (config.getDiscord().getBoolean("discord.logging.debug-sent", false)) {
                discordFuture.thenRun(() -> {
                    log.debug("[Discord] Successfully queued webhook for player {}", alertInfo.username());
                });
            }

        } catch (Exception e) {
            if (config.getDiscord().getBoolean("discord.logging.error-send-failures", true)) {
                log.error("[Discord] Failed to queue webhook for player {}: {}",
                        alertInfo.username(), e.getMessage());
            }
        }
    }

    private String buildDetectionTypes(IPCheckResult result, ConfigManager config) {
        if (result == null) {
            return "Unknown";
        }

        StringBuilder detectedTypes = new StringBuilder();

        try {
            for (CheckType checkType : CheckType.values()) {
                switch (checkType) {
                    case VPN:
                        if (config.getConfig().getBoolean(checkType.getConfigKey()) && result.vpn()) {
                            detectedTypes.append(checkType.getDisplayName()).append(" ");
                        }
                        break;
                    case PROXY:
                        if (config.getConfig().getBoolean(checkType.getConfigKey()) && result.proxy()) {
                            detectedTypes.append(checkType.getDisplayName()).append(" ");
                        }
                        break;
                    case TOR:
                        if (config.getConfig().getBoolean(checkType.getConfigKey()) && result.tor()) {
                            detectedTypes.append(checkType.getDisplayName()).append(" ");
                        }
                        break;
                    case DATACENTER:
                        if (config.getConfig().getBoolean(checkType.getConfigKey()) && result.datacenter() && !result.residential()) {
                            detectedTypes.append(checkType.getDisplayName()).append(" ");
                        }
                        break;
                }
            }
        } catch (Exception e) {
            if (config.getConfig().getBoolean("alerts.logging.warn-detection-errors", true)) {
                log.warn("[!] Error building detection types: {}", e.getMessage());
            }
            return "Error";
        }

        String result_str = detectedTypes.toString().trim();
        return result_str.isEmpty() ? "Unknown" : result_str;
    }

    private boolean isExpired(AlertInfo alertInfo) {
        return alertInfo.timestamp().isBefore(LocalDateTime.now().minusMinutes(expireMinutes));
    }

    private void cleanupExpiredEntries() {
        try {
            alertCache.entrySet().removeIf(entry -> isExpired(entry.getValue()));

            ConfigManager config = MAntiVPN.getConfigManager();
            if (config.getConfig().getBoolean("alerts.logging.debug-cache-cleanup", false)) {
                log.debug("[!] Cleaned up expired alert cache entries. Current size: {}", alertCache.size());
            }
        } catch (Exception e) {
            log.error("[!] Error during alert cache cleanup: {}", e.getMessage());
        }
    }

    private void cleanupOldestEntries() {
        try {
            int entriesToRemove = Math.max(1, maxCacheSize / 10);

            alertCache.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getValue().timestamp()))
                    .limit(entriesToRemove)
                    .forEach(entry -> alertCache.remove(entry.getKey()));

            ConfigManager config = MAntiVPN.getConfigManager();
            if (config.getConfig().getBoolean("alerts.logging.debug-cache-cleanup", false)) {
                log.debug("[!] Removed {} oldest entries from alert cache. Current size: {}",
                        entriesToRemove, alertCache.size());
            }
        } catch (Exception e) {
            log.error("[!] Error during oldest entries cleanup: {}", e.getMessage());
        }
    }

    public void clearAlertCache() {
        alertCache.clear();
        ConfigManager config = MAntiVPN.getConfigManager();
        if (config.getConfig().getBoolean("alerts.logging.info-cache-operations", true)) {
            log.info("[!] Alert cache cleared");
        }
    }

    public long getAlertCacheSize() {
        return alertCache.size();
    }

    public void shutdown() {
        if (cleanupExecutor != null && !cleanupExecutor.isShutdown()) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (discordWebhookManager != null) {
            discordWebhookManager.shutdown();
        }
    }
}