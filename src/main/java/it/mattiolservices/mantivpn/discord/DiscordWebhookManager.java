package it.mattiolservices.mantivpn.discord;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.mattiolservices.mantivpn.MAntiVPN;
import it.mattiolservices.mantivpn.alert.info.AlertInfo;
import it.mattiolservices.mantivpn.antivpn.core.IPCheckResult;
import it.mattiolservices.mantivpn.antivpn.type.CheckType;
import it.mattiolservices.mantivpn.config.ConfigManager;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class DiscordWebhookManager {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService rateLimitExecutor;
    private volatile LocalDateTime lastMessageTime;
    private final int rateLimitMs;
    private final Map<String, LocalDateTime> playerAlertHistory;

    public DiscordWebhookManager() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        this.objectMapper = new ObjectMapper();
        this.rateLimitExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DiscordWebhook-RateLimit");
            t.setDaemon(true);
            return t;
        });
        this.lastMessageTime = LocalDateTime.now().minusHours(1);
        this.playerAlertHistory = new ConcurrentHashMap<>();

        ConfigManager config = MAntiVPN.getConfigManager();
        this.rateLimitMs = config.getDiscord().getInt("discord.rate-limit-ms", 2000);
    }

    public CompletableFuture<Void> sendAlertAsync(AlertInfo alertInfo) {
        ConfigManager config = MAntiVPN.getConfigManager();

        if (!config.getDiscord().getBoolean("discord.enabled", false)) {
            return CompletableFuture.completedFuture(null);
        }

        String webhookUrl = config.getDiscord().getString("discord.webhook-url", "");
        if (webhookUrl.isEmpty()) {
            log.warn("[Discord] Webhook URL is not configured");
            return CompletableFuture.completedFuture(null);
        }

        if (!shouldSendAlert(alertInfo)) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                synchronized (this) {
                    LocalDateTime currentTime = LocalDateTime.now();
                    long timeSinceLastMessage = ChronoUnit.MILLIS.between(lastMessageTime, currentTime);

                    if (timeSinceLastMessage < rateLimitMs) {
                        long delay = rateLimitMs - timeSinceLastMessage;
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }

                    boolean success = sendWebhook(webhookUrl, alertInfo);
                    if (success) {
                        lastMessageTime = LocalDateTime.now();
                        ConfigManager config2 = MAntiVPN.getConfigManager();
                        int cooldownMinutes = config2.getDiscord().getInt("discord.player-alert-cooldown-minutes", 0);
                        if (cooldownMinutes > 0) {
                            playerAlertHistory.put(alertInfo.username(), LocalDateTime.now());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[Discord] Failed to send webhook for player {}: {}",
                        alertInfo.username(), e.getMessage());
            }
        }, rateLimitExecutor);
    }

    private boolean sendWebhook(String webhookUrl, AlertInfo alertInfo) {
        try {
            Map<String, Object> webhookPayload = createWebhookPayload(alertInfo);
            String jsonPayload = objectMapper.writeValueAsString(webhookPayload);

            RequestBody body = RequestBody.create(jsonPayload, MediaType.get("application/json"));
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .addHeader("User-Agent", "MAntiVPN-Discord-Webhook/1.0")
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return true;
                } else {
                    String responseBody = response.body() != null ? response.body().string() : "No response body";
                    log.error("[Discord] Webhook request failed with status {}: {}",
                            response.code(), responseBody);
                    return false;
                }
            }
        } catch (IOException e) {
            log.error("[Discord] IOException while sending webhook: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("[Discord] Unexpected error while sending webhook: {}", e.getMessage());
            return false;
        }
    }

    private Map<String, Object> createWebhookPayload(AlertInfo alertInfo) {
        ConfigManager config = MAntiVPN.getConfigManager();
        Map<String, Object> payload = new HashMap<>();

        String username = config.getDiscord().getString("discord.bot-username", "MAntiVPN");
        String avatarUrl = config.getDiscord().getString("discord.bot-avatar-url", "");

        payload.put("username", username);
        if (!avatarUrl.isEmpty()) {
            payload.put("avatar_url", avatarUrl);
        }

        String plainContent = config.getDiscord().getString("discord.content", "");
        if (!plainContent.isEmpty()) {
            String processedContent = processPlaceholders(plainContent, alertInfo);
            payload.put("content", processedContent);
        }

        if (config.getDiscord().getBoolean("discord.embed.enabled", true)) {
            Map<String, Object> embed = createEmbed(alertInfo);
            payload.put("embeds", List.of(embed));
        }

        return payload;
    }

    private Map<String, Object> createEmbed(AlertInfo alertInfo) {
        ConfigManager config = MAntiVPN.getConfigManager();
        Map<String, Object> embed = new HashMap<>();

        String title = config.getDiscord().getString("discord.embed.title", "🚨 VPN/Proxy Detection Alert");
        embed.put("title", processPlaceholders(title, alertInfo));

        String description = config.getDiscord().getString("discord.embed.description",
                "**Player:** %player%\n**IP:** %ip%\n**Detection:** %detection%\n**Score:** %score%\n**Time:** %time%");
        embed.put("description", processPlaceholders(description, alertInfo));

        embed.put("color", getEmbedColor(alertInfo.result()));

        if (config.getDiscord().getBoolean("discord.embed.timestamp", true)) {
            embed.put("timestamp", alertInfo.timestamp().atZone(java.time.ZoneId.systemDefault()).toInstant().toString());
        }

        if (config.getDiscord().getBoolean("discord.embed.fields.enabled", false)) {
            embed.put("fields", createEmbedFields(alertInfo));
        }

        if (config.getDiscord().getBoolean("discord.embed.author.enabled", false)) {
            Map<String, Object> author = new HashMap<>();
            String authorName = config.getDiscord().getString("discord.embed.author.name", "MAntiVPN");
            author.put("name", processPlaceholders(authorName, alertInfo));

            String authorIcon = config.getDiscord().getString("discord.embed.author.icon", "");
            if (!authorIcon.isEmpty()) {
                author.put("icon_url", authorIcon);
            }

            String authorUrl = config.getDiscord().getString("discord.embed.author.url", "");
            if (!authorUrl.isEmpty()) {
                author.put("url", authorUrl);
            }

            embed.put("author", author);
        }

        if (config.getDiscord().getBoolean("discord.embed.footer.enabled", true)) {
            Map<String, Object> footer = new HashMap<>();
            String footerText = config.getDiscord().getString("discord.embed.footer.text", "MAntiVPN Alert System");
            footer.put("text", processPlaceholders(footerText, alertInfo));

            String footerIcon = config.getDiscord().getString("discord.embed.footer.icon", "");
            if (!footerIcon.isEmpty()) {
                footer.put("icon_url", footerIcon);
            }

            embed.put("footer", footer);
        }

        String thumbnailUrl = config.getDiscord().getString("discord.embed.thumbnail", "");
        if (!thumbnailUrl.isEmpty()) {
            Map<String, Object> thumbnail = new HashMap<>();
            thumbnail.put("url", thumbnailUrl);
            embed.put("thumbnail", thumbnail);
        }

        String imageUrl = config.getDiscord().getString("discord.embed.image", "");
        if (!imageUrl.isEmpty()) {
            Map<String, Object> image = new HashMap<>();
            image.put("url", imageUrl);
            embed.put("image", image);
        }

        return embed;
    }

    private List<Map<String, Object>> createEmbedFields(AlertInfo alertInfo) {
        ConfigManager config = MAntiVPN.getConfigManager();
        List<Map<String, Object>> fields = new ArrayList<>();

        List<String> fieldKeys = config.getDiscord().getStringList("discord.embed.fields.custom");
        String timeFormatted = processPlaceholders("%time%", alertInfo);

        if (fieldKeys.isEmpty()) {
            fields.add(createField("Player", alertInfo.username(), true));
            fields.add(createField("IP Address", alertInfo.playerIP(), true));
            fields.add(createField("Detection", buildDetectionTypes(alertInfo.result()), true));
            fields.add(createField("Score", String.valueOf(alertInfo.result().threatScore()), true));
            fields.add(createField("Time", timeFormatted, true));
        } else {
            for (String fieldKey : fieldKeys) {
                String fieldName = config.getDiscord().getString("discord.embed.fields." + fieldKey + ".name", fieldKey);
                String fieldValue = config.getDiscord().getString("discord.embed.fields." + fieldKey + ".value", "N/A");
                boolean fieldInline = config.getDiscord().getBoolean("discord.embed.fields." + fieldKey + ".inline", true);

                fields.add(createField(
                        processPlaceholders(fieldName, alertInfo),
                        processPlaceholders(fieldValue, alertInfo),
                        fieldInline
                ));
            }
        }

        return fields;
    }

    private Map<String, Object> createField(String name, String value, boolean inline) {
        Map<String, Object> field = new HashMap<>();
        field.put("name", name);
        field.put("value", value);
        field.put("inline", inline);
        return field;
    }

    private String processPlaceholders(String text, AlertInfo alertInfo) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        java.time.ZonedDateTime zonedDateTime = alertInfo.timestamp().atZone(java.time.ZoneId.systemDefault());

        String result = text
                .replace("%player%", alertInfo.username())
                .replace("%ip%", alertInfo.playerIP())
                .replace("%detection%", buildDetectionTypes(alertInfo.result()))
                .replace("%score%", String.valueOf(alertInfo.result().threatScore()))
                .replace("%time%", zonedDateTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                .replace("%date%", zonedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .replace("%datetime%", zonedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .replace("%timestamp%", String.valueOf(zonedDateTime.toEpochSecond()));

        IPCheckResult ipResult = alertInfo.result();
        if (ipResult != null) {
            result = result
                    .replace("%vpn%", String.valueOf(ipResult.vpn()))
                    .replace("%proxy%", String.valueOf(ipResult.proxy()))
                    .replace("%tor%", String.valueOf(ipResult.tor()))
                    .replace("%datacenter%", String.valueOf(ipResult.datacenter()))
                    .replace("%residential%", String.valueOf(ipResult.residential()));
        }

        result = result
                .replace("&0", "```diff\n-")
                .replace("&1", "```css\n")
                .replace("&2", "```css\n")
                .replace("&3", "```css\n")
                .replace("&4", "```diff\n-")
                .replace("&5", "```css\n")
                .replace("&6", "```fix\n")
                .replace("&7", "```")
                .replace("&8", "```")
                .replace("&9", "```css\n")
                .replace("&a", "```diff\n+")
                .replace("&b", "```css\n")
                .replace("&c", "```diff\n-")
                .replace("&d", "```css\n")
                .replace("&e", "```fix\n")
                .replace("&f", "```")
                .replace("&r", "```");

        return result;
    }

    private int getEmbedColor(IPCheckResult result) {
        ConfigManager config = MAntiVPN.getConfigManager();
        double score = result.threatScore();

        if (score >= config.getDiscord().getInt("discord.colors.high-threshold", 90)) {
            return config.getDiscord().getInt("discord.colors.high-threat", 0xFF0000);
        } else if (score >= config.getDiscord().getInt("discord.colors.medium-threshold", 70)) {
            return config.getDiscord().getInt("discord.colors.medium-threat", 0xFF8C00);
        } else if (score >= config.getDiscord().getInt("discord.colors.low-threshold", 50)) {
            return config.getDiscord().getInt("discord.colors.low-threat", 0xFFFF00);
        } else {
            return config.getDiscord().getInt("discord.colors.info-threat", 0x00FF00);
        }
    }

    private String buildDetectionTypes(IPCheckResult result) {
        if (result == null) {
            return "Unknown";
        }

        ConfigManager config = MAntiVPN.getConfigManager();
        List<String> detections = new ArrayList<>();

        try {
            for (CheckType checkType : CheckType.values()) {
                switch (checkType) {
                    case VPN:
                        if (config.getConfig().getBoolean(checkType.getConfigKey()) && result.vpn()) {
                            detections.add(checkType.getDisplayName());
                        }
                        break;
                    case PROXY:
                        if (config.getConfig().getBoolean(checkType.getConfigKey()) && result.proxy()) {
                            detections.add(checkType.getDisplayName());
                        }
                        break;
                    case TOR:
                        if (config.getConfig().getBoolean(checkType.getConfigKey()) && result.tor()) {
                            detections.add(checkType.getDisplayName());
                        }
                        break;
                    case DATACENTER:
                        if (config.getConfig().getBoolean(checkType.getConfigKey()) && result.datacenter() && !result.residential()) {
                            detections.add(checkType.getDisplayName());
                        }
                        break;
                }
            }
        } catch (Exception e) {
            log.warn("[Discord] Error building detection types: {}", e.getMessage());
            return "Error";
        }

        if (detections.isEmpty()) {
            return "Unknown";
        }

        String separator = config.getDiscord().getString("discord.detection-separator", ", ");
        return String.join(separator, detections);
    }

    private boolean shouldSendAlert(AlertInfo alertInfo) {
        ConfigManager config = MAntiVPN.getConfigManager();
        String username = alertInfo.username();

        int cooldownMinutes = config.getDiscord().getInt("discord.player-alert-cooldown-minutes", 0);

        if (cooldownMinutes <= 0) {
            return true;
        }

        LocalDateTime lastAlertTime = playerAlertHistory.get(username);
        if (lastAlertTime == null) {
            return true;
        }

        LocalDateTime currentTime = LocalDateTime.now();
        long minutesSinceLastAlert = ChronoUnit.MINUTES.between(lastAlertTime, currentTime);

        return minutesSinceLastAlert >= cooldownMinutes;
    }

    public void shutdown() {
        if (rateLimitExecutor != null && !rateLimitExecutor.isShutdown()) {
            rateLimitExecutor.shutdown();
            try {
                if (!rateLimitExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    rateLimitExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                rateLimitExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }
}