package com.netverify.controlplane.persistence.worker;

import com.netverify.controlplane.persistence.TerminalUpdate;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Distributed worker run ownership (PostgreSQL leases). Separate from HTTP
 * {@link com.netverify.controlplane.persistence.RunLifecycleStore}.
 */
public interface WorkerRunLifecycle {

    ClaimResult claimForExecution(
            UUID runId,
            UUID expectedTestId,
            String workerId,
            Duration leaseDuration);

    boolean renewLease(UUID runId, String workerId, Duration leaseDuration);

    boolean complete(UUID runId, String workerId, TerminalUpdate update, Instant finishedAt);

    ReclaimResult reclaimExpiredRunning(UUID runId);
}
