package com.netverify.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis Streams queue defaults (active under the {@code redis} profile).
 */
@ConfigurationProperties(prefix = "netverify.queue")
public class QueueProperties {

    private String stream = "netverify:runs";

    private String group = "netverify-workers";

    public String getStream() {
        return stream;
    }

    public void setStream(String stream) {
        this.stream = stream;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }
}
