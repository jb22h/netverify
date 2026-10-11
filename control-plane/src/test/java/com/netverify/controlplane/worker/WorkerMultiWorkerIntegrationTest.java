package com.netverify.controlplane.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * Opt-in PG+Redis multi-worker / restart ITs for M4 Build 2 (D1/D2/E).
 *
 * <p>Boots as {@code role=api} so {@link RedisRunWorker} does not consume in the
 * background. Processors are constructed in-test with counting/blocking fakes
 * (no real Python).
 */
@SpringBootTest
@ActiveProfiles({"postgres", "redis"})
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_URL", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_USER", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_PASSWORD", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_REDIS_HOST", matches = ".+")
})
class WorkerMultiWorkerIntegrationTest {

    private static final String ISOLATION_SUFFIX = UUID.randomUUID().toString();
    private static final String STREAM = "netverify:runs:m4-multi:" + ISOLATION_SUFFIX;
    private static final String GROUP = "netverify-workers-m4-multi-" + ISOLATION_SUFFIX;
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(120);
    private static final Duration TEST_MIN_IDLE = Duration.ofSeconds(1);
    private static final Duration MIN_IDLE_DEADLINE = Duration.ofSeconds(5);

    @DynamicPropertySource
    static void isolateQueueAndRole(DynamicPropertyRegistry registry) {
        registry.add("netverify.queue.stream", () -> STREAM);
        registry.add("netverify.queue.group", () -> GROUP);
        registry.add("netverify.queue.claim-min-idle-seconds", () -> "1");
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

    @BeforeEach
    void setUp() {
        stringRedisTemplate.delete(STREAM);
        runQueue.ensureConsumerGroup();
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
    void d1_twoHealthyWorkersOwnAndExecuteDistinctRuns() {
        Fixture runA = seedQueuedRun("d1-a");
        Fixture runB = seedQueuedRun("d1-b");
        String messageIdA = runQueue.publish(runA.runId(), runA.testId());
        String messageIdB = runQueue.publish(runB.runId(), runB.testId());

        QueueDelivery delivery1 = readOne("worker-1");
        QueueDelivery delivery2 = readOne("worker-2");
        assertTrue(
                (delivery1.messageId().equals(messageIdA) && delivery2.messageId().equals(messageIdB))
                        || (delivery1.messageId().equals(messageIdB) && delivery2.messageId().equals(messageIdA)),
                "workers must each receive one of the two published messages"
        );

        AtomicInteger executions1 = new AtomicInteger();
        AtomicInteger executions2 = new AtomicInteger();
        WorkerMessageProcessor processor1 = processor("worker-1", countingExecution(executions1));
        WorkerMessageProcessor processor2 = processor("worker-2", countingExecution(executions2));

        processor1.process(delivery1);
        processor2.process(delivery2);

        TestRunEntity rowForDelivery1 = requireRun(delivery1.runId());
        TestRunEntity rowForDelivery2 = requireRun(delivery2.runId());
        assertEquals(TestStatus.PASSED, rowForDelivery1.status());
        assertEquals(TestStatus.PASSED, rowForDelivery2.status());
        assertEquals("worker-1", rowForDelivery1.workerId());
        assertEquals("worker-2", rowForDelivery2.workerId());
        assertNull(rowForDelivery1.leaseExpiresAt());
        assertNull(rowForDelivery2.leaseExpiresAt());
        assertEquals(1, executions1.get());
        assertEquals(1, executions2.get());
        assertFalse(runQueue.acknowledge(delivery1.messageId()));
        assertFalse(runQueue.acknowledge(delivery2.messageId()));
        assertNoPendingDelivery(delivery1.messageId());
        assertNoPendingDelivery(delivery2.messageId());
    }

    @Test
    void d2_duplicateTransportDeliveryDoesNotDuplicateExecution() throws Exception {
        Fixture fixture = seedQueuedRun("d2-dup");
        String messageIdA = runQueue.publish(fixture.runId(), fixture.testId());
        String messageIdB = runQueue.publish(fixture.runId(), fixture.testId());

        QueueDelivery messageA = readOne("worker-1");
        QueueDelivery messageB = readOne("worker-2");
        assertEquals(fixture.runId(), messageA.runId());
        assertEquals(fixture.runId(), messageB.runId());
        assertTrue(
                (messageA.messageId().equals(messageIdA) && messageB.messageId().equals(messageIdB))
                        || (messageA.messageId().equals(messageIdB) && messageB.messageId().equals(messageIdA))
        );

        AtomicInteger executionCount = new AtomicInteger();
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);

        RunExecutionService blockingExecution = mock(RunExecutionService.class);
        when(blockingExecution.execute(any(), anyInt(), any())).thenAnswer(invocation -> {
            executionCount.incrementAndGet();
            executionStarted.countDown();
            if (!releaseExecution.await(10, TimeUnit.SECONDS)) {
                fail("timed out waiting to release worker-1 execution");
            }
            return TerminalUpdate.passed(0, null, "", "");
        });
        RunExecutionService peerExecution = countingExecution(executionCount);

        WorkerMessageProcessor worker1 = processor("worker-1", blockingExecution);
        WorkerMessageProcessor worker2 = processor("worker-2", peerExecution);

        Thread ownerThread = new Thread(() -> worker1.process(messageA), "d2-owner");
        ownerThread.setDaemon(true);
        ownerThread.start();

        assertTrue(executionStarted.await(5, TimeUnit.SECONDS), "worker-1 must enter execute()");
        TestRunEntity mid = requireRun(fixture.runId());
        assertEquals(TestStatus.RUNNING, mid.status());
        assertEquals("worker-1", mid.workerId());
        assertEquals(1, executionCount.get());

        worker2.process(messageB);
        assertEquals(1, executionCount.get(), "peer must not execute under valid lease");
        assertEquals(TestStatus.RUNNING, requireRun(fixture.runId()).status());
        assertEquals("worker-1", requireRun(fixture.runId()).workerId());
        assertMessagePendingInGroup(messageB.messageId());

        releaseExecution.countDown();
        ownerThread.join(10_000);
        assertFalse(ownerThread.isAlive(), "owner thread should finish after release");

        TestRunEntity terminal = requireRun(fixture.runId());
        assertEquals(TestStatus.PASSED, terminal.status());
        assertEquals("worker-1", terminal.workerId());
        assertNull(terminal.leaseExpiresAt());
        assertFalse(runQueue.acknowledge(messageA.messageId()), "message A should already be ACKed");
        assertEquals(1, executionCount.get());

        QueueDelivery redeliveredB = awaitClaimStale("worker-2", messageB.messageId());
        worker2.process(redeliveredB);

        TestRunEntity after = requireRun(fixture.runId());
        assertEquals(TestStatus.PASSED, after.status());
        assertEquals(terminal.finishedAt(), after.finishedAt());
        assertEquals(1, executionCount.get(), "duplicate transport must not re-execute");
        assertFalse(runQueue.acknowledge(messageB.messageId()), "message B should be ACKed after terminal");
        assertNoPendingDelivery(messageB.messageId());
    }

    @Test
    void e_restartSameWorkerIdStillRespectsPgLeaseThenReclaims() {
        Fixture fixture = seedQueuedRun("e-restart");
        String messageId = runQueue.publish(fixture.runId(), fixture.testId());
        QueueDelivery delivery = readOne("worker-restart");
        assertEquals(messageId, delivery.messageId());

        ClaimResult claim = workerRunLifecycle.claimForExecution(
                fixture.runId(), fixture.testId(), "worker-restart", CLAIM_LEASE
        );
        assertEquals(ClaimOutcome.CLAIMED, claim.outcome());
        AtomicInteger executionCount = new AtomicInteger(1);
        assertEquals(TestStatus.RUNNING, requireRun(fixture.runId()).status());
        // Crash: no complete, no ACK.

        WorkerMessageProcessor restarted = processor(
                "worker-restart",
                countingExecution(executionCount)
        );

        QueueDelivery afterRestart = awaitClaimStale("worker-restart", messageId);
        int beforeMid = executionCount.get();
        restarted.process(afterRestart);
        assertEquals(beforeMid, executionCount.get(), "same worker-id must not bypass valid lease");
        assertEquals(TestStatus.RUNNING, requireRun(fixture.runId()).status());
        assertEquals("worker-restart", requireRun(fixture.runId()).workerId());
        assertMessagePendingInGroup(messageId);

        expireLease(fixture.runId());

        QueueDelivery expiredDelivery = awaitClaimStale("worker-restart", messageId);
        restarted.process(expiredDelivery);

        TestRunEntity row = requireRun(fixture.runId());
        assertEquals(TestStatus.FAILED, row.status());
        assertEquals(FailureCategory.TIMEOUT, row.failureCategory());
        assertEquals(1, executionCount.get(), "restart reclaim must not retry Python");
        assertFalse(runQueue.acknowledge(messageId));
        assertNoPendingDelivery(messageId);
    }

    private WorkerMessageProcessor processor(String workerId, RunExecutionService execution) {
        NetVerifyProperties properties = new NetVerifyProperties();
        properties.setWorkerId(workerId);
        return new WorkerMessageProcessor(
                workerRunLifecycle,
                testRepository,
                execution,
                heartbeater,
                runQueue,
                properties
        );
    }

    private static RunExecutionService countingExecution(AtomicInteger counter) {
        RunExecutionService execution = mock(RunExecutionService.class);
        when(execution.execute(any(), anyInt(), any())).thenAnswer(invocation -> {
            counter.incrementAndGet();
            return TerminalUpdate.passed(0, null, "", "");
        });
        return execution;
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
