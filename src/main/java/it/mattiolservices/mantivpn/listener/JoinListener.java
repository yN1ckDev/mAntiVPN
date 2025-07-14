package it.mattiolservices.mantivpn.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import it.mattiolservices.mantivpn.MAntiVPN;
import it.mattiolservices.mantivpn.alert.manager.AlertManager;
import it.mattiolservices.mantivpn.antivpn.core.IPCheckResult;
import it.mattiolservices.mantivpn.antivpn.type.CheckType;
import it.mattiolservices.mantivpn.config.ConfigManager;
import it.mattiolservices.mantivpn.utils.AntiVPNUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.concurrent.CompletableFuture;

@Slf4j
@RequiredArgsConstructor
public class JoinListener {

    private final AlertManager alertManager;

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        String player = event.getUsername();

        String playerIP = AntiVPNUtils.getPlayerIP(event);

        if (playerIP == null) {
            log.warn("[!] Could not determine IP for player: {}", player);
            return;
        }

        if (MAntiVPN.getConfigManager().getConfig().getStringList("whitelist").contains(playerIP)) {
            if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
                log.info("[!] The player {} is whitelisted, bypassing check", player);
            }
            return;
        }

        IPCheckResult cachedResult = MAntiVPN.getInstance().getAntiVPNCache().getCachedResult(playerIP);
        if (cachedResult != null) {
            if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
                log.info("[!] Using cached result for player {} (IP: {})", player, playerIP);
            }

            handleCheckResult(event, player, playerIP, cachedResult);
            return;
        }

        if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
            log.info("[!] No cached result found for player {} (IP: {}), performing async check", player, playerIP);
        }

        CompletableFuture<IPCheckResult> checkFuture =
                MAntiVPN.getInstance().getAntiVPNManager().checkIPAsync(playerIP);

        try {
            IPCheckResult result = checkFuture.get();

            if (!result.isSuspicious(MAntiVPN.getConfigManager())) {
                MAntiVPN.getInstance().getAntiVPNCache().cacheResult(playerIP, result);

                if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
                    log.info("[!] Cached clean result for IP: {}", playerIP);
                }
            } else {
                if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
                    log.info("[!] Not caching suspicious result for IP: {}", playerIP);
                }
            }

            handleCheckResult(event, player, playerIP, result);

        } catch (Exception e) {
            log.error("[!] Failed to get async result for player {}: {}", player, e.getMessage());

            if (MAntiVPN.getConfigManager().getConfig().getBoolean("antivpn.allow-on-error")) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.allowed());
            } else {
                Component errorMessage = LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(MAntiVPN.getConfigManager().getMessages().getString("General.error-message"));
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(errorMessage));
            }
        }
    }

    private void handleCheckResult(PreLoginEvent event, String username, String playerIP, IPCheckResult result) {
        if (result.isSuspicious(MAntiVPN.getConfigManager())) {
            String reason = buildKickReason(result, MAntiVPN.getConfigManager());

            String rawMessage = MAntiVPN.getConfigManager().getMessages().getString("General.kick-message");
            String finalMessage = rawMessage.replace("%result%", reason);

            Component kickMessage = LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(finalMessage);

            if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
                log.info("Denied player {} (Score: {}): {}", username, result.threatScore(), reason);
            }

            alertManager.sendAlert(username, playerIP, result);

            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(kickMessage));
        } else {
            if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
                log.info("Allowed connection for player {} (Score: {})", username, result.threatScore());
            }

            event.setResult(PreLoginEvent.PreLoginComponentResult.allowed());
        }
    }

    public String buildKickReason(IPCheckResult result, ConfigManager config) {
        StringBuilder detectedTypes = new StringBuilder();

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

        return detectedTypes.toString().trim();
    }
}