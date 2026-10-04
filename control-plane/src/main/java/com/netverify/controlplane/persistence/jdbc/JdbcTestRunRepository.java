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
                readRunnerResult(rs.getString("runner_result"))
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
                    """
                    SELECT id, test_id, status, queued_at, started_at, finished_at,
                           exit_code, failure_category, stdout, stderr,
                           runner_result::text AS runner_result
                    FROM test_runs
                    WHERE id = ?
                    """,
                    rowMapper,
                    id
            ));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
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
