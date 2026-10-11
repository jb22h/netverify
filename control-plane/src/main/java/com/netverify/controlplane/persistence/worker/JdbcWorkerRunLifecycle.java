package com.netverify.controlplane.persistence.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.persistence.TerminalUpdate;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@Profile("postgres")
public class JdbcWorkerRunLifecycle implements WorkerRunLifecycle {

    private static final String CLAIM_SQL = """
            UPDATE test_runs
            SET status = 'RUNNING',
                started_at = CURRENT_TIMESTAMP,
                worker_id = ?,
                lease_expires_at = CURRENT_TIMESTAMP + make_interval(secs => ?)
            WHERE id = ?
              AND test_id = ?
              AND status = 'QUEUED'
            """;

    private static final String RENEW_SQL = """
            UPDATE test_runs
            SET lease_expires_at = CURRENT_TIMESTAMP + make_interval(secs => ?)
            WHERE id = ?
              AND status = 'RUNNING'
              AND worker_id = ?
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at > CURRENT_TIMESTAMP
            """;

    private static final String COMPLETE_SQL = """
            UPDATE test_runs
            SET status = ?,
                exit_code = ?,
                failure_category = ?,
                stdout = ?,
                stderr = ?,
                runner_result = CAST(? AS jsonb),
                finished_at = ?,
                lease_expires_at = NULL
            WHERE id = ?
              AND status = 'RUNNING'
              AND worker_id = ?
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at > CURRENT_TIMESTAMP
            """;

    private static final String RECLAIM_SQL = """
            UPDATE test_runs
            SET status = 'FAILED',
                failure_category = 'TIMEOUT',
                finished_at = CURRENT_TIMESTAMP,
                lease_expires_at = NULL
            WHERE id = ?
              AND status = 'RUNNING'
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at <= CURRENT_TIMESTAMP
            """;

    private static final String CLASSIFY_CLAIM_SQL = """
            SELECT test_id::text AS test_id,
                   status,
                   (lease_expires_at IS NOT NULL
                       AND lease_expires_at > CURRENT_TIMESTAMP) AS lease_valid,
                   (lease_expires_at IS NOT NULL
                       AND lease_expires_at <= CURRENT_TIMESTAMP) AS lease_expired,
                   (lease_expires_at IS NULL) AS lease_missing
            FROM test_runs
            WHERE id = ?
            """;

    private static final String CLASSIFY_RECLAIM_SQL = """
            SELECT status,
                   (lease_expires_at IS NOT NULL
                       AND lease_expires_at > CURRENT_TIMESTAMP) AS lease_valid,
                   (lease_expires_at IS NOT NULL) AS has_lease
            FROM test_runs
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcWorkerRunLifecycle(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ClaimResult claimForExecution(
            UUID runId,
            UUID expectedTestId,
            String workerId,
            Duration leaseDuration
    ) {
        validateIds(runId, expectedTestId, workerId);
        long leaseSeconds = requireAtLeastOneSecond(leaseDuration);

        int updated = jdbcTemplate.update(
                CLAIM_SQL,
                workerId.trim(),
                leaseSeconds,
                runId,
                expectedTestId
        );
        if (updated == 1) {
            return ClaimResult.of(ClaimOutcome.CLAIMED);
        }

        updated = jdbcTemplate.update(
                CLAIM_SQL,
                workerId.trim(),
                leaseSeconds,
                runId,
                expectedTestId
        );
        if (updated == 1) {
            return ClaimResult.of(ClaimOutcome.CLAIMED);
        }

        return classifyClaimMiss(runId, expectedTestId);
    }

    @Override
    public boolean renewLease(UUID runId, String workerId, Duration leaseDuration) {
        validateRunAndWorker(runId, workerId);
        long leaseSeconds = requireAtLeastOneSecond(leaseDuration);
        return jdbcTemplate.update(
                RENEW_SQL,
                leaseSeconds,
                runId,
                workerId.trim()
        ) == 1;
    }

    @Override
    public boolean complete(
            UUID runId,
            String workerId,
            TerminalUpdate update,
            Instant finishedAt
    ) {
        validateRunAndWorker(runId, workerId);
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(finishedAt, "finishedAt");
        if (update.status() != TestStatus.PASSED && update.status() != TestStatus.FAILED) {
            throw new IllegalArgumentException(
                    "terminal status must be PASSED or FAILED: " + update.status()
            );
        }

        RunnerResultDto result = update.runnerResult() instanceof RunnerResultDto dto ? dto : null;
        return jdbcTemplate.update(
                COMPLETE_SQL,
                update.status().name(),
                update.exitCode(),
                update.failureCategory() == null ? null : update.failureCategory().name(),
                update.stdout(),
                update.stderr(),
                writeRunnerResult(result),
                Timestamp.from(finishedAt),
                runId,
                workerId.trim()
        ) == 1;
    }

    @Override
    public ReclaimResult reclaimExpiredRunning(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        if (jdbcTemplate.update(RECLAIM_SQL, runId) == 1) {
            return ReclaimResult.of(ReclaimOutcome.RECLAIMED);
        }

        ReclaimRow row = readReclaimRow(runId);
        if (row == null) {
            return ReclaimResult.of(ReclaimOutcome.NOT_FOUND);
        }

        ReclaimResult early = classifyReclaimRow(row);
        if (early != null) {
            return early;
        }

        // RUNNING + lease present + expired relative to CURRENT_TIMESTAMP:
        // the initial UPDATE may have raced with the lease boundary; retry once.
        if (jdbcTemplate.update(RECLAIM_SQL, runId) == 1) {
            return ReclaimResult.of(ReclaimOutcome.RECLAIMED);
        }

        ReclaimRow finalRow = readReclaimRow(runId);
        if (finalRow == null) {
            return ReclaimResult.of(ReclaimOutcome.NOT_FOUND);
        }
        ReclaimResult finalClassification = classifyReclaimRow(finalRow);
        if (finalClassification != null) {
            return finalClassification;
        }
        throw new IllegalStateException(
                "reclaim miss with RUNNING expired lease after retry for run " + runId
        );
    }

    private ClaimResult classifyClaimMiss(UUID runId, UUID expectedTestId) {
        ClaimRow row;
        try {
            row = jdbcTemplate.queryForObject(
                    CLASSIFY_CLAIM_SQL,
                    (rs, rowNum) -> new ClaimRow(
                            UUID.fromString(rs.getString("test_id")),
                            rs.getString("status"),
                            rs.getBoolean("lease_valid"),
                            rs.getBoolean("lease_expired"),
                            rs.getBoolean("lease_missing")
                    ),
                    runId
            );
        } catch (EmptyResultDataAccessException ex) {
            return ClaimResult.of(ClaimOutcome.NOT_FOUND);
        }

        if (!expectedTestId.equals(row.testId())) {
            return ClaimResult.of(ClaimOutcome.TEST_ID_MISMATCH);
        }
        if (isTerminal(row.status())) {
            return ClaimResult.of(ClaimOutcome.ALREADY_TERMINAL);
        }
        if (TestStatus.RUNNING.name().equals(row.status())) {
            if (row.leaseMissing()) {
                return ClaimResult.of(ClaimOutcome.RUNNING_WITHOUT_LEASE);
            }
            if (row.leaseValid()) {
                return ClaimResult.of(ClaimOutcome.RUNNING_WITH_VALID_LEASE);
            }
            if (row.leaseExpired()) {
                return ClaimResult.of(ClaimOutcome.EXPIRED_RUNNING_NEEDS_RECLAIM);
            }
        }
        if (TestStatus.QUEUED.name().equals(row.status())) {
            throw new IllegalStateException(
                    "claim miss with QUEUED row after retry for run " + runId
            );
        }
        throw new IllegalStateException(
                "unclassified claim miss for run " + runId + " status=" + row.status()
        );
    }

    /**
     * Classifies a reclaim miss into a terminal outcome, or returns {@code null}
     * when the row is still {@code RUNNING} with an expired lease (retry candidate).
     */
    private static ReclaimResult classifyReclaimRow(ReclaimRow row) {
        if (!TestStatus.RUNNING.name().equals(row.status())) {
            return ReclaimResult.of(ReclaimOutcome.NOT_RUNNING);
        }
        if (!row.hasLease()) {
            return ReclaimResult.of(ReclaimOutcome.NO_LEASE);
        }
        if (row.leaseValid()) {
            return ReclaimResult.of(ReclaimOutcome.LEASE_STILL_VALID);
        }
        // RUNNING + has lease + not valid => expired; never map this to NOT_RUNNING.
        return null;
    }

    private ReclaimRow readReclaimRow(UUID runId) {
        try {
            return jdbcTemplate.queryForObject(
                    CLASSIFY_RECLAIM_SQL,
                    (rs, rowNum) -> new ReclaimRow(
                            rs.getString("status"),
                            rs.getBoolean("lease_valid"),
                            rs.getBoolean("has_lease")
                    ),
                    runId
            );
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private static void validateIds(UUID runId, UUID expectedTestId, String workerId) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(expectedTestId, "expectedTestId");
        validateWorkerId(workerId);
    }

    private static void validateRunAndWorker(UUID runId, String workerId) {
        Objects.requireNonNull(runId, "runId");
        validateWorkerId(workerId);
    }

    private static void validateWorkerId(String workerId) {
        if (!StringUtils.hasText(workerId)) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
    }

    private static long requireAtLeastOneSecond(Duration leaseDuration) {
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        long leaseSeconds = leaseDuration.toSeconds();
        if (leaseSeconds < 1) {
            throw new IllegalArgumentException(
                    "leaseDuration must be at least one second: " + leaseDuration
            );
        }
        return leaseSeconds;
    }

    private static boolean isTerminal(String status) {
        return TestStatus.PASSED.name().equals(status)
                || TestStatus.FAILED.name().equals(status)
                || TestStatus.CANCELLED.name().equals(status);
    }

    private String writeRunnerResult(RunnerResultDto result) {
        if (result == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize runner_result", ex);
        }
    }

    private record ClaimRow(
            UUID testId,
            String status,
            boolean leaseValid,
            boolean leaseExpired,
            boolean leaseMissing
    ) {
    }

    private record ReclaimRow(String status, boolean leaseValid, boolean hasLease) {
    }
}
