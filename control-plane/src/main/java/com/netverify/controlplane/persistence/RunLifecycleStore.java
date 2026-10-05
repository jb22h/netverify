package com.netverify.controlplane.persistence;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.store.TerminalUpdate;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Run lifecycle state machine shared by in-memory and PostgreSQL backends.
 * {@link TestService} must not branch on profile for these transitions.
 */
public interface RunLifecycleStore {

    /**
     * Accept a new QUEUED run for the given durable definition.
     * The definition supplies the test id (and, for in-memory, skeleton fields).
     */
    QueueRunResult tryQueue(TestEntity definition, UUID runId, Instant queuedAt);

    /** Conditional QUEUED → RUNNING. */
    boolean tryStartRunning(UUID testId, UUID runId, Instant startedAt);

    /** Conditional RUNNING → PASSED/FAILED with terminal fields. */
    boolean tryComplete(UUID testId, UUID runId, TerminalUpdate update, Instant finishedAt);

    /**
     * Conditional QUEUED → FAILED (executor rejection compensation).
     * Must release the active-run slot so a later /run can succeed.
     */
    boolean tryFailQueued(
            UUID testId,
            UUID runId,
            FailureCategory category,
            String stderr,
            Instant finishedAt
    );

    /** Latest run for latest-view composition; empty means API CREATED. */
    Optional<RunSnapshot> findLatest(UUID testId);
}
