package com.netverify.controlplane.persistence;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.time.Instant;
import java.util.UUID;

/** Row model for the {@code test_runs} table (not a JPA entity). */
public record TestRunEntity(
        UUID id,
        UUID testId,
        TestStatus status,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Integer exitCode,
        FailureCategory failureCategory,
        String stdout,
        String stderr,
        RunnerResultDto runnerResult,
        String workerId,
        Instant leaseExpiresAt
) {
}
