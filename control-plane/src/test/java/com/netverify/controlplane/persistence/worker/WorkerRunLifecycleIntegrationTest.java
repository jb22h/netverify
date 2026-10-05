package com.netverify.controlplane.persistence.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.persistence.TerminalUpdate;
import com.netverify.controlplane.persistence.TestEntity;
import com.netverify.controlplane.persistence.TestRepository;
import com.netverify.controlplane.persistence.TestRunEntity;
import com.netverify.controlplane.persistence.TestRunRepository;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_URL", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_USER", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_PASSWORD", matches = ".+")
})
class WorkerRunLifecycleIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(120);

    @Autowired
    private WorkerRunLifecycle workerRunLifecycle;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private TestRepository testRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v2ColumnsAndPartialIndexExist() {
        Integer workerColumn = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'test_runs' AND column_name = 'worker_id'
                """,
                Integer.class
        );
        Integer leaseColumn = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'test_runs' AND column_name = 'lease_expires_at'
                """,
                Integer.class
        );
        Integer indexCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM pg_indexes
                WHERE tablename = 'test_runs'
                  AND indexname = 'idx_test_runs_running_with_lease'
                """,
                Integer.class
        );

        assertEquals(1, workerColumn);
        assertEquals(1, leaseColumn);
        assertEquals(1, indexCount);
    }

    @Test
    void claimQueuedRunSetsWorkerAndLease() {
        TestEntity def = insertDefinition("claim");
        UUID runId = UUID.randomUUID();
        assertEquals(1, testRunRepository.tryInsertQueued(runId, def.id(), Instant.now()));

        ClaimResult claim = workerRunLifecycle.claimForExecution(
                runId, def.id(), "worker-a", LEASE
        );
        assertEquals(ClaimOutcome.CLAIMED, claim.outcome());

        TestRunEntity row = testRunRepository.findById(runId).orElseThrow();
        assertEquals(TestStatus.RUNNING, row.status());
        assertEquals("worker-a", row.workerId());
        assertNotNull(row.startedAt());
        assertNotNull(row.leaseExpiresAt());
    }

    @Test
    void claimRejectsTestIdMismatch() {
        TestEntity def = insertDefinition("mismatch");
        UUID runId = UUID.randomUUID();
        testRunRepository.tryInsertQueued(runId, def.id(), Instant.now());

        ClaimResult claim = workerRunLifecycle.claimForExecution(
                runId, UUID.randomUUID(), "worker-a", LEASE
        );
        assertEquals(ClaimOutcome.TEST_ID_MISMATCH, claim.outcome());
    }

    @Test
    void claimRejectsTerminalRow() {
        TestEntity def = insertDefinition("terminal");
        UUID runId = UUID.randomUUID();
        insertTerminalRun(runId, def.id());

        ClaimResult claim = workerRunLifecycle.claimForExecution(
                runId, def.id(), "worker-a", LEASE
        );
        assertEquals(ClaimOutcome.ALREADY_TERMINAL, claim.outcome());
    }

    @Test
    void validLeaseBlocksSameWorkerDuplicateLaunch() {
        TestEntity def = insertDefinition("dup-self");
        UUID runId = UUID.randomUUID();
        testRunRepository.tryInsertQueued(runId, def.id(), Instant.now());
        assertTrue(workerRunLifecycle.claimForExecution(runId, def.id(), "worker-a", LEASE).claimed());

        ClaimResult second = workerRunLifecycle.claimForExecution(
                runId, def.id(), "worker-a", LEASE
        );
        assertEquals(ClaimOutcome.RUNNING_WITH_VALID_LEASE, second.outcome());
    }

    @Test
    void validLeaseBlocksOtherWorker() {
        TestEntity def = insertDefinition("dup-other");
        UUID runId = UUID.randomUUID();
        testRunRepository.tryInsertQueued(runId, def.id(), Instant.now());
        assertTrue(workerRunLifecycle.claimForExecution(runId, def.id(), "worker-a", LEASE).claimed());

        ClaimResult other = workerRunLifecycle.claimForExecution(
                runId, def.id(), "worker-b", LEASE
        );
        assertEquals(ClaimOutcome.RUNNING_WITH_VALID_LEASE, other.outcome());
    }

    @Test
    void expiredLeaseNeedsReclaim() {
        TestEntity def = insertDefinition("expired");
        UUID runId = UUID.randomUUID();
        testRunRepository.tryInsertQueued(runId, def.id(), Instant.now());
        assertTrue(workerRunLifecycle.claimForExecution(runId, def.id(), "worker-a", LEASE).claimed());

        expireLease(runId);

        ClaimResult claim = workerRunLifecycle.claimForExecution(
                runId, def.id(), "worker-b", LEASE
        );
        assertEquals(ClaimOutcome.EXPIRED_RUNNING_NEEDS_RECLAIM, claim.outcome());
    }

    @Test
    void runningWithoutLeaseFromLocalPath() {
        TestEntity def = insertDefinition("local-running");
        UUID runId = UUID.randomUUID();
        testRunRepository.tryInsertQueued(runId, def.id(), Instant.now());
        assertEquals(1, testRunRepository.tryMarkRunning(runId, Instant.now()));

        ClaimResult claim = workerRunLifecycle.claimForExecution(
                runId, def.id(), "worker-a", LEASE
        );
        assertEquals(ClaimOutcome.RUNNING_WITHOUT_LEASE, claim.outcome());
    }

    @Test
    void ownerRenewSucceedsWrongOwnerFails() {
        TestEntity def = insertDefinition("renew");
        UUID runId = UUID.randomUUID();
        testRunRepository.tryInsertQueued(runId, def.id(), Instant.now());
        workerRunLifecycle.claimForExecution(runId, def.id(), "worker-a", LEASE);

        assertTrue(workerRunLifecycle.renewLease(runId, "worker-a", LEASE));
        assertFalse(workerRunLifecycle.renewLease(runId, "worker-b", LEASE));
    }

    @Test
    void reclaimExpiredRunningToTimeout() {
        TestEntity def = insertDefinition("reclaim");
        UUID runId = UUID.randomUUID();
        testRunRepository.tryInsertQueued(runId, def.id(), Instant.now());
        workerRunLifecycle.claimForExecution(runId, def.id(), "worker-a", LEASE);
        expireLease(runId);

        ReclaimResult reclaim = workerRunLifecycle.reclaimExpiredRunning(runId);
        assertEquals(ReclaimOutcome.RECLAIMED, reclaim.outcome());

        TestRunEntity row = testRunRepository.findById(runId).orElseThrow();
        assertEquals(TestStatus.FAILED, row.status());
        assertEquals(FailureCategory.TIMEOUT, row.failureCategory());
        assertNull(row.leaseExpiresAt());
    }

    @Test
    void reclaimRefusesNonExpiredLease() {
        TestEntity def = insertDefinition("fresh-lease");
        UUID runId = UUID.randomUUID();
        testRunRepository.tryInsertQueued(runId, def.id(), Instant.now());
        workerRunLifecycle.claimForExecution(runId, def.id(), "worker-a", LEASE);

        ReclaimResult reclaim = workerRunLifecycle.reclaimExpiredRunning(runId);
        assertEquals(ReclaimOutcome.LEASE_STILL_VALID, reclaim.outcome());
    }

    @Test
    void staleCompletionAfterReclaimFails() {
        TestEntity def = insertDefinition("stale-complete");
        UUID runId = UUID.randomUUID();
        testRunRepository.tryInsertQueued(runId, def.id(), Instant.now());
        workerRunLifecycle.claimForExecution(runId, def.id(), "worker-a", LEASE);
        expireLease(runId);
        workerRunLifecycle.reclaimExpiredRunning(runId);

        assertFalse(workerRunLifecycle.complete(
                runId,
                "worker-a",
                TerminalUpdate.failed(FailureCategory.LAUNCH, null, null, "", "late"),
                Instant.now()
        ));
    }

    @Test
    void wrongOwnerCompletionIsRefusedAndRowStaysRunning() {
        TestEntity def = insertDefinition("wrong-owner");
        UUID runId = UUID.randomUUID();
        testRunRepository.tryInsertQueued(runId, def.id(), Instant.now());
        assertTrue(workerRunLifecycle.claimForExecution(runId, def.id(), "worker-a", LEASE).claimed());

        assertFalse(workerRunLifecycle.complete(
                runId,
                "worker-b",
                TerminalUpdate.passed(
                        0,
                        new RunnerResultDto("basic_ping_pong", true, List.of(), 0, 0.01, Map.of()),
                        "{}",
                        ""
                ),
                Instant.now()
        ));

        TestRunEntity row = testRunRepository.findById(runId).orElseThrow();
        assertEquals(TestStatus.RUNNING, row.status());
        assertEquals("worker-a", row.workerId());
        assertNotNull(row.leaseExpiresAt());
    }

    @Test
    void workerCompletionClearsLease() {
        TestEntity def = insertDefinition("complete");
        UUID runId = UUID.randomUUID();
        testRunRepository.tryInsertQueued(runId, def.id(), Instant.now());
        workerRunLifecycle.claimForExecution(runId, def.id(), "worker-a", LEASE);

        assertTrue(workerRunLifecycle.complete(
                runId,
                "worker-a",
                TerminalUpdate.passed(
                        0,
                        new RunnerResultDto("basic_ping_pong", true, List.of(), 0, 0.01, Map.of()),
                        "{}",
                        ""
                ),
                Instant.now()
        ));

        TestRunEntity row = testRunRepository.findById(runId).orElseThrow();
        assertEquals(TestStatus.PASSED, row.status());
        assertEquals("worker-a", row.workerId());
        assertNull(row.leaseExpiresAt());
    }

    @Test
    void claimNotFoundForMissingRun() {
        ClaimResult claim = workerRunLifecycle.claimForExecution(
                UUID.randomUUID(), UUID.randomUUID(), "worker-a", LEASE
        );
        assertEquals(ClaimOutcome.NOT_FOUND, claim.outcome());
    }

    private TestEntity insertDefinition(String name) {
        UUID id = UUID.randomUUID();
        TestEntity entity = new TestEntity(id, name, "basic_ping_pong", 3, null, Instant.now());
        testRepository.insert(entity);
        return entity;
    }

    private void expireLease(UUID runId) {
        jdbcTemplate.update(
                """
                UPDATE test_runs
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                runId
        );
    }

    private void insertTerminalRun(UUID runId, UUID testId) {
        testRunRepository.insert(new TestRunEntity(
                runId,
                testId,
                TestStatus.PASSED,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                0,
                null,
                "{}",
                "",
                null,
                null,
                null
        ));
    }

}
