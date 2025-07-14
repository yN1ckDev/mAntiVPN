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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class DiscordWebhookManager {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService rateLimitExecutor;
    private final AtomicLong lastMessageTime;
    private final int rateLimitMs;

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
        this.lastMessageTime = new AtomicLong(0);

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
            if (config.getConfig().getBoolean("discord.logging.warn-missing-url", true)) {
                log.warn("[Discord] Webhook URL is not configured");
            }
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                long currentTime = System.currentTimeMillis();
                long timeSinceLastMessage = currentTime - lastMessageTime.get();

                if (timeSinceLastMessage < rateLimitMs) {
                    long delay = rateLimitMs - timeSinceLastMessage;
                    if (config.getDiscord().getBoolean("discord.logging.debug-rate-limit", false)) {
                        log.debug("[Discord] Rate limiting: waiting {}ms before sending message", delay);
                    }
                    Thread.sleep(delay);
                }

                lastMessageTime.set(System.currentTimeMillis());
                sendWebhook(webhookUrl, alertInfo);

            } catch (Exception e) {
                if (config.getDiscord().getBoolean("discord.logging.error-send-failures", true)) {
                    log.error("[Discord] Failed to send webhook for player {}: {}",
                            alertInfo.username(), e.getMessage());
                }
            }
        });
    }

    private void sendWebhook(String webhookUrl, AlertInfo alertInfo) throws IOException {
        ConfigManager config = MAntiVPN.getConfigManager();

        Map<String, Object> webhookPayload = createWebhookPayload(alertInfo, config);
        String jsonPayload = objectMapper.writeValueAsString(webhookPayload);

        RequestBody body = RequestBody.create(jsonPayload, MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(body)
                .addHeader("User-Agent", "MAntiVPN-Discord-Webhook/1.0")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                if (config.getDiscord().getBoolean("discord.logging.debug-success", false)) {
                    log.debug("[Discord] Successfully sent webhook for player {}", alertInfo.username());
                }
            } else {
                if (config.getDiscord().getBoolean("discord.logging.error-http-failures", true)) {
                    String responseBody = response.body() != null ? response.body().string() : "No response body";
                    log.error("[Discord] Webhook request failed with status {}: {}",
                            response.code(), responseBody);
                }
            }
        }
    }

    private Map<String, Object> createWebhookPayload(AlertInfo alertInfo, ConfigManager config) {
        Map<String, Object> payload = new HashMap<>();

        String username = config.getDiscord().getString("discord.bot-username", "MAntiVPN");
        String avatarUrl = config.getDiscord().getString("discord.bot-avatar-url", "");

        payload.put("username", username);
        if (!avatarUrl.isEmpty()) {
            payload.put("avatar_url", avatarUrl);
        }

        String plainContent = config.getDiscord().getString("discord.content", "");
        if (!plainContent.isEmpty()) {
            String processedContent = processPlaceholders(plainContent, alertInfo, config);
            payload.put("content", processedContent);
        }

        if (config.getDiscord().getBoolean("discord.embed.enabled", true)) {
            Map<String, Object> embed = createEmbed(alertInfo, config);
            payload.put("embeds", List.of(embed));
        }

        return payload;
    }

    private Map<String, Object> createEmbed(AlertInfo alertInfo, ConfigManager config) {
        Map<String, Object> embed = new HashMap<>();

        String title = config.getDiscord().getString("discord.embed.title", "🚨 VPN/Proxy Detection Alert");
        embed.put("title", processPlaceholders(title, alertInfo, config));

        String description = config.getDiscord().getString("discord.embed.description",
                "**Player:** %player%\n**IP:** %ip%\n**Detection:** %detection%\n**Score:** %score%\n**Time:** %time%");
        embed.put("description", processPlaceholders(description, alertInfo, config));

        embed.put("color", getEmbedColor(alertInfo.result(), config));

        if (config.getDiscord().getBoolean("discord.embed.timestamp", true)) {
            embed.put("timestamp", alertInfo.timestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z");
        }

        if (config.getDiscord().getBoolean("discord.embed.fields.enabled", false)) {
            embed.put("fields", createEmbedFields(alertInfo, config));
        }

        if (config.getDiscord().getBoolean("discord.embed.author.enabled", false)) {
            Map<String, Object> author = new HashMap<>();
            String authorName = config.getDiscord().getString("discord.embed.author.name", "MAntiVPN");
            author.put("name", processPlaceholders(authorName, alertInfo, config));

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
            footer.put("text", processPlaceholders(footerText, alertInfo, config));

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

    private List<Map<String, Object>> createEmbedFields(AlertInfo alertInfo, ConfigManager config) {
        List<Map<String, Object>> fields = new ArrayList<>();

        List<String> fieldKeys = config.getDiscord().getStringList("discord.embed.fields.custom");

        if (fieldKeys.isEmpty()) {
            fields.add(createField("Player", alertInfo.username(), true));
            fields.add(createField("IP Address", alertInfo.playerIP(), true));
            fields.add(createField("Detection", buildDetectionTypes(alertInfo.result(), config), true));
            fields.add(createField("Score", String.valueOf(alertInfo.result().threatScore()), true));
            fields.add(createField("Time", alertInfo.timestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss")), true));
        } else {
            for (String fieldKey : fieldKeys) {
                String fieldName = config.getDiscord().getString("discord.embed.fields." + fieldKey + ".name", fieldKey);
                String fieldValue = config.getDiscord().getString("discord.embed.fields." + fieldKey + ".value", "N/A");
                boolean fieldInline = config.getDiscord().getBoolean("discord.embed.fields." + fieldKey + ".inline", true);

                fields.add(createField(
                        processPlaceholders(fieldName, alertInfo, config),
                        processPlaceholders(fieldValue, alertInfo, config),
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

    private String processPlaceholders(String text, AlertInfo alertInfo, ConfigManager config) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text
                .replace("%player%", alertInfo.username())
                .replace("%ip%", alertInfo.playerIP())
                .replace("%detection%", buildDetectionTypes(alertInfo.result(), config))
                .replace("%score%", String.valueOf(alertInfo.result().threatScore()))
                .replace("%time%", alertInfo.timestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                .replace("%date%", alertInfo.timestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .replace("%datetime%", alertInfo.timestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .replace("%timestamp%", String.valueOf(alertInfo.timestamp().toEpochSecond(java.time.ZoneOffset.UTC)));

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

    private int getEmbedColor(IPCheckResult result, ConfigManager config) {
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

    private String buildDetectionTypes(IPCheckResult result, ConfigManager config) {
        if (result == null) {
            return "Unknown";
        }

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
            if (config.getDiscord().getBoolean("discord.logging.warn-detection-errors", true)) {
                log.warn("[Discord] Error building detection types: {}", e.getMessage());
            }
            return "Error";
        }

        if (detections.isEmpty()) {
            return "Unknown";
        }

        String separator = config.getDiscord().getString("discord.detection-separator", ", ");
        return String.join(separator, detections);
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