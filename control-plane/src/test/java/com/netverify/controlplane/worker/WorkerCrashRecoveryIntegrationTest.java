package com.netverify.controlplane.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netverify.controlplane.config.NetVerifyProperties;
import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.execution.RunExecutionService;
import com.netverify.controlplane.execution.RunLeaseHeartbeater;
import com.netverify.controlplane.persistence.TerminalUpdate;
import com.netverify.controlplane.persistence.TestEntity;
import com.netverify.controlplane.persistence.TestRepository;
import com.netverify.controlplane.persistence.TestRunEntity;
import com.netverify.controlplane.persistence.TestRunRepository;
import com.netverify.controlplane.persistence.worker.ClaimOutcome;
import com.netverify.controlplane.persistence.worker.ClaimResult;
import com.netverify.controlplane.persistence.worker.WorkerRunLifecycle;
import com.netverify.controlplane.queue.AutoClaimBatch;
import com.netverify.controlplane.queue.QueueDelivery;
import com.netverify.controlplane.queue.RunQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Opt-in PG+Redis crash-window ITs for M4 Build 1 (A/B/C).
 *
 * <p>Boots distributed profiles as {@code role=api} so {@link RedisRunWorker} does not
 * consume in the background. Processors are constructed in-test with a counting
 * fake {@link RunExecutionService} (no real Python).
 *
 * <p>Skipped unless {@code NETVERIFY_DB_*} and {@code NETVERIFY_REDIS_HOST} are set.
 */
@SpringBootTest
@ActiveProfiles({"postgres", "redis"})
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_URL", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_USER", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_PASSWORD", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_REDIS_HOST", matches = ".+")
})
class WorkerCrashRecoveryIntegrationTest {

    private static final String ISOLATION_SUFFIX = UUID.randomUUID().toString();
    private static final String STREAM = "netverify:runs:m4-crash:" + ISOLATION_SUFFIX;
    private static final String GROUP = "netverify-workers-m4-crash-" + ISOLATION_SUFFIX;
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(120);
    private static final Duration TEST_MIN_IDLE = Duration.ofSeconds(1);
    private static final Duration MIN_IDLE_DEADLINE = Duration.ofSeconds(5);

    @DynamicPropertySource
    static void isolateQueueAndRole(DynamicPropertyRegistry registry) {
        registry.add("netverify.queue.stream", () -> STREAM);
        registry.add("netverify.queue.group", () -> GROUP);
        registry.add("netverify.queue.claim-min-idle-seconds", () -> "1");
        // API role: validator OK, no RedisRunWorker auto-consumer in this harness.
        registry.add("netverify.role", () -> "api");
    }

    @Autowired
    private RunQueue runQueue;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private WorkerRunLifecycle workerRunLifecycle;

    @Autowired
    private TestRepository testRepository;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RunLeaseHeartbeater heartbeater;
    private AtomicInteger executionCount;
    private RunExecutionService countingExecution;

    @BeforeEach
    void setUp() {
        stringRedisTemplate.delete(STREAM);
        runQueue.ensureConsumerGroup();

        executionCount = new AtomicInteger(0);
        countingExecution = mock(RunExecutionService.class);
        when(countingExecution.execute(any(), anyInt(), any())).thenAnswer(invocation -> {
            executionCount.incrementAndGet();
            return TerminalUpdate.passed(0, null, "", "");
        });
        heartbeater = new RunLeaseHeartbeater(workerRunLifecycle);
    }

    @AfterEach
    void tearDown() {
        if (heartbeater != null) {
            heartbeater.close();
        }
        stringRedisTemplate.delete(STREAM);
    }

    @Test
    void crashA_deliveredBeforeClaim_secondWorkerClaimsAndExecutesOnce() {
        Fixture fixture = seedQueuedRun("crash-a");
        String messageId = runQueue.publish(fixture.runId(), fixture.testId());

        QueueDelivery delivered = readOne("worker-1");
        assertEquals(messageId, delivered.messageId());
        assertEquals(TestStatus.QUEUED, requireRun(fixture.runId()).status());
        assertEquals(0, executionCount.get());

        QueueDelivery claimed = awaitClaimStale("worker-2", messageId);
        WorkerMessageProcessor worker2 = processor("worker-2");
        worker2.process(claimed);

        TestRunEntity row = requireRun(fixture.runId());
        assertEquals(TestStatus.PASSED, row.status());
        assertEquals("worker-2", row.workerId());
        assertEquals(1, executionCount.get());
        assertFalse(runQueue.acknowledge(messageId), "message should already be ACKed");
        assertNoPendingDelivery(messageId);
    }

    @Test
    void crashB_claimedDuringExecution_reclaimTimeoutWithoutSecondPython() {
        Fixture fixture = seedQueuedRun("crash-b");
        String messageId = runQueue.publish(fixture.runId(), fixture.testId());
        QueueDelivery delivery = readOne("worker-1");
        assertEquals(messageId, delivery.messageId());

        ClaimResult claim = workerRunLifecycle.claimForExecution(
                fixture.runId(), fixture.testId(), "worker-1", CLAIM_LEASE
        );
        assertEquals(ClaimOutcome.CLAIMED, claim.outcome());
        // Simulate mid-Python crash: execution started once, no complete, no ACK.
        executionCount.set(1);
        assertEquals(TestStatus.RUNNING, requireRun(fixture.runId()).status());

        // Redis ownership transfer while PG lease is still valid must not authorize Python.
        QueueDelivery transferred = awaitClaimStale("worker-2", messageId);
        WorkerMessageProcessor worker2 = processor("worker-2");
        int beforePeerProcess = executionCount.get();
        worker2.process(transferred);
        assertEquals(beforePeerProcess, executionCount.get(), "valid lease must not execute");
        assertEquals(TestStatus.RUNNING, requireRun(fixture.runId()).status());
        assertMessagePendingInGroup(messageId);

        expireLease(fixture.runId());

        // Third worker XAUTOCLAIMs after the entry is idle again, then reclaims via PG.
        QueueDelivery expiredDelivery = awaitClaimStale("worker-3", messageId);
        WorkerMessageProcessor worker3 = processor("worker-3");
        worker3.process(expiredDelivery);

        TestRunEntity row = requireRun(fixture.runId());
        assertEquals(TestStatus.FAILED, row.status());
        assertEquals(FailureCategory.TIMEOUT, row.failureCategory());
        assertEquals(1, executionCount.get(), "must never retry Python after reclaim");
        assertFalse(runQueue.acknowledge(messageId), "reclaim path should have ACKed");
        assertNoPendingDelivery(messageId);
    }

    @Test
    void crashC_terminalBeforeAck_secondWorkerAcksOnly() {
        Fixture fixture = seedQueuedRun("crash-c");
        String messageId = runQueue.publish(fixture.runId(), fixture.testId());
        QueueDelivery delivery = readOne("worker-1");
        assertEquals(messageId, delivery.messageId());

        ClaimResult claim = workerRunLifecycle.claimForExecution(
                fixture.runId(), fixture.testId(), "worker-1", CLAIM_LEASE
        );
        assertEquals(ClaimOutcome.CLAIMED, claim.outcome());

        TerminalUpdate update = countingExecution.execute("basic_ping_pong", 3, null);
        assertEquals(1, executionCount.get());
        assertTrue(workerRunLifecycle.complete(
                fixture.runId(), "worker-1", update, Instant.now()
        ));
        TestRunEntity terminal = requireRun(fixture.runId());
        assertEquals(TestStatus.PASSED, terminal.status());
        // Deliberately skip Redis ACK — message remains pending for worker-1.

        QueueDelivery redelivered = awaitClaimStale("worker-2", messageId);
        WorkerMessageProcessor worker2 = processor("worker-2");
        worker2.process(redelivered);

        TestRunEntity after = requireRun(fixture.runId());
        assertEquals(TestStatus.PASSED, after.status());
        assertEquals(terminal.finishedAt(), after.finishedAt());
        assertEquals(1, executionCount.get(), "ALREADY_TERMINAL must not re-execute");
        assertFalse(runQueue.acknowledge(messageId), "second worker should have ACKed");
        assertNoPendingDelivery(messageId);
    }

    private WorkerMessageProcessor processor(String workerId) {
        NetVerifyProperties properties = new NetVerifyProperties();
        properties.setWorkerId(workerId);
        return new WorkerMessageProcessor(
                workerRunLifecycle,
                testRepository,
                countingExecution,
                heartbeater,
                runQueue,
                properties
        );
    }

    private Fixture seedQueuedRun(String namePrefix) {
        UUID testId = UUID.randomUUID();
        TestEntity definition = new TestEntity(
                testId,
                namePrefix + "-" + testId,
                "basic_ping_pong",
                3,
                null,
                Instant.now()
        );
        testRepository.insert(definition);
        UUID runId = UUID.randomUUID();
        assertEquals(1, testRunRepository.tryInsertQueued(runId, testId, Instant.now()));
        return new Fixture(runId, testId);
    }

    private QueueDelivery readOne(String consumer) {
        var deliveries = runQueue.read(consumer, 1);
        assertEquals(1, deliveries.size(), "expected one fresh delivery for " + consumer);
        return deliveries.getFirst();
    }

    private QueueDelivery awaitClaimStale(String consumer, String messageId) {
        Instant deadline = Instant.now().plus(MIN_IDLE_DEADLINE);
        String cursor = "0-0";
        while (Instant.now().isBefore(deadline)) {
            AutoClaimBatch batch = runQueue.claimStale(consumer, TEST_MIN_IDLE, cursor, 1);
            for (QueueDelivery delivery : batch.deliveries()) {
                if (messageId.equals(delivery.messageId())) {
                    return delivery;
                }
            }
            cursor = batch.nextStartId();
            if ("0-0".equals(cursor)) {
                parkBriefly();
            }
        }
        fail("timed out waiting for XAUTOCLAIM of messageId=" + messageId + " by " + consumer);
        return null;
    }

    private void assertNoPendingDelivery(String messageId) {
        AutoClaimBatch scan = runQueue.claimStale("pending-check", TEST_MIN_IDLE, "0-0", 1);
        for (QueueDelivery delivery : scan.deliveries()) {
            assertFalse(
                    messageId.equals(delivery.messageId()),
                    "message still pending after ACK: " + messageId
            );
        }
    }

    private void assertMessagePendingInGroup(String messageId) {
        var pending = stringRedisTemplate.opsForStream().pending(
                STREAM,
                GROUP,
                org.springframework.data.domain.Range.unbounded(),
                100L
        );
        assertTrue(pending != null && !pending.isEmpty(), "expected pending entries in group");
        boolean found = false;
        for (var entry : pending) {
            if (messageId.equals(entry.getIdAsString())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "message should still be pending (not ACKed): " + messageId);
    }

    private void expireLease(UUID runId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE test_runs
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                runId
        );
        assertEquals(1, updated, "expireLease must update exactly one row for " + runId);
    }

    private TestRunEntity requireRun(UUID runId) {
        return testRunRepository.findById(runId).orElseThrow();
    }

    private static void parkBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for min-idle");
        }
    }

    private record Fixture(UUID runId, UUID testId) {
    }
}
