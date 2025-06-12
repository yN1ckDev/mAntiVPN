package it.mattiolservices.mantivpn.antivpn.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import it.mattiolservices.mantivpn.antivpn.core.IPCheckResult;
import it.mattiolservices.mantivpn.config.ConfigManager;

import java.time.Duration;

public class AntiVPNCache {

    private final Cache<String, IPCheckResult> ipCache;

    public AntiVPNCache(ConfigManager configManager) {
        this.ipCache = Caffeine.newBuilder()
                .maximumSize(configManager.getConfig().getInt("antivpn.cachesize"))
                .expireAfterWrite(Duration.ofHours(configManager.getConfig().getInt("antivpn.cachetime")))
                .build();
    }

    public IPCheckResult getCachedResult(String ip) {
        return ipCache.getIfPresent(ip);
    }

    public void cacheResult(String ip, IPCheckResult result) {
        ipCache.put(ip, result);
    }

    public boolean isCached(String ip) {
        return ipCache.getIfPresent(ip) != null;
    }

    public void invalidateCache(String ip) {
        ipCache.invalidate(ip);
    }

    public void clearCache() {
        ipCache.invalidateAll();
    }

    public long getCacheSize() {
        return ipCache.estimatedSize();
    }

    public void shutdown() {
        ipCache.invalidateAll();
    }
}