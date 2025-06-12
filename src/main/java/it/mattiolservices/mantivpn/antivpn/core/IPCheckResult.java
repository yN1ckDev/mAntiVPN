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
            switch (checkType) {
                case VPN:
                    if (config.getConfig().getBoolean("checks." + checkType.getConfigKey()) && vpn) {
                        return true;
                    }
                    break;
                case PROXY:
                    if (config.getConfig().getBoolean("checks." + checkType.getConfigKey()) && proxy) {
                        return true;
                    }
                    break;
                case TOR:
                    if (config.getConfig().getBoolean("checks." + checkType.getConfigKey()) && tor) {
                        return true;
                    }
                    break;
                case DATACENTER:
                    if (config.getConfig().getBoolean("checks." + checkType.getConfigKey()) && datacenter && !residential) {
                        return true;
                    }
                    break;
                case HIGH_RISK:
                    double threshold = config.getConfig().getDouble("checks." + checkType.getConfigKey());
                    if (threatScore >= threshold) {
                        return true;
                    }
                    break;
            }
        }
        return false;
    }

}