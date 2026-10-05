package com.netverify.controlplane.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.store.InMemoryTestStore;
import com.netverify.controlplane.store.TerminalUpdate;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryRunLifecycleStoreTest {

    private InMemoryRunLifecycleStore lifecycle;
    private TestEntity definition;

    @BeforeEach
    void setUp() {
        lifecycle = new InMemoryRunLifecycleStore(new InMemoryTestStore());
        definition = new TestEntity(
                UUID.randomUUID(), "t", "basic_ping_pong", 3, null, Instant.now()
        );
    }

    @Test
    void tryQueueAcceptsOnceThenConflicts() {
        UUID run1 = UUID.randomUUID();
        UUID run2 = UUID.randomUUID();
        Instant queuedAt = Instant.now();

        assertEquals(QueueRunResult.QUEUED, lifecycle.tryQueue(definition, run1, queuedAt));
        assertEquals(QueueRunResult.CONFLICT, lifecycle.tryQueue(definition, run2, queuedAt));
        assertEquals(TestStatus.QUEUED, lifecycle.findLatest(definition.id()).orElseThrow().status());
    }

    @Test
    void failQueuedReleasesSlotForRetry() {
        UUID run1 = UUID.randomUUID();
        assertEquals(
                QueueRunResult.QUEUED,
                lifecycle.tryQueue(definition, run1, Instant.now())
        );
        assertTrue(lifecycle.tryFailQueued(
                definition.id(), run1, FailureCategory.LAUNCH, "rejected", Instant.now()
        ));
        assertEquals(TestStatus.FAILED, lifecycle.findLatest(definition.id()).orElseThrow().status());

        UUID run2 = UUID.randomUUID();
        assertEquals(
                QueueRunResult.QUEUED,
                lifecycle.tryQueue(definition, run2, Instant.now())
        );
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
        assertTrue(lifecycle.tryStartRunning(definition.id(), runId, Instant.now()));
        assertTrue(lifecycle.tryComplete(
                definition.id(),
                runId,
                TerminalUpdate.passed(0, null, "", ""),
                Instant.now()
        ));
        assertEquals(TestStatus.PASSED, lifecycle.findLatest(definition.id()).orElseThrow().status());
    }
}
