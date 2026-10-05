package com.netverify.controlplane.persistence;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Default-profile run state and history. Each test has its own {@link RunBucket}
 * that owns rows and the monitor for compound lifecycle operations.
 */
@Component
@Profile("!postgres")
public class InMemoryRunLifecycleStore implements RunLifecycleStore {

    private static final Comparator<RunRow> NEWEST_FIRST = Comparator
            .comparing(RunRow::queuedAt)
            .reversed()
            .thenComparing(row -> row.runId().toString(), Comparator.reverseOrder());

    private final ConcurrentHashMap<UUID, RunBucket> runsByTest = new ConcurrentHashMap<>();

    @Override
    public QueueRunResult tryQueue(TestEntity definition, UUID runId, Instant queuedAt) {
        // Only queue may create a per-test bucket.
        return runsByTest
                .computeIfAbsent(definition.id(), ignored -> new RunBucket())
                .tryQueue(runId, queuedAt);
    }

    @Override
    public boolean tryStartRunning(UUID testId, UUID runId, Instant startedAt) {
        RunBucket bucket = runsByTest.get(testId);
        return bucket != null && bucket.tryStartRunning(runId, startedAt);
    }

    @Override
    public boolean tryComplete(
            UUID testId,
            UUID runId,
            TerminalUpdate update,
            Instant finishedAt
    ) {
        RunBucket bucket = runsByTest.get(testId);
        return bucket != null && bucket.tryComplete(runId, update, finishedAt);
    }

    @Override
    public boolean tryFailQueued(
            UUID testId,
            UUID runId,
            FailureCategory category,
            String stderr,
            Instant finishedAt
    ) {
        RunBucket bucket = runsByTest.get(testId);
        return bucket != null && bucket.tryFailQueued(runId, category, stderr, finishedAt);
    }

    @Override
    public Optional<RunSnapshot> findLatest(UUID testId) {
        RunBucket bucket = runsByTest.get(testId);
        if (bucket == null) {
            return Optional.empty();
        }
        return bucket.findLatest();
    }

    @Override
    public List<RunSnapshot> listRuns(UUID testId) {
        RunBucket bucket = runsByTest.get(testId);
        if (bucket == null) {
            return List.of();
        }
        return bucket.listRuns();
    }

    /** Per-test run rows + synchronization monitor. */
    static final class RunBucket {

        private final Map<UUID, RunRow> runs = new HashMap<>();

        synchronized QueueRunResult tryQueue(UUID runId, Instant queuedAt) {
            boolean active = runs.values().stream().anyMatch(RunRow::isActive);
            if (active) {
                return QueueRunResult.CONFLICT;
            }
            if (runs.containsKey(runId)) {
                // Duplicate PK / historical reuse — not an active-run conflict.
                throw new IllegalStateException(
                        "runId already exists for this test: " + runId
                );
            }
            runs.put(runId, RunRow.queued(runId, queuedAt));
            return QueueRunResult.QUEUED;
        }

        synchronized boolean tryStartRunning(UUID runId, Instant startedAt) {
            RunRow row = runs.get(runId);
            if (row == null || row.status() != TestStatus.QUEUED) {
                return false;
            }
            runs.put(runId, row.toRunning(startedAt));
            return true;
        }

        synchronized boolean tryComplete(UUID runId, TerminalUpdate update, Instant finishedAt) {
            RunRow row = runs.get(runId);
            if (row == null || row.status() != TestStatus.RUNNING) {
                return false;
            }
            runs.put(runId, row.toTerminal(update, finishedAt));
            return true;
        }

        synchronized boolean tryFailQueued(
                UUID runId,
                FailureCategory category,
                String stderr,
                Instant finishedAt
        ) {
            RunRow row = runs.get(runId);
            if (row == null || row.status() != TestStatus.QUEUED) {
                return false;
            }
            runs.put(
                    runId,
                    row.toFailedQueued(
                            category,
                            stderr == null ? "" : stderr,
                            finishedAt
                    )
            );
            return true;
        }

        synchronized Optional<RunSnapshot> findLatest() {
            return runs.values().stream()
                    .sorted(NEWEST_FIRST)
                    .findFirst()
                    .map(RunRow::toSnapshot);
        }

        synchronized List<RunSnapshot> listRuns() {
            List<RunSnapshot> ordered = new ArrayList<>();
            runs.values().stream()
                    .sorted(NEWEST_FIRST)
                    .map(RunRow::toSnapshot)
                    .forEach(ordered::add);
            return List.copyOf(ordered);
        }
    }

    /** Mutable-free run row stored inside a bucket. */
    private record RunRow(
            UUID runId,
            TestStatus status,
            Instant queuedAt,
            Instant startedAt,
            Instant finishedAt,
            Integer exitCode,
            FailureCategory failureCategory,
            String stdout,
            String stderr,
            RunnerResultDto runnerResult
    ) {
        static RunRow queued(UUID runId, Instant queuedAt) {
            return new RunRow(
                    runId, TestStatus.QUEUED, queuedAt,
                    null, null, null, null, null, null, null
            );
        }

        boolean isActive() {
            return status == TestStatus.QUEUED || status == TestStatus.RUNNING;
        }

        RunRow toRunning(Instant startedAt) {
            return new RunRow(
                    runId, TestStatus.RUNNING, queuedAt, startedAt,
                    null, null, null, null, null, null
            );
        }

        RunRow toTerminal(TerminalUpdate update, Instant finishedAt) {
            RunnerResultDto result = update.runnerResult() instanceof RunnerResultDto dto
                    ? dto
                    : null;
            return new RunRow(
                    runId,
                    update.status(),
                    queuedAt,
                    startedAt,
                    finishedAt,
                    update.exitCode(),
                    update.failureCategory(),
                    update.stdout(),
                    update.stderr(),
                    result
            );
        }

        RunRow toFailedQueued(FailureCategory category, String stderr, Instant finishedAt) {
            return new RunRow(
                    runId, TestStatus.FAILED, queuedAt, null, finishedAt,
                    null, category, "", stderr, null
            );
        }

        RunSnapshot toSnapshot() {
            return new RunSnapshot(
                    runId,
                    status,
                    queuedAt,
                    startedAt,
                    finishedAt,
                    exitCode,
                    failureCategory,
                    stdout,
                    stderr,
                    runnerResult
            );
        }
    }
}
