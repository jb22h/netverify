package com.netverify.controlplane.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Opt-in PostgreSQL integration tests. Skipped unless all three DB env vars are set
 * and the {@code postgres} profile is active. Default {@code mvn test} never requires
 * a database.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_URL", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_USER", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_PASSWORD", matches = ".+")
})
class JdbcPersistenceIntegrationTest {

    @Autowired
    private TestRepository testRepository;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void springContextConnectsAndFlywayV1HasRun() {
        assertNotNull(jdbcTemplate);
        assertNotNull(flyway);
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success",
                Integer.class
        );
        assertEquals(1, applied);

        Boolean testsExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.tests') IS NOT NULL",
                Boolean.class
        );
        Boolean runsExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.test_runs') IS NOT NULL",
                Boolean.class
        );
        assertTrue(testsExists);
        assertTrue(runsExists);
    }

    @Test
    void testEntityRoundTripIncludingNullableClients() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        TestEntity written = new TestEntity(
                id, "demo", "basic_ping_pong", 10, null, createdAt
        );

        testRepository.insert(written);
        TestEntity read = testRepository.findById(id).orElseThrow();

        assertEquals(id, read.id());
        assertEquals("demo", read.name());
        assertEquals("basic_ping_pong", read.scenario());
        assertEquals(10, read.count());
        assertNull(read.clients());
        assertEquals(createdAt, read.createdAt().truncatedTo(ChronoUnit.MILLIS));
        assertTrue(testRepository.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void testRunEntityRoundTripWithJsonbMetricsAndNullables() {
        UUID testId = UUID.randomUUID();
        testRepository.insert(new TestEntity(
                testId, "load", "concurrent_load", 100, 4, Instant.now()
        ));

        RunnerResultDto result = new RunnerResultDto(
                "concurrent_load",
                true,
                List.of(),
                0,
                0.125,
                Map.of(
                        "requested_count", 100,
                        "aggregate_messages_per_s", 1234.5,
                        "peak_active_connections", 3
                )
        );

        UUID runId = UUID.randomUUID();
        Instant queuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant startedAt = queuedAt.plusSeconds(1);
        Instant finishedAt = startedAt.plusSeconds(2);
        TestRunEntity written = new TestRunEntity(
                runId,
                testId,
                TestStatus.PASSED,
                queuedAt,
                startedAt,
                finishedAt,
                0,
                null,
                "{\"ok\":true}",
                "",
                result,
                null,
                null
        );

        testRunRepository.insert(written);
        TestRunEntity read = testRunRepository.findById(runId).orElseThrow();

        assertEquals(runId, read.id());
        assertEquals(testId, read.testId());
        assertEquals(TestStatus.PASSED, read.status());
        assertEquals(queuedAt, read.queuedAt().truncatedTo(ChronoUnit.MILLIS));
        assertEquals(startedAt, read.startedAt().truncatedTo(ChronoUnit.MILLIS));
        assertEquals(finishedAt, read.finishedAt().truncatedTo(ChronoUnit.MILLIS));
        assertEquals(0, read.exitCode());
        assertNull(read.failureCategory());
        assertEquals("{\"ok\":true}", read.stdout());
        assertEquals("", read.stderr());
        assertNotNull(read.runnerResult());
        assertEquals("concurrent_load", read.runnerResult().name());
        assertTrue(read.runnerResult().passed());
        assertEquals(0, read.runnerResult().clientExitCode());
        assertEquals(0.125, read.runnerResult().durationSeconds());
        assertEquals(100, ((Number) read.runnerResult().metrics().get("requested_count")).intValue());
        assertEquals(
                1234.5,
                ((Number) read.runnerResult().metrics().get("aggregate_messages_per_s")).doubleValue()
        );
        assertEquals(
                3,
                ((Number) read.runnerResult().metrics().get("peak_active_connections")).intValue()
        );
    }

    @Test
    void queuedRunWithNullRunnerResultAndFailureCategoryRoundTrips() {
        UUID testId = UUID.randomUUID();
        testRepository.insert(new TestEntity(
                testId, "pending", "basic_ping_pong", 3, null, Instant.now()
        ));

        UUID runId = UUID.randomUUID();
        TestRunEntity written = new TestRunEntity(
                runId,
                testId,
                TestStatus.QUEUED,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        testRunRepository.insert(written);
        TestRunEntity read = testRunRepository.findById(runId).orElseThrow();

        assertEquals(TestStatus.QUEUED, read.status());
        assertNull(read.startedAt());
        assertNull(read.finishedAt());
        assertNull(read.exitCode());
        assertNull(read.failureCategory());
        assertNull(read.stdout());
        assertNull(read.stderr());
        assertNull(read.runnerResult());
    }

    @Test
    void testRunRequiresExistingTestForeignKey() {
        UUID orphanRunId = UUID.randomUUID();
        TestRunEntity orphan = new TestRunEntity(
                orphanRunId,
                UUID.randomUUID(),
                TestStatus.QUEUED,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThrows(DataIntegrityViolationException.class, () -> testRunRepository.insert(orphan));
        assertTrue(testRunRepository.findById(orphanRunId).isEmpty());
    }

    @Test
    void uniqueActiveRunIndexRejectsSecondQueuedRun() {
        UUID testId = UUID.randomUUID();
        testRepository.insert(new TestEntity(
                testId, "active", "basic_ping_pong", 5, null, Instant.now()
        ));

        testRunRepository.insert(new TestRunEntity(
                UUID.randomUUID(),
                testId,
                TestStatus.QUEUED,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        TestRunEntity second = new TestRunEntity(
                UUID.randomUUID(),
                testId,
                TestStatus.QUEUED,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        // Auto-commit: a failed statement must not poison later statements.
        assertThrows(DataIntegrityViolationException.class, () -> testRunRepository.insert(second));

        Integer activeCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM test_runs
                WHERE test_id = ? AND status IN ('QUEUED', 'RUNNING')
                """,
                Integer.class,
                testId
        );
        assertEquals(1, activeCount);
    }

    @Test
    void uniqueActiveRunIndexRejectsQueuedPlusRunning() {
        UUID testId = UUID.randomUUID();
        testRepository.insert(new TestEntity(
                testId, "race", "basic_ping_pong", 5, null, Instant.now()
        ));

        testRunRepository.insert(new TestRunEntity(
                UUID.randomUUID(),
                testId,
                TestStatus.RUNNING,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> testRunRepository.insert(new TestRunEntity(
                        UUID.randomUUID(),
                        testId,
                        TestStatus.QUEUED,
                        Instant.now(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ))
        );
    }

    @Test
    void terminalRunsDoNotBlockANewQueuedRun() {
        UUID testId = UUID.randomUUID();
        testRepository.insert(new TestEntity(
                testId, "rerun", "basic_ping_pong", 5, null, Instant.now()
        ));

        testRunRepository.insert(new TestRunEntity(
                UUID.randomUUID(),
                testId,
                TestStatus.FAILED,
                Instant.now().minusSeconds(10),
                Instant.now().minusSeconds(9),
                Instant.now().minusSeconds(8),
                1,
                FailureCategory.VERIFICATION,
                "{}",
                "boom",
                new RunnerResultDto(
                        "basic_ping_pong", false, List.of("boom"), 1, 0.01, Map.of()
                ),
                null,
                null
        ));

        UUID secondId = UUID.randomUUID();
        testRunRepository.insert(new TestRunEntity(
                secondId,
                testId,
                TestStatus.QUEUED,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        TestRunEntity second = testRunRepository.findById(secondId).orElseThrow();
        assertEquals(TestStatus.QUEUED, second.status());
        assertNull(second.runnerResult());
    }
}
