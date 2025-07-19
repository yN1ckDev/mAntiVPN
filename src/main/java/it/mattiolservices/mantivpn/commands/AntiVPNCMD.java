package it.mattiolservices.mantivpn.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import it.mattiolservices.mantivpn.MAntiVPN;
import it.mattiolservices.mantivpn.alert.manager.AlertManager;
import it.mattiolservices.mantivpn.config.ConfigManager;
import it.mattiolservices.mantivpn.utils.CC;
import lombok.extern.slf4j.Slf4j;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.velocity.annotation.CommandPermission;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Command("antivpn")
@CommandPermission("mantivpn.admin")
@Description("Main AntiVPN command")
public class AntiVPNCMD {

    private final AlertManager alertManager;
    private final ConfigManager configManager;

    private final ConcurrentHashMap<String, Boolean> playerAlertPreferences;

    public AntiVPNCMD(AlertManager alertManager, ConfigManager configManager) {
        this.alertManager = alertManager;
        this.configManager = configManager;
        this.playerAlertPreferences = new ConcurrentHashMap<>();
    }

    @Subcommand("info")
    @Description("Shows main AntiVPN information")
    public void mainCommand(CommandSource sender) {

        sender.sendMessage(CC.translate("&b&l═══════════════════════════════════════"));
        sender.sendMessage(CC.translate(""));
        sender.sendMessage(CC.translate("&7Running &bmAntiVPN &8- &7The most &caccurate &7anti-vpn plugin!"));
        sender.sendMessage(CC.translate(""));
        sender.sendMessage(CC.translate("&b&l═══════════════════════════════════════"));
        sender.sendMessage(CC.translate(""));
        sender.sendMessage(CC.translate("&7Check out our website: &bhttps://www.mattiolservices.it"));
        sender.sendMessage(CC.translate(""));
        sender.sendMessage(CC.translate("&b&l═══════════════════════════════════════"));
        sender.sendMessage(CC.translate(""));
        sender.sendMessage(CC.translate("&b&lCommands:" ));
        sender.sendMessage(CC.translate("&7/antivpn info - Shows main AntiVPN information"));
        sender.sendMessage(CC.translate("&7/antivpn alerts - Toggle alert notifications for yourself"));
        sender.sendMessage(CC.translate("&7/antivpn cache clear - Clears the alert cache"));
        sender.sendMessage(CC.translate("&7/antivpn cache info - Shows cache information"));
        sender.sendMessage(CC.translate("&7/antivpn reload - Reloads the plugin configuration"));
        sender.sendMessage(CC.translate(""));
        sender.sendMessage(CC.translate("&b&l═══════════════════════════════════════"));

    }

    @Subcommand("alerts")
    @CommandPermission("mantivpn.alerts")
    @Description("Toggle alert notifications for yourself")
    public void toggleAlerts(CommandSource sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(CC.translate(MAntiVPN.getConfigManager().getMessages().getString("antivpn.alerts.player-only")));
            return;
        }

        String playerName = player.getUsername();
        boolean currentState = playerAlertPreferences.getOrDefault(playerName, true);
        boolean newState = !currentState;
        playerAlertPreferences.put(playerName, newState);

        String messageKey = newState ? "antivpn.alerts.toggled.enabled" : "antivpn.alerts.toggled.disabled";
        String rawMessage = MAntiVPN.getConfigManager().getMessages().getString(messageKey);

        player.sendMessage(CC.translate(rawMessage));

        log.info("Player {} {} alert notifications", playerName, newState ? "enabled" : "disabled");
    }

    @Subcommand("alerts enable")
    @CommandPermission("mantivpn.alerts.manage")
    @Description("Enable alerts for a specific player")
    public void enableAlerts(CommandSource sender, @Named("player") String targetPlayer) {
        playerAlertPreferences.put(targetPlayer, true);

        String rawMessage = MAntiVPN.getConfigManager().getMessages().getString("antivpn.alerts.manage.enabled");
        rawMessage = rawMessage.replace("%player%", targetPlayer);

        sender.sendMessage(CC.translate(rawMessage));
        log.info("Alerts enabled for player {} by {}", targetPlayer, getCommandSourceName(sender));
    }

    @Subcommand("alerts disable")
    @CommandPermission("mantivpn.alerts.manage")
    @Description("Disable alerts for a specific player")
    public void disableAlerts(CommandSource sender, @Named("player") String targetPlayer) {
        playerAlertPreferences.put(targetPlayer, false);

        String rawMessage = MAntiVPN.getConfigManager().getMessages().getString("antivpn.alerts.manage.disabled");
        rawMessage = rawMessage.replace("%player%", targetPlayer);

        sender.sendMessage(CC.translate(rawMessage));
        log.info("Alerts disabled for player {} by {}", targetPlayer, getCommandSourceName(sender));
    }

    @Subcommand("alerts status")
    @CommandPermission("mantivpn.alerts")
    @Description("Check your alert notification status")
    public void alertStatus(CommandSource sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(CC.translate(MAntiVPN.getConfigManager().getMessages().getString("antivpn.alerts.player-only")));
            return;
        }

        String playerName = player.getUsername();
        boolean alertsEnabled = playerAlertPreferences.getOrDefault(playerName, true);
        boolean globalAlertsEnabled = configManager.getConfig().getBoolean("alerts.enable", true);

        String statusHeader = MAntiVPN.getConfigManager().getMessages().getString("antivpn.alerts.status.header").replace("%player%", playerName);

        String personalMsg = alertsEnabled
                ? MAntiVPN.getConfigManager().getMessages().getString("antivpn.alerts.status.personal.enabled")
                : MAntiVPN.getConfigManager().getMessages().getString("antivpn.alerts.status.personal.disabled");

        String globalMsg = globalAlertsEnabled
                ? MAntiVPN.getConfigManager().getMessages().getString("antivpn.alerts.status.global.enabled")
                : MAntiVPN.getConfigManager().getMessages().getString("antivpn.alerts.status.global.disabled");

        player.sendMessage(CC.translate(statusHeader + "\n" + personalMsg + "\n" + globalMsg));
    }

    @Subcommand("reload")
    @CommandPermission("mantivpn.admin")
    @Description("Reload the plugin configuration")
    public void reloadConfig(CommandSource sender) {
        try {
            configManager.reload();
            String rawMessage = MAntiVPN.getConfigManager().getMessages().getString("antivpn.reload.success");
            sender.sendMessage(CC.translate(rawMessage));
            log.info("Configuration reloaded by {}", getCommandSourceName(sender));
        } catch (Exception e) {
            String errorTemplate = MAntiVPN.getConfigManager().getMessages().getString("antivpn.reload.failure");
            String errorMessage = errorTemplate.replace("%error%", e.getMessage());
            sender.sendMessage(CC.translate(errorMessage));
            log.error("Failed to reload configuration", e);
        }
    }

    @Subcommand("cache clear")
    @CommandPermission("mantivpn.admin")
    @Description("Clear the alert cache")
    public void clearCache(CommandSource sender) {
        long previousSize = alertManager.getAlertCacheSize();
        alertManager.clearAlertCache();

        String rawMessage = MAntiVPN.getConfigManager().getMessages().getString("antivpn.cache.clear")
                .replace("%amount%", String.valueOf(previousSize));

        sender.sendMessage(CC.translate(rawMessage));
        log.info("Alert cache cleared by {} ({} entries removed)", getCommandSourceName(sender), previousSize);
    }

    @Subcommand("cache info")
    @CommandPermission("mantivpn.admin")
    @Description("Show cache information")
    public void cacheInfo(CommandSource sender) {
        long cacheSize = alertManager.getAlertCacheSize();
        int maxSize = configManager.getConfig().getInt("alerts.cache.max-size", 1000);
        int expireMinutes = configManager.getConfig().getInt("alerts.cache.expire-minutes", 5);
        double usagePercent = ((double) cacheSize / maxSize) * 100.0;

        String header = MAntiVPN.getConfigManager().getMessages().getString("antivpn.cache.info.header");
        String currentSize = MAntiVPN.getConfigManager().getMessages().getString("antivpn.cache.info.current-size")
                .replace("%current%", String.valueOf(cacheSize))
                .replace("%max%", String.valueOf(maxSize));
        String expiry = MAntiVPN.getConfigManager().getMessages().getString("antivpn.cache.info.expiry")
                .replace("%minutes%", String.valueOf(expireMinutes));
        String usage = MAntiVPN.getConfigManager().getMessages().getString("antivpn.cache.info.usage")
                .replace("%usage%", String.format("%.1f", usagePercent));

        sender.sendMessage(CC.translate(header + "\n" + currentSize + "\n" + expiry + "\n" + usage));
    }



    private String getCommandSourceName(CommandSource source) {
        if (source instanceof Player player) {
            return player.getUsername();
        }
        return "Console";
    }

}