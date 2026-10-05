package com.netverify.controlplane.queue;

import java.util.UUID;

/**
 * Immutable Redis Streams payload for a queued run.
 * PostgreSQL remains authoritative for scenario/config; the queue carries IDs only.
 */
public record RunQueueMessage(UUID runId, UUID testId) {

    public static final String FIELD_RUN_ID = "runId";
    public static final String FIELD_TEST_ID = "testId";

    public RunQueueMessage {
        if (runId == null) {
            throw new IllegalArgumentException("runId must not be null");
        }
        if (testId == null) {
            throw new IllegalArgumentException("testId must not be null");
        }
    }
}
