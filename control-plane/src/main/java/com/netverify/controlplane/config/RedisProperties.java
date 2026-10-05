package com.netverify.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis connection settings (active only under the {@code redis} profile).
 * Bound from {@code NETVERIFY_REDIS_*} environment variables via application-redis.yml.
 */
@ConfigurationProperties(prefix = "netverify.redis")
public class RedisProperties {

    private String host = "";

    private int port = 6379;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
