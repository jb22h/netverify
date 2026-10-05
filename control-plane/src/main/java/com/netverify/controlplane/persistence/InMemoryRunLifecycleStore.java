package com.netverify.controlplane.persistence;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.runner.RunnerResultDto;
import com.netverify.controlplane.store.InMemoryTestStore;
import com.netverify.controlplane.store.QueueTransitionResult;
import com.netverify.controlplane.store.TerminalUpdate;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Default-profile lifecycle adapter. Latest-only: the random {@code runId} is not
 * persisted as history; transitions remain keyed by test id in
 * {@link InMemoryTestStore}.
 */
@Component
@Profile("!postgres")
public class InMemoryRunLifecycleStore implements RunLifecycleStore {

    private final InMemoryTestStore store;

    public InMemoryRunLifecycleStore(InMemoryTestStore store) {
        this.store = store;
    }

    @Override
    public QueueRunResult tryQueue(TestEntity definition, UUID runId, Instant queuedAt) {
        TestRecord skeleton = new TestRecord(
                definition.id(),
                definition.name(),
                definition.scenario(),
                definition.count(),
                definition.clients(),
                definition.createdAt()
        );
        store.ensurePresent(skeleton);
        QueueTransitionResult result = store.tryQueueForRun(definition.id());
        return switch (result) {
            case SUCCESS -> QueueRunResult.QUEUED;
            case CONFLICT, NOT_FOUND -> QueueRunResult.CONFLICT;
        };
    }

    @Override
    public boolean tryStartRunning(UUID testId, UUID runId, Instant startedAt) {
        // InMemoryTestStore stamps its own startedAt; expected-status check is what matters.
        return store.tryStartRunning(testId) == QueueTransitionResult.SUCCESS;
    }

    @Override
    public boolean tryComplete(
            UUID testId,
            UUID runId,
            TerminalUpdate update,
            Instant finishedAt
    ) {
        return store.completeRun(testId, update) == QueueTransitionResult.SUCCESS;
    }

    @Override
    public boolean tryFailQueued(
            UUID testId,
            UUID runId,
            FailureCategory category,
            String stderr,
            Instant finishedAt
    ) {
        return store.failQueued(testId, category, stderr) == QueueTransitionResult.SUCCESS;
    }

    @Override
    public Optional<RunSnapshot> findLatest(UUID testId) {
        return store.get(testId).map(this::toSnapshot);
    }

    private RunSnapshot toSnapshot(TestRecord record) {
        Object raw = record.getRunnerResult();
        RunnerResultDto result = raw instanceof RunnerResultDto dto ? dto : null;
        // Synthetic runId: in-memory store is latest-only and keyed by test id.
        return new RunSnapshot(
                record.getId(),
                record.getStatus(),
                null,
                record.getStartedAt(),
                record.getFinishedAt(),
                record.getExitCode(),
                record.getFailureCategory(),
                record.getStdout(),
                record.getStderr(),
                result
        );
    }
}
