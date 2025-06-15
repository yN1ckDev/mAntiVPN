package it.mattiolservices.mantivpn.antivpn.core;

import it.mattiolservices.mantivpn.antivpn.type.CheckType;
import it.mattiolservices.mantivpn.config.ConfigManager;

public record IPCheckResult(
        String ip,
        boolean vpn,
        boolean proxy,
        boolean tor,
        boolean datacenter,
        boolean residential,
        double threatScore,
        String country,
        String provider,
        long timestamp
) {

    public IPCheckResult(String ip, boolean vpn, boolean proxy, boolean tor,
                         boolean datacenter, boolean residential, double threatScore,
                         String country, String provider) {
        this(ip, vpn, proxy, tor, datacenter, residential, threatScore,
                country, provider, System.currentTimeMillis());
    }

    public boolean isSuspicious(ConfigManager config) {
        for (CheckType checkType : CheckType.values()) {
            boolean checkEnabled = config.getConfig().getBoolean(checkType.getConfigKey());

            switch (checkType) {
                case VPN:
                    if (checkEnabled && vpn) {
                        return true;
                    }
                    break;
                case PROXY:
                    if (checkEnabled && proxy) {
                        return true;
                    }
                    break;
                case TOR:
                    if (checkEnabled && tor) {
                        return true;
                    }
                    break;
                case DATACENTER:
                    if (checkEnabled && datacenter && !residential) {
                        return true;
                    }
                    break;
                case HIGH_RISK:
                    double threshold = config.getConfig().getDouble(checkType.getConfigKey());
                    if (checkEnabled && threatScore > threshold) {
                        return true;
                    }
                    break;
            }
        }
        return false;
    }

}