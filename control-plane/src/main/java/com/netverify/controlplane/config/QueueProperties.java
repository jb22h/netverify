package com.netverify.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis Streams queue defaults (active under the {@code redis} profile).
 */
@ConfigurationProperties(prefix = "netverify.queue")
public class QueueProperties {

    private String stream = "netverify:runs";

    private String group = "netverify-workers";

    /** Blocking XREADGROUP wait in seconds (worker runtime). */
    private int readBlockSeconds = 2;

    /**
     * Worker XREADGROUP / XAUTOCLAIM batch size.
     * Fixed to {@code 1} in M3 for poison isolation.
     */
    private int readBatchSize = 1;

    /**
     * Minimum idle time before XAUTOCLAIM may reclaim a pending message.
     * Default is 150s, intentionally greater than the 120s run lease horizon.
     */
    private int claimMinIdleSeconds = 150;

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

    public int getReadBlockSeconds() {
        return readBlockSeconds;
    }

    public void setReadBlockSeconds(int readBlockSeconds) {
        this.readBlockSeconds = readBlockSeconds;
    }

    public int getReadBatchSize() {
        return readBatchSize;
    }

    public void setReadBatchSize(int readBatchSize) {
        this.readBatchSize = readBatchSize;
    }

    public int getClaimMinIdleSeconds() {
        return claimMinIdleSeconds;
    }

    public void setClaimMinIdleSeconds(int claimMinIdleSeconds) {
        this.claimMinIdleSeconds = claimMinIdleSeconds;
    }

    /**
     * Fail-fast checks for worker-facing queue tuning.
     * Stream/group blank checks remain at {@code RedisRunQueue} construction.
     */
    public void validate() {
        if (readBlockSeconds < 1) {
            throw new IllegalArgumentException("netverify.queue.read-block-seconds must be >= 1");
        }
        if (readBatchSize != 1) {
            throw new IllegalArgumentException("netverify.queue.read-batch-size must be == 1");
        }
        if (claimMinIdleSeconds < 1) {
            throw new IllegalArgumentException("netverify.queue.claim-min-idle-seconds must be >= 1");
        }
    }
}
