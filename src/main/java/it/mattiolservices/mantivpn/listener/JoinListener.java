package it.mattiolservices.mantivpn.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import it.mattiolservices.mantivpn.MAntiVPN;
import it.mattiolservices.mantivpn.antivpn.core.IPCheckResult;
import it.mattiolservices.mantivpn.antivpn.type.CheckType;
import it.mattiolservices.mantivpn.config.ConfigManager;
import it.mattiolservices.mantivpn.utils.AntiVPNUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

public class JoinListener {

    private final Logger logger;

    public JoinListener(Logger logger) {
        this.logger = logger;
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        String player = event.getUsername();

        String playerIP = AntiVPNUtils.getPlayerIP(event);

        if (playerIP == null) {
            logger.warn("[!] Could not determine IP for player: " + player);
            return;
        }

       if (MAntiVPN.getConfigManager().getConfig().getStringList("whitelist").contains(playerIP)) {
            if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
                logger.info("[!] The player {} is whitelisted, bypassing check", player);
            }
           return;
        }

        IPCheckResult cachedResult = MAntiVPN.getInstance().getAntiVPNCache().getCachedResult(playerIP);
        if (cachedResult != null) {
            if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
                logger.info("[!] Using cached result for player {} (IP: {})", player, playerIP);
            }

            handleCheckResult(event, player, cachedResult);
            return;
        }

        if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
            logger.info("[!] Using cached result for player {} (IP: {})", player, playerIP);
        }

        CompletableFuture<IPCheckResult> checkFuture =
                MAntiVPN.getInstance().getAntiVPNManager().checkIPAsync(playerIP);

        checkFuture.whenComplete((result, throwable) -> {
            if (throwable != null) {

                if (MAntiVPN.getConfigManager().getConfig().getBoolean("antivpn.allow-on-error")) {
                    event.setResult(PreLoginEvent.PreLoginComponentResult.allowed());
                } else {
                    Component errorMessage = LegacyComponentSerializer.legacyAmpersand()
                            .deserialize(MAntiVPN.getConfigManager().getMessages().getString("General.error-message"));
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(errorMessage));
                }
                return;
            }

            MAntiVPN.getInstance().getAntiVPNCache().cacheResult(playerIP, result);

            handleCheckResult(event, player, result);
        });
    }

    private void handleCheckResult(PreLoginEvent event, String username, IPCheckResult result) {

        if (result.isSuspicious(MAntiVPN.getConfigManager())) {
            String reason = buildKickReason(result, MAntiVPN.getConfigManager());

            String rawMessage = MAntiVPN.getConfigManager().getMessages().getString("General.kick-message");
            String finalMessage = rawMessage.replace("%result%", reason);

            Component kickMessage = LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(finalMessage);

            if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
                logger.info("Denied player {} (Score: {}): {}", username, result.threatScore(), reason);
            }

            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(kickMessage));
        } else {
            if(MAntiVPN.getConfigManager().getConfig().getBoolean("Debug.enable")) {
                logger.info("Denied connection for player {} for VPN/Proxy usage: {}",
                        username, result.threatScore());
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
                case HIGH_RISK:
                    if (result.threatScore() >= config.getConfig().getDouble(checkType.getConfigKey())) {
                        detectedTypes.append(checkType.getDisplayName()).append(" ");
                    }
                    break;
            }
        }

        return detectedTypes.toString().trim();
    }

}