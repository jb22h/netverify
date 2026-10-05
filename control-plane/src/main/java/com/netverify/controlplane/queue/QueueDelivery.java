package com.netverify.controlplane.queue;

import java.util.UUID;

/**
 * A consumer-group delivery from {@link RunQueue#read(String, int)}.
 * Redis-specific types do not appear here.
 */
public record QueueDelivery(String messageId, UUID runId, UUID testId) {

    public QueueDelivery {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (runId == null) {
            throw new IllegalArgumentException("runId must not be null");
        }
        if (testId == null) {
            throw new IllegalArgumentException("testId must not be null");
        }
    }
}
