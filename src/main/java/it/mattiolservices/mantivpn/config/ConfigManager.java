package it.mattiolservices.mantivpn.config;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import it.mattiolservices.mantivpn.MAntiVPN;
import lombok.Getter;

import java.io.File;
import java.io.IOException;

@Getter
public class ConfigManager {
    private YamlDocument messages, config, alerts, discord;

    public void loadMessages() {
        try {
            messages = YamlDocument.create(
                    new File(MAntiVPN.getInstance().getDataDirectory().toFile(), "messages.yml"),
                    getClass().getResourceAsStream("/messages.yml"),
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT,
                    UpdaterSettings.builder().setVersioning(new BasicVersioning("config-version")).build()
            );
            messages.update();
            messages.save();
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    public void loadConfig() {
        try {
            config = YamlDocument.create(
                    new File(MAntiVPN.getInstance().getDataDirectory().toFile(), "config.yml"),
                    getClass().getResourceAsStream("/config.yml"),
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT,
                    UpdaterSettings.builder().setVersioning(new BasicVersioning("config-version")).build()
            );
            config.update();
            config.save();
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    public void loadAlerts() {
        try {
            alerts = YamlDocument.create(
                    new File(MAntiVPN.getInstance().getDataDirectory().toFile(), "alerts.yml"),
                    getClass().getResourceAsStream("/alerts.yml"),
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT,
                    UpdaterSettings.builder().setVersioning(new BasicVersioning("config-version")).build()
            );
            alerts.update();
            alerts.save();
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    public void loadDiscord() {
        try {
            discord = YamlDocument.create(
                    new File(MAntiVPN.getInstance().getDataDirectory().toFile(), "discord.yml"),
                    getClass().getResourceAsStream("/discord.yml"),
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT,
                    UpdaterSettings.builder().setVersioning(new BasicVersioning("config-version")).build()
            );
            discord.update();
            discord.save();
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }


    public static void load() {
        MAntiVPN.getConfigManager().loadConfig();
        MAntiVPN.getConfigManager().loadMessages();
        MAntiVPN.getConfigManager().loadAlerts();
        MAntiVPN.getConfigManager().loadDiscord();
    }

    public void reload() throws IOException {
        messages.reload();
        config.reload();
        alerts.reload();
        discord.reload();
    }
}