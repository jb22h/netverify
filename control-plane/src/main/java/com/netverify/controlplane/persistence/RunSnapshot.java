package com.netverify.controlplane.persistence;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable latest-run projection for API composition.
 * Contains no authoritative test-definition fields.
 */
public record RunSnapshot(
        UUID runId,
        TestStatus status,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Integer exitCode,
        FailureCategory failureCategory,
        String stdout,
        String stderr,
        RunnerResultDto runnerResult
) {
}
