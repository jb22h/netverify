package com.netverify.controlplane.queue;

import java.util.List;

/**
 * Result of {@link RunQueue#claimStale(String, java.time.Duration, String, int)}.
 *
 * @param nextStartId continuation cursor for the next XAUTOCLAIM call
 * @param deliveries claimed messages with parseable payloads (deleted-entry ids omitted)
 */
public record AutoClaimBatch(String nextStartId, List<QueueDelivery> deliveries) {

    public AutoClaimBatch {
        if (nextStartId == null || nextStartId.isBlank()) {
            throw new IllegalArgumentException("nextStartId must not be blank");
        }
        if (deliveries == null) {
            throw new IllegalArgumentException("deliveries must not be null");
        }
        deliveries = List.copyOf(deliveries);
    }
}
