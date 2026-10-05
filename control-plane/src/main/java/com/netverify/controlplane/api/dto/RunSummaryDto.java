package com.netverify.controlplane.api.dto;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.persistence.RunSnapshot;
import java.time.Instant;
import java.util.UUID;

/**
 * Compact history entry for {@code GET /api/tests/{id}/runs}.
 *
 * <p>Exposes the actual run UUID. {@code POST /api/tests/{id}/run} remains
 * backward-compatible and returns the test id, not the run id.
 *
 * <p>Omits stdout/stderr/runnerResult — those stay on latest-only detail endpoints.
 */
public record RunSummaryDto(
        UUID id,
        UUID testId,
        TestStatus status,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Integer exitCode,
        FailureCategory failureCategory
) {

    public static RunSummaryDto from(UUID testId, RunSnapshot snapshot) {
        return new RunSummaryDto(
                snapshot.runId(),
                testId,
                snapshot.status(),
                snapshot.queuedAt(),
                snapshot.startedAt(),
                snapshot.finishedAt(),
                snapshot.exitCode(),
                snapshot.failureCategory()
        );
    }
}
