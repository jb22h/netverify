package com.netverify.controlplane.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryRunLifecycleStoreTest {

    /** Lexically low/high for deterministic queuedAt-tie ordering. */
    private static final UUID TIE_LOW_TEXT =
            UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID TIE_HIGH_TEXT =
            UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff");

    private InMemoryRunLifecycleStore lifecycle;
    private TestEntity definition;

    @BeforeEach
    void setUp() {
        lifecycle = new InMemoryRunLifecycleStore();
        definition = new TestEntity(
                UUID.randomUUID(), "t", "basic_ping_pong", 3, null, Instant.now()
        );
    }

    @Test
    void listRunsEmptyWhenNeverQueued() {
        assertEquals(List.of(), lifecycle.listRuns(definition.id()));
        assertTrue(lifecycle.findLatest(definition.id()).isEmpty());
    }

    @Test
    void tryQueueAcceptsAndRetainsExactRunId() {
        UUID runId = UUID.randomUUID();
        Instant queuedAt = Instant.parse("2026-01-01T00:00:00Z");

        assertEquals(QueueRunResult.QUEUED, lifecycle.tryQueue(definition, runId, queuedAt));

        RunSnapshot latest = lifecycle.findLatest(definition.id()).orElseThrow();
        assertEquals(runId, latest.runId());
        assertEquals(TestStatus.QUEUED, latest.status());
        assertEquals(queuedAt, latest.queuedAt());
    }

    @Test
    void secondActiveQueueConflicts() {
        UUID run1 = UUID.randomUUID();
        UUID run2 = UUID.randomUUID();
        Instant queuedAt = Instant.now();

        assertEquals(QueueRunResult.QUEUED, lifecycle.tryQueue(definition, run1, queuedAt));
        assertEquals(QueueRunResult.CONFLICT, lifecycle.tryQueue(definition, run2, queuedAt));
        assertEquals(1, lifecycle.listRuns(definition.id()).size());
        assertEquals(run1, lifecycle.findLatest(definition.id()).orElseThrow().runId());
    }

    @Test
    void queuedToRunningUsesSuppliedStartedAt() {
        UUID runId = UUID.randomUUID();
        Instant queuedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant startedAt = Instant.parse("2026-01-01T00:00:01Z");
        lifecycle.tryQueue(definition, runId, queuedAt);

        assertTrue(lifecycle.tryStartRunning(definition.id(), runId, startedAt));
        RunSnapshot latest = lifecycle.findLatest(definition.id()).orElseThrow();
        assertEquals(TestStatus.RUNNING, latest.status());
        assertEquals(startedAt, latest.startedAt());
    }

    @Test
    void staleDuplicateStartFails() {
        UUID runId = UUID.randomUUID();
        lifecycle.tryQueue(definition, runId, Instant.now());
        assertTrue(lifecycle.tryStartRunning(definition.id(), runId, Instant.now()));
        assertFalse(lifecycle.tryStartRunning(definition.id(), runId, Instant.now()));
        assertFalse(lifecycle.tryStartRunning(
                definition.id(), UUID.randomUUID(), Instant.now()
        ));
    }

    @Test
    void runningToPassedAppliesTerminalFields() {
        UUID runId = UUID.randomUUID();
        Instant finishedAt = Instant.parse("2026-01-01T00:00:02Z");
        lifecycle.tryQueue(definition, runId, Instant.now());
        lifecycle.tryStartRunning(definition.id(), runId, Instant.now());

        assertTrue(lifecycle.tryComplete(
                definition.id(),
                runId,
                TerminalUpdate.passed(0, null, "out", "err"),
                finishedAt
        ));
        RunSnapshot latest = lifecycle.findLatest(definition.id()).orElseThrow();
        assertEquals(TestStatus.PASSED, latest.status());
        assertEquals(0, latest.exitCode());
        assertEquals("out", latest.stdout());
        assertEquals("err", latest.stderr());
        assertEquals(finishedAt, latest.finishedAt());
    }

    @Test
    void completeRequiresRunning() {
        UUID runId = UUID.randomUUID();
        lifecycle.tryQueue(definition, runId, Instant.now());
        assertFalse(lifecycle.tryComplete(
                definition.id(),
                runId,
                TerminalUpdate.passed(0, null, "", ""),
                Instant.now()
        ));
        lifecycle.tryStartRunning(definition.id(), runId, Instant.now());
        assertTrue(lifecycle.tryComplete(
                definition.id(),
                runId,
                TerminalUpdate.failed(FailureCategory.VERIFICATION, 1, null, "", "x"),
                Instant.now()
        ));
        assertEquals(
                TestStatus.FAILED,
                lifecycle.findLatest(definition.id()).orElseThrow().status()
        );
    }

    @Test
    void staleDuplicateCompletionFails() {
        UUID runId = UUID.randomUUID();
        lifecycle.tryQueue(definition, runId, Instant.now());
        lifecycle.tryStartRunning(definition.id(), runId, Instant.now());
        assertTrue(lifecycle.tryComplete(
                definition.id(),
                runId,
                TerminalUpdate.passed(0, null, "", ""),
                Instant.now()
        ));
        assertFalse(lifecycle.tryComplete(
                definition.id(),
                runId,
                TerminalUpdate.failed(FailureCategory.VERIFICATION, 1, null, "", "nope"),
                Instant.now()
        ));
        assertEquals(
                TestStatus.PASSED,
                lifecycle.findLatest(definition.id()).orElseThrow().status()
        );
    }

    @Test
    void failQueuedOnlyFromQueuedAndReleasesSlot() {
        UUID run1 = UUID.randomUUID();
        Instant finishedAt = Instant.parse("2026-01-01T00:00:03Z");
        lifecycle.tryQueue(definition, run1, Instant.now());
        assertTrue(lifecycle.tryFailQueued(
                definition.id(), run1, FailureCategory.LAUNCH, "rejected", finishedAt
        ));
        RunSnapshot failed = lifecycle.findLatest(definition.id()).orElseThrow();
        assertEquals(TestStatus.FAILED, failed.status());
        assertEquals(FailureCategory.LAUNCH, failed.failureCategory());
        assertEquals("rejected", failed.stderr());
        assertEquals(finishedAt, failed.finishedAt());

        assertFalse(lifecycle.tryFailQueued(
                definition.id(), run1, FailureCategory.LAUNCH, "again", Instant.now()
        ));

        UUID run2 = UUID.randomUUID();
        assertEquals(
                QueueRunResult.QUEUED,
                lifecycle.tryQueue(definition, run2, Instant.now())
        );
        lifecycle.tryStartRunning(definition.id(), run2, Instant.now());
        assertFalse(lifecycle.tryFailQueued(
                definition.id(), run2, FailureCategory.LAUNCH, "running", Instant.now()
        ));
    }

    @Test
    void multipleCompletedRunsRetainedWithDistinctIds() {
        UUID runOlder = UUID.randomUUID();
        UUID runNewer = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T00:00:01Z");

        complete(runOlder, t0, TerminalUpdate.failed(
                FailureCategory.VERIFICATION, 1, null, "", "old"
        ));
        complete(runNewer, t1, TerminalUpdate.passed(0, null, "new", ""));

        List<RunSnapshot> history = lifecycle.listRuns(definition.id());
        assertEquals(2, history.size());
        assertNotEquals(history.get(0).runId(), history.get(1).runId());
        assertEquals(runNewer, history.get(0).runId());
        assertEquals(runOlder, history.get(1).runId());
    }

    @Test
    void listRunsNewestFirstAndFindLatestMatchesFirst() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-01-01T00:00:10Z");
        UUID runOld = UUID.randomUUID();
        UUID runNew = UUID.randomUUID();
        complete(runOld, older, TerminalUpdate.passed(0, null, "old", ""));
        complete(runNew, newer, TerminalUpdate.passed(0, null, "new", ""));

        List<RunSnapshot> history = lifecycle.listRuns(definition.id());
        assertEquals(List.of(runNew, runOld), history.stream().map(RunSnapshot::runId).toList());
        assertEquals(history.get(0), lifecycle.findLatest(definition.id()).orElseThrow());
    }

    @Test
    void canonicalUuidTextTieBreakWhenQueuedAtEqual() {
        Instant same = Instant.parse("2026-01-01T00:00:00Z");
        assertTrue(TIE_LOW_TEXT.toString().compareTo(TIE_HIGH_TEXT.toString()) < 0);

        complete(TIE_LOW_TEXT, same, TerminalUpdate.passed(0, null, "low", ""));
        complete(TIE_HIGH_TEXT, same, TerminalUpdate.passed(0, null, "high", ""));

        List<UUID> expected = List.of(TIE_HIGH_TEXT, TIE_LOW_TEXT);
        List<UUID> actual = lifecycle.listRuns(definition.id()).stream()
                .map(RunSnapshot::runId)
                .toList();
        assertEquals(expected, actual);
        assertEquals("high", lifecycle.findLatest(definition.id()).orElseThrow().stdout());
    }

    @Test
    void duplicateHistoricalRunIdIsInvariantViolation() {
        UUID runId = UUID.randomUUID();
        Instant queuedAt = Instant.parse("2026-01-01T00:00:00Z");
        complete(runId, queuedAt, TerminalUpdate.passed(0, null, "only", ""));
        List<RunSnapshot> before = lifecycle.listRuns(definition.id());

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> lifecycle.tryQueue(definition, runId, Instant.now())
        );
        assertTrue(ex.getMessage().contains(runId.toString()));
        assertEquals(before, lifecycle.listRuns(definition.id()));
        assertEquals(1, lifecycle.listRuns(definition.id()).size());
        assertEquals("only", lifecycle.findLatest(definition.id()).orElseThrow().stdout());
    }

    @Test
    void concurrentTryQueueAcceptsExactlyOne() throws Exception {
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(5, TimeUnit.SECONDS));
                    QueueRunResult result = lifecycle.tryQueue(
                            definition, UUID.randomUUID(), Instant.now()
                    );
                    if (result == QueueRunResult.QUEUED) {
                        accepted.incrementAndGet();
                    } else {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, accepted.get());
        assertEquals(threads - 1, conflicts.get());
        assertEquals(1, lifecycle.listRuns(definition.id()).size());
    }

    @Test
    void differentTestsOperateIndependently() {
        TestEntity other = new TestEntity(
                UUID.randomUUID(), "other", "basic_ping_pong", 1, null, Instant.now()
        );
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        assertEquals(QueueRunResult.QUEUED, lifecycle.tryQueue(definition, runA, Instant.now()));
        assertEquals(QueueRunResult.QUEUED, lifecycle.tryQueue(other, runB, Instant.now()));
        assertEquals(1, lifecycle.listRuns(definition.id()).size());
        assertEquals(1, lifecycle.listRuns(other.id()).size());
    }

    @Test
    void terminalUpdateValidationPreserved() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerminalUpdate(TestStatus.RUNNING, 0, null, "", "", null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerminalUpdate(TestStatus.FAILED, 1, null, "", "", null)
        );
    }

    private void complete(UUID runId, Instant queuedAt, TerminalUpdate update) {
        assertEquals(QueueRunResult.QUEUED, lifecycle.tryQueue(definition, runId, queuedAt));
        assertTrue(lifecycle.tryStartRunning(definition.id(), runId, queuedAt));
        assertTrue(lifecycle.tryComplete(definition.id(), runId, update, queuedAt));
    }
}
