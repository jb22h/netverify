package com.netverify.controlplane.store;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * In-memory records plus every lifecycle state transition.
 *
 * <p>Concurrency contract:
 * <ul>
 *   <li>{@link ConcurrentHashMap#compute} serializes lifecycle mutation per map
 *       key, so two transitions for the same test cannot interleave.
 *   <li>Transitions into RUNNING or a terminal state write {@code status}
 *       <em>last</em>. Because the {@code TestRecord} runtime fields are
 *       volatile, a reader that observes one of those statuses also observes the
 *       field writes that preceded it.
 *   <li>The transition into QUEUED is the exception: it writes {@code status}
 *       <em>first</em> and only then discards the previous run's fields, so a
 *       reader never sees PASSED/FAILED/CANCELLED next to already-cleared
 *       results. A reader that sees QUEUED may transiently see stale timestamps
 *       or result metadata, which is harmless because the result, log and
 *       metrics endpoints reject non-terminal state.
 *   <li>None of this is an immutable-snapshot guarantee. {@code TestRecord} is
 *       mutable and readers are lock-free, so a reader holding the same
 *       reference can still see individual fields change between getter calls.
 *       Readers that need consistency must branch on {@code status} first.
 * </ul>
 *
 * <p>Callers must not drive lifecycle state through {@code TestRecord} setters
 * directly.
 */
@Component
public class InMemoryTestStore {

    private final ConcurrentMap<UUID, TestRecord> records = new ConcurrentHashMap<>();

    public void save(TestRecord record) {
        records.put(record.getId(), record);
    }

    /**
     * Installs a CREATED runtime skeleton only if this test id has no runtime row
     * yet. Never overwrites an existing QUEUED, RUNNING, or terminal record.
     *
     * @return the pre-existing record, or {@code createdSkeleton} if inserted
     */
    public TestRecord ensurePresent(TestRecord createdSkeleton) {
        TestRecord existing = records.putIfAbsent(createdSkeleton.getId(), createdSkeleton);
        return existing != null ? existing : createdSkeleton;
    }

    public Optional<TestRecord> get(UUID id) {
        return Optional.ofNullable(records.get(id));
    }

    public List<TestRecord> list() {
        List<TestRecord> list = new ArrayList<>(records.values());
        list.sort(Comparator.comparing(TestRecord::getCreatedAt).thenComparing(TestRecord::getId));
        return List.copyOf(list);
    }

    /**
     * Transition CREATED / PASSED / FAILED / CANCELLED → QUEUED, discarding any
     * result metadata from a previous run so a queued test never reports a stale
     * outcome. Rejects when already QUEUED or RUNNING (CONFLICT).
     *
     * <p>QUEUED is published <em>before</em> the old fields are cleared. Clearing
     * first would let a lock-free reader see a terminal status alongside blanked
     * results, which is a worse lie than a queued record that still shows the
     * previous run's metadata for a moment.
     */
    public QueueTransitionResult tryQueueForRun(UUID id) {
        return transition(id, status -> switch (status) {
            case CREATED, PASSED, FAILED, CANCELLED -> true;
            case QUEUED, RUNNING -> false;
        }, record -> {
            record.setStatus(TestStatus.QUEUED);
            clearRunFields(record);
            // Re-published as a barrier so the cleared fields are visible to any
            // reader that observes this second write.
            record.setStatus(TestStatus.QUEUED);
        });
    }

    /**
     * Transition QUEUED → RUNNING, stamping startedAt. Terminal fields are
     * cleared before the status write so a reader seeing RUNNING never sees an
     * earlier run's result.
     */
    public QueueTransitionResult tryStartRunning(UUID id) {
        return transition(id, status -> status == TestStatus.QUEUED, record -> {
            clearRunFields(record);
            record.setStartedAt(Instant.now());
            record.setStatus(TestStatus.RUNNING);
        });
    }

    /**
     * Transition RUNNING → PASSED / FAILED. Every terminal field is written
     * before the status write that publishes them.
     */
    public QueueTransitionResult completeRun(UUID id, TerminalUpdate update) {
        return transition(id, status -> status == TestStatus.RUNNING, record -> {
            record.setExitCode(update.exitCode());
            record.setRunnerResult(update.runnerResult());
            record.setStdout(update.stdout());
            record.setStderr(update.stderr());
            record.setFailureCategory(update.failureCategory());
            record.setFinishedAt(Instant.now());
            record.setStatus(update.status());
        });
    }

    /**
     * Transition QUEUED → FAILED without ever running. Used when the bounded
     * executor rejects submission, so a record cannot stay QUEUED.
     */
    public QueueTransitionResult failQueued(UUID id, FailureCategory category, String message) {
        return transition(id, status -> status == TestStatus.QUEUED, record -> {
            record.setExitCode(null);
            record.setRunnerResult(null);
            record.setStdout("");
            record.setStderr(message == null ? "" : message);
            record.setFailureCategory(category);
            record.setFinishedAt(Instant.now());
            record.setStatus(TestStatus.FAILED);
        });
    }

    /** Resets run/terminal metadata; callers write {@code status} afterwards. */
    private static void clearRunFields(TestRecord record) {
        record.setStartedAt(null);
        record.setFinishedAt(null);
        record.setExitCode(null);
        record.setRunnerResult(null);
        record.setStdout(null);
        record.setStderr(null);
        record.setFailureCategory(null);
    }

    private QueueTransitionResult transition(
            UUID id,
            StatusPredicate allowedFrom,
            Consumer<TestRecord> mutation
    ) {
        final QueueTransitionResult[] outcome = {QueueTransitionResult.NOT_FOUND};
        records.compute(id, (key, existing) -> {
            if (existing == null) {
                outcome[0] = QueueTransitionResult.NOT_FOUND;
                return null;
            }
            if (!allowedFrom.test(existing.getStatus())) {
                outcome[0] = QueueTransitionResult.CONFLICT;
                return existing;
            }
            mutation.accept(existing);
            outcome[0] = QueueTransitionResult.SUCCESS;
            return existing;
        });
        return outcome[0];
    }

    @FunctionalInterface
    private interface StatusPredicate {
        boolean test(TestStatus status);
    }
}
