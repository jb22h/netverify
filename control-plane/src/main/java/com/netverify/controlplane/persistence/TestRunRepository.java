package com.netverify.controlplane.persistence;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TestRunRepository {

    void insert(TestRunEntity run);

    Optional<TestRunEntity> findById(UUID id);

    /**
     * Insert one QUEUED row. Returns 1 if inserted, 0 if the partial unique
     * active-run index blocked the insert ({@code ON CONFLICT DO NOTHING}).
     * Other DB errors (FK, etc.) must propagate.
     */
    int tryInsertQueued(UUID runId, UUID testId, Instant queuedAt);

    /**
     * Latest run for a test: {@code ORDER BY queued_at DESC, id::text DESC}.
     * The {@code id::text} tie-break matches the M2 canonical UUID-text contract;
     * it is chosen for semantic consistency and is not claimed to be covered by
     * the native-UUID index alone.
     */
    Optional<TestRunEntity> findLatestByTestId(UUID testId);

    /** @return rows updated (0 or 1) */
    int tryMarkRunning(UUID runId, Instant startedAt);

    /** @return rows updated (0 or 1) */
    int tryComplete(
            UUID runId,
            TestStatus terminalStatus,
            Integer exitCode,
            FailureCategory failureCategory,
            String stdout,
            String stderr,
            RunnerResultDto runnerResult,
            Instant finishedAt
    );

    /** @return rows updated (0 or 1) */
    int tryFailQueued(
            UUID runId,
            FailureCategory category,
            String stderr,
            Instant finishedAt
    );

    /**
     * Startup recovery: QUEUED/RUNNING → CANCELLED.
     *
     * @return number of rows recovered
     */
    int cancelAllActive(Instant finishedAt, String stderrNote);
}
