package com.netverify.controlplane.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryTestStoreLifecycleTest {

    private InMemoryTestStore store;
    private TestRecord record;

    @BeforeEach
    void setUp() {
        store = new InMemoryTestStore();
        record = new TestRecord(
                UUID.randomUUID(), "t", "basic_ping_pong", 10, null, Instant.now()
        );
        store.save(record);
    }

    @Test
    void queuedToRunningStampsStartedAt() {
        assertEquals(QueueTransitionResult.SUCCESS, store.tryQueueForRun(record.getId()));

        assertEquals(QueueTransitionResult.SUCCESS, store.tryStartRunning(record.getId()));
        assertEquals(TestStatus.RUNNING, record.getStatus());
        assertNotNull(record.getStartedAt());
        assertNull(record.getFinishedAt());
    }

    @Test
    void startRunningRejectsNonQueuedState() {
        assertEquals(QueueTransitionResult.CONFLICT, store.tryStartRunning(record.getId()));
        assertEquals(TestStatus.CREATED, record.getStatus());
    }

    @Test
    void startRunningReturnsNotFoundForUnknownId() {
        assertEquals(QueueTransitionResult.NOT_FOUND, store.tryStartRunning(UUID.randomUUID()));
    }

    @Test
    void completeRunAppliesEveryTerminalFieldTogether() {
        store.tryQueueForRun(record.getId());
        store.tryStartRunning(record.getId());

        QueueTransitionResult result = store.completeRun(
                record.getId(),
                TerminalUpdate.failed(FailureCategory.VERIFICATION, 1, "result", "out", "err")
        );

        assertEquals(QueueTransitionResult.SUCCESS, result);
        assertEquals(TestStatus.FAILED, record.getStatus());
        assertEquals(FailureCategory.VERIFICATION, record.getFailureCategory());
        assertEquals(1, record.getExitCode());
        assertEquals("result", record.getRunnerResult());
        assertEquals("out", record.getStdout());
        assertEquals("err", record.getStderr());
        assertNotNull(record.getFinishedAt());
    }

    @Test
    void completeRunRequiresRunningState() {
        store.tryQueueForRun(record.getId());

        assertEquals(
                QueueTransitionResult.CONFLICT,
                store.completeRun(record.getId(), TerminalUpdate.passed(0, null, "", ""))
        );
        assertEquals(TestStatus.QUEUED, record.getStatus());
    }

    @Test
    void rerunClearsPreviousTerminalFields() {
        store.tryQueueForRun(record.getId());
        store.tryStartRunning(record.getId());
        store.completeRun(
                record.getId(),
                TerminalUpdate.failed(FailureCategory.TIMEOUT, null, "old", "old-out", "old-err")
        );

        assertEquals(QueueTransitionResult.SUCCESS, store.tryQueueForRun(record.getId()));
        assertEquals(QueueTransitionResult.SUCCESS, store.tryStartRunning(record.getId()));

        assertNull(record.getFinishedAt());
        assertNull(record.getExitCode());
        assertNull(record.getRunnerResult());
        assertNull(record.getStdout());
        assertNull(record.getStderr());
        assertNull(record.getFailureCategory());
    }

    @Test
    void queueingRerunClearsStaleTerminalFieldsImmediately() {
        store.tryQueueForRun(record.getId());
        store.tryStartRunning(record.getId());
        store.completeRun(
                record.getId(),
                TerminalUpdate.failed(FailureCategory.TIMEOUT, 3, "old", "old-out", "old-err")
        );

        assertEquals(QueueTransitionResult.SUCCESS, store.tryQueueForRun(record.getId()));

        assertEquals(TestStatus.QUEUED, record.getStatus());
        assertNull(record.getStartedAt());
        assertNull(record.getFinishedAt());
        assertNull(record.getExitCode());
        assertNull(record.getRunnerResult());
        assertNull(record.getStdout());
        assertNull(record.getStderr());
        assertNull(record.getFailureCategory());
    }

    @Test
    void failQueuedMovesQueuedRecordToFailed() {
        store.tryQueueForRun(record.getId());

        QueueTransitionResult result =
                store.failQueued(record.getId(), FailureCategory.LAUNCH, "rejected");

        assertEquals(QueueTransitionResult.SUCCESS, result);
        assertEquals(TestStatus.FAILED, record.getStatus());
        assertEquals(FailureCategory.LAUNCH, record.getFailureCategory());
        assertEquals("rejected", record.getStderr());
        assertNotNull(record.getFinishedAt());
    }

    @Test
    void failQueuedRejectsRunningRecord() {
        store.tryQueueForRun(record.getId());
        store.tryStartRunning(record.getId());

        assertEquals(
                QueueTransitionResult.CONFLICT,
                store.failQueued(record.getId(), FailureCategory.LAUNCH, "rejected")
        );
        assertEquals(TestStatus.RUNNING, record.getStatus());
    }

    @Test
    void terminalUpdateRejectsNonTerminalStatus() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerminalUpdate(TestStatus.RUNNING, 0, null, "", "", null)
        );
    }

    @Test
    void failedTerminalUpdateRequiresCategory() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerminalUpdate(TestStatus.FAILED, 1, null, "", "", null)
        );
    }
}
