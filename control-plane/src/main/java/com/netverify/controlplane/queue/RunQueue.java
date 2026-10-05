package com.netverify.controlplane.queue;

import java.util.List;
import java.util.UUID;

/**
 * Redis-independent queue primitives for Phase 10 M1.
 * No worker lifecycle, retries, or leases.
 */
public interface RunQueue {

    /**
     * Ensures the consumer group exists on the stream (creates stream if needed).
     * Idempotent when the group already exists.
     */
    void ensureConsumerGroup();

    /**
     * Publishes a run to the queue. Returns the broker message id.
     */
    String publish(UUID runId, UUID testId);

    /**
     * Reads new messages for the given consumer via consumer-group semantics.
     * Malformed payloads throw {@link MalformedQueueMessageException}.
     */
    List<QueueDelivery> read(String consumerName, int count);

    /**
     * Acknowledges a previously delivered message.
     *
     * @return {@code true} only when the broker confirms this message was acknowledged;
     *         {@code false} when no pending message was acknowledged
     */
    boolean acknowledge(String messageId);
}
