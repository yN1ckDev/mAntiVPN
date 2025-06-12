package it.mattiolservices.mantivpn.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import it.mattiolservices.mantivpn.MAntiVPN;
import it.mattiolservices.mantivpn.utils.CC;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AntiVPNCMD implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (source.hasPermission("mantivpn.commands.reload")) {

                try {
                    MAntiVPN.getConfigManager().reload();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                source.sendMessage(CC.translate(MAntiVPN.getConfigManager().getMessages().getString("Commands.reloaded")));
            } else {
                source.sendMessage(CC.translate(MAntiVPN.getConfigManager().getMessages().getString("Commands.not-allowed")));
            }
        } else {
            source.sendMessage(CC.translate("&7This server is running &cmAntiVPN &7developed by &bMattiol Digital Services &8| &ehttps://www.mattiolservices.it"));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return SimpleCommand.super.suggest(invocation);
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        return SimpleCommand.super.suggestAsync(invocation);
    }
}
