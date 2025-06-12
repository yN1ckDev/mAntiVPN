package it.mattiolservices.mantivpn;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import it.mattiolservices.mantivpn.antivpn.cache.AntiVPNCache;
import it.mattiolservices.mantivpn.antivpn.manager.AntiVPNManager;
import it.mattiolservices.mantivpn.commands.AntiVPNCMD;
import it.mattiolservices.mantivpn.config.ConfigManager;
import it.mattiolservices.mantivpn.listener.JoinListener;
import lombok.Getter;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "mantivpn",
        name = "mAntiVPN",
        version = "1.0.0",
        description = "A simple antivpn plugin that uses antivpn.cc system.",
        url = "www.mattiolservices.it | antivpn.cc",
        authors = {"Mattiol Digital Services"}
)

@Getter
public class MAntiVPN {

    @Inject
    @Getter
    private Logger logger;

    @Getter
    private final ProxyServer server;

    @Getter
    private final Path dataDirectory;

    @Getter
    private static MAntiVPN instance;

    @Getter
    private static final ConfigManager configManager = new ConfigManager();

    @Getter
    private AntiVPNManager antiVPNManager;
    private AntiVPNCache antiVPNCache;

    @Inject
    public MAntiVPN(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        instance = this;
        getLogger().info("\n" +
                " __    __     ______     __   __     ______   __     __   __   ______   __   __    \n" +
                "/\\ \"-./  \\   /\\  __ \\   /\\ \"-.\\ \\   /\\__  _\\ /\\ \\   /\\ \\ / /  /\\  == \\ /\\ \"-.\\ \\   \n" +
                "\\ \\ \\-./\\ \\  \\ \\  __ \\  \\ \\ \\-.  \\  \\/_/\\ \\/ \\ \\ \\  \\ \\ \\'/   \\ \\  _-/ \\ \\ \\-.  \\  \n" +
                " \\ \\_\\ \\ \\_\\  \\ \\_\\ \\_\\  \\ \\_\\\\\"\\_\\    \\ \\_\\  \\ \\_\\  \\ \\__|    \\ \\_\\    \\ \\_\\\\\"\\_\\ \n" +
                "  \\/_/  \\/_/   \\/_/\\/_/   \\/_/ \\/_/     \\/_/   \\/_/   \\/_/      \\/_/     \\/_/ \\/_/ \n" +
                "                                                                                   \n");

        getLogger().info("");
        getLogger().info("");
        getLogger().info("[/] Loading Config....");
        ConfigManager.load();
        getLogger().info("[/] Config Loaded!");
        getLogger().info("");
        getLogger().info("[/] Loading AntiVPN Cache...");
        this.antiVPNCache = new AntiVPNCache(configManager);
        getLogger().info("[/] AntiVPN Cache Loaded!");
        getLogger().info("");
        getLogger().info("[/] Loading AntiVPN Service...");
        this.antiVPNManager = new AntiVPNManager(configManager, logger);
        getLogger().info("[/] AntiVPN Service Loaded!");
        getLogger().info("");
        getLogger().info("[/] Registering Commands and Listeners.....");
        this.registerCommand("antivpn", new AntiVPNCMD(), "mantivpn");
        server.getEventManager().register(this, new JoinListener(logger));
        getLogger().info("[/] Commands and Listeners Registered!");

    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        getLogger().info("[!] Shutting down AntiVPN Cache");
        this.antiVPNCache.clearCache();
        this.antiVPNCache.shutdown();
        getLogger().info("Goodbye!");
    }


    public void registerCommand(String name, SimpleCommand command, String... aliases) {
        CommandManager commandManager = server.getCommandManager();

        commandManager.register(commandManager.metaBuilder(name).aliases(aliases).build(), command);
    }
}
