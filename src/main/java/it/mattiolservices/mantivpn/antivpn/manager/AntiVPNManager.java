package it.mattiolservices.mantivpn.antivpn.manager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import it.mattiolservices.mantivpn.MAntiVPN;
import it.mattiolservices.mantivpn.antivpn.core.IPCheckResult;
import it.mattiolservices.mantivpn.config.ConfigManager;
import okhttp3.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class AntiVPNManager {

    private final ConfigManager configManager;
    private final Logger logger;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public AntiVPNManager(ConfigManager configManager, Logger logger) {
        this.configManager = configManager;
        this.logger = logger;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(configManager.getConfig().getLong("antivpn.timeout"), TimeUnit.MILLISECONDS)
                .readTimeout(configManager.getConfig().getLong("antivpn.timeout"), TimeUnit.MILLISECONDS)
                .writeTimeout(configManager.getConfig().getLong("antivpn.timeout"), TimeUnit.MILLISECONDS)
                .build();
        this.gson = new Gson();
    }

    public CompletableFuture<IPCheckResult> checkIPAsync(String ip) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return checkIP(ip);
            } catch (Exception e) {
                logger.error("Failed to check IP: " + ip, e);
                return new IPCheckResult(ip, false, false, false,
                        false, true, 0.0, "unknown", "unknown");
            }
        });
    }

    private IPCheckResult checkIP(String ip) throws IOException {
        String apiKey = configManager.getConfig().getString("antivpn.apikey");
        if (apiKey.isEmpty() || "YOUR_API_KEY_HERE".equals(apiKey)) {
            logger.error("[!] The API key is not configured! Falling back to test mode.");
            return checkIPTest(ip);
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("ip", ip);

        Request request = new Request.Builder()
                .url("https://antivpn.cc/api/check")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json; charset=utf-8")))
                .build();

        return executeRequestWithRetry(request, ip, configManager.getConfig().getInt("antivpn.retries"));
    }

    private IPCheckResult checkIPTest(String ip) throws IOException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("ip", ip);

        Request request = new Request.Builder()
                .url("https://antivpn.cc/api/test-check")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json; charset=utf-8")))
                .build();

        return executeRequestWithRetry(request, ip, configManager.getConfig().getInt("antivpn.retries"));
    }

    private IPCheckResult executeRequestWithRetry(Request request, String ip, int maxRetries)
            throws IOException {

        IOException lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (Response response = httpClient.newCall(request).execute()) {

                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    return parseResponse(responseBody, ip);
                } else if (response.code() == 429) {
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(Math.min(1000L * attempt, 5000L));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Request interrupted", e);
                        }
                        continue;
                    }
                } else if (response.code() >= 500 && response.code() < 600) {
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(Math.min(1000L * attempt, 5000L));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Request interrupted", e);
                        }
                        continue;
                    }
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                throw new IOException("HTTP " + response.code() + ": " + responseBody);

            } catch (IOException e) {
                lastException = e;
                if (attempt < maxRetries) {
                    if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
                        logger.warn("[!] API request failed (attempt {}/{}): {}", attempt, maxRetries, e.getMessage());
                    }

                    try {
                        Thread.sleep(Math.min(1000L * attempt, 5000L));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Request interrupted", ie);
                    }
                } else {
                    if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
                        logger.error("[!] API request failed after {} attempts", maxRetries, e);
                    }
                }
            }
        }

        throw new IOException("All retry attempts failed", lastException);
    }

    private IPCheckResult parseResponse(String responseBody, String ip) {
        try {
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);

            boolean vpn = json.has("vpn") && json.get("vpn").getAsBoolean();
            boolean proxy = json.has("proxy") && json.get("proxy").getAsBoolean();
            boolean tor = json.has("tor") && json.get("tor").getAsBoolean();
            boolean datacenter = json.has("datacenter") && json.get("datacenter").getAsBoolean();
            boolean residential = json.has("residential") && json.get("residential").getAsBoolean();
            double threatScore = json.has("threat_score") ? json.get("threat_score").getAsDouble() : 0.0;
            String country = json.has("country") ? json.get("country").getAsString() : "unknown";
            String provider = json.has("provider") ? json.get("provider").getAsString() : "unknown";

            return new IPCheckResult(ip, vpn, proxy, tor, datacenter,
                    residential, threatScore, country, provider);

        } catch (Exception e) {
            logger.error("Failed to parse API response", e);
            return new IPCheckResult(ip, false, false, false,
                    false, true, 0.0, "unknown", "unknown");
        }
    }
}