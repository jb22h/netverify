package com.netverify.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PostgreSQL connection settings (active only under the {@code postgres} profile).
 * Bound from {@code NETVERIFY_DB_*} environment variables via application-postgres.yml.
 */
@ConfigurationProperties(prefix = "netverify.db")
public class PostgresProperties {

    private String url = "";
    private String user = "";
    private String password = "";

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
