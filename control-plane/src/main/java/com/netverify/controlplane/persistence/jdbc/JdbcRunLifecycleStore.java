package com.netverify.controlplane.persistence.jdbc;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.persistence.QueueRunResult;
import com.netverify.controlplane.persistence.RunLifecycleStore;
import com.netverify.controlplane.persistence.RunSnapshot;
import com.netverify.controlplane.persistence.TestEntity;
import com.netverify.controlplane.persistence.TestRunEntity;
import com.netverify.controlplane.persistence.TestRunRepository;
import com.netverify.controlplane.persistence.TerminalUpdate;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("postgres")
public class JdbcRunLifecycleStore implements RunLifecycleStore {

    private final TestRunRepository testRunRepository;

    public JdbcRunLifecycleStore(TestRunRepository testRunRepository) {
        this.testRunRepository = testRunRepository;
    }

    @Override
    public QueueRunResult tryQueue(TestEntity definition, UUID runId, Instant queuedAt) {
        int inserted = testRunRepository.tryInsertQueued(runId, definition.id(), queuedAt);
        return inserted == 1 ? QueueRunResult.QUEUED : QueueRunResult.CONFLICT;
    }

    @Override
    public boolean tryStartRunning(UUID testId, UUID runId, Instant startedAt) {
        return testRunRepository.tryMarkRunning(runId, startedAt) == 1;
    }

    @Override
    public boolean tryComplete(
            UUID testId,
            UUID runId,
            TerminalUpdate update,
            Instant finishedAt
    ) {
        RunnerResultDto result = update.runnerResult() instanceof RunnerResultDto dto ? dto : null;
        return testRunRepository.tryComplete(
                runId,
                update.status(),
                update.exitCode(),
                update.failureCategory(),
                update.stdout(),
                update.stderr(),
                result,
                finishedAt
        ) == 1;
    }

    @Override
    public boolean tryFailQueued(
            UUID testId,
            UUID runId,
            FailureCategory category,
            String stderr,
            Instant finishedAt
    ) {
        return testRunRepository.tryFailQueued(runId, category, stderr, finishedAt) == 1;
    }

    @Override
    public Optional<RunSnapshot> findLatest(UUID testId) {
        return testRunRepository.findLatestByTestId(testId).map(this::toSnapshot);
    }

    @Override
    public List<RunSnapshot> listRuns(UUID testId) {
        return testRunRepository.findAllByTestId(testId).stream()
                .map(this::toSnapshot)
                .toList();
    }

    private RunSnapshot toSnapshot(TestRunEntity entity) {
        return new RunSnapshot(
                entity.id(),
                entity.status(),
                entity.queuedAt(),
                entity.startedAt(),
                entity.finishedAt(),
                entity.exitCode(),
                entity.failureCategory(),
                entity.stdout(),
                entity.stderr(),
                entity.runnerResult()
        );
    }
}
