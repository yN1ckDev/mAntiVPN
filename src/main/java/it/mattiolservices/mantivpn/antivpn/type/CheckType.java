package it.mattiolservices.mantivpn.antivpn.type;

import lombok.Getter;

@Getter
public enum CheckType {
    VPN("Checks.vpn", "VPN"),
    PROXY("Checks.proxy", "Proxy"),
    TOR("Checks.tor", "Tor"),
    DATACENTER("Checks.datacenter", "Datacenter"),
    HIGH_RISK("Checks.high-risk", "Risk Threshold");
    private final String configKey;
    private final String displayName;

    CheckType(String configKey, String displayName) {
        this.configKey = configKey;
        this.displayName = displayName;
    }

}