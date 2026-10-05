package com.netverify.controlplane.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.persistence.TestRunEntity;
import com.netverify.controlplane.persistence.TestRunRepository;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcTestRunRepository implements TestRunRepository {

    private static final String SELECT_COLUMNS = """
            id, test_id, status, queued_at, started_at, finished_at,
            exit_code, failure_category, stdout, stderr,
            runner_result::text AS runner_result,
            worker_id, lease_expires_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<TestRunEntity> rowMapper;

    public JdbcTestRunRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = (rs, rowNum) -> new TestRunEntity(
                rs.getObject("id", UUID.class),
                rs.getObject("test_id", UUID.class),
                TestStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("queued_at").toInstant(),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("finished_at")),
                (Integer) rs.getObject("exit_code"),
                toFailureCategory(rs.getString("failure_category")),
                rs.getString("stdout"),
                rs.getString("stderr"),
                readRunnerResult(rs.getString("runner_result")),
                rs.getString("worker_id"),
                toInstant(rs.getTimestamp("lease_expires_at"))
        );
    }

    @Override
    public void insert(TestRunEntity run) {
        jdbcTemplate.update(
                """
                INSERT INTO test_runs (
                    id, test_id, status, queued_at, started_at, finished_at,
                    exit_code, failure_category, stdout, stderr, runner_result
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """,
                run.id(),
                run.testId(),
                run.status().name(),
                Timestamp.from(run.queuedAt()),
                toTimestamp(run.startedAt()),
                toTimestamp(run.finishedAt()),
                run.exitCode(),
                run.failureCategory() == null ? null : run.failureCategory().name(),
                run.stdout(),
                run.stderr(),
                writeRunnerResult(run.runnerResult())
        );
    }

    @Override
    public Optional<TestRunEntity> findById(UUID id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT " + SELECT_COLUMNS + " FROM test_runs WHERE id = ?",
                    rowMapper,
                    id
            ));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public int tryInsertQueued(UUID runId, UUID testId, Instant queuedAt) {
        // Conflict target matches uq_test_runs_one_active_per_test. FK/other errors propagate.
        return jdbcTemplate.update(
                """
                INSERT INTO test_runs (
                    id, test_id, status, queued_at, started_at, finished_at,
                    exit_code, failure_category, stdout, stderr, runner_result
                ) VALUES (?, ?, 'QUEUED', ?, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                ON CONFLICT (test_id) WHERE (status IN ('QUEUED', 'RUNNING')) DO NOTHING
                """,
                runId,
                testId,
                Timestamp.from(queuedAt)
        );
    }

    @Override
    public Optional<TestRunEntity> findLatestByTestId(UUID testId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    """
                    SELECT %s
                    FROM test_runs
                    WHERE test_id = ?
                    ORDER BY queued_at DESC, id::text DESC
                    LIMIT 1
                    """.formatted(SELECT_COLUMNS),
                    rowMapper,
                    testId
            ));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<TestRunEntity> findAllByTestId(UUID testId) {
        // id::text tie-break matches M2 canonical UUID-text ordering for semantic
        // consistency; not claimed to be covered by the native-UUID index alone.
        List<TestRunEntity> rows = jdbcTemplate.query(
                """
                SELECT %s
                FROM test_runs
                WHERE test_id = ?
                ORDER BY queued_at DESC, id::text DESC
                """.formatted(SELECT_COLUMNS),
                rowMapper,
                testId
        );
        return List.copyOf(rows);
    }

    @Override
    public int tryMarkRunning(UUID runId, Instant startedAt) {
        return jdbcTemplate.update(
                """
                UPDATE test_runs
                SET status = 'RUNNING', started_at = ?
                WHERE id = ? AND status = 'QUEUED'
                """,
                Timestamp.from(startedAt),
                runId
        );
    }

    @Override
    public int tryComplete(
            UUID runId,
            TestStatus terminalStatus,
            Integer exitCode,
            FailureCategory failureCategory,
            String stdout,
            String stderr,
            RunnerResultDto runnerResult,
            Instant finishedAt
    ) {
        if (terminalStatus != TestStatus.PASSED && terminalStatus != TestStatus.FAILED) {
            throw new IllegalArgumentException(
                    "terminal status must be PASSED or FAILED: " + terminalStatus
            );
        }
        return jdbcTemplate.update(
                """
                UPDATE test_runs
                SET status = ?,
                    exit_code = ?,
                    failure_category = ?,
                    stdout = ?,
                    stderr = ?,
                    runner_result = CAST(? AS jsonb),
                    finished_at = ?
                WHERE id = ? AND status = 'RUNNING'
                """,
                terminalStatus.name(),
                exitCode,
                failureCategory == null ? null : failureCategory.name(),
                stdout,
                stderr,
                writeRunnerResult(runnerResult),
                Timestamp.from(finishedAt),
                runId
        );
    }

    @Override
    public int tryFailQueued(
            UUID runId,
            FailureCategory category,
            String stderr,
            Instant finishedAt
    ) {
        return jdbcTemplate.update(
                """
                UPDATE test_runs
                SET status = 'FAILED',
                    failure_category = ?,
                    stdout = '',
                    stderr = ?,
                    finished_at = ?,
                    exit_code = NULL,
                    runner_result = NULL
                WHERE id = ? AND status = 'QUEUED'
                """,
                category == null ? null : category.name(),
                stderr == null ? "" : stderr,
                Timestamp.from(finishedAt),
                runId
        );
    }

    @Override
    public int cancelAllActive(Instant finishedAt, String stderrNote) {
        String note = stderrNote == null ? "" : stderrNote;
        return jdbcTemplate.update(
                """
                UPDATE test_runs
                SET status = 'CANCELLED',
                    finished_at = ?,
                    stderr = CASE
                        WHEN stderr IS NULL OR stderr = '' THEN ?
                        ELSE stderr
                    END
                WHERE status IN ('QUEUED', 'RUNNING')
                """,
                Timestamp.from(finishedAt),
                note
        );
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

    private RunnerResultDto readRunnerResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RunnerResultDto.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to deserialize runner_result", ex);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static FailureCategory toFailureCategory(String value) {
        return value == null ? null : FailureCategory.valueOf(value);
    }
}
