package it.mattiolservices.mantivpn.utils;

import com.velocitypowered.api.event.connection.PreLoginEvent;

import java.net.InetSocketAddress;

public class AntiVPNUtils {

    public static String getPlayerIP(PreLoginEvent event) {
        if (event.getConnection().getRemoteAddress() != null) {
            InetSocketAddress address = event.getConnection().getRemoteAddress();
            return address.getAddress().getHostAddress();
        }
        return null;
    }
}
