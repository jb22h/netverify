package com.netverify.controlplane.store;

import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryTestStore {

    private final ConcurrentMap<UUID, TestRecord> records = new ConcurrentHashMap<>();

    public void save(TestRecord record) {
        records.put(record.getId(), record);
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
     * Atomically transition CREATED / PASSED / FAILED / CANCELLED → QUEUED.
     * Rejects when already QUEUED or RUNNING (CONFLICT).
     */
    public QueueTransitionResult tryQueueForRun(UUID id) {
        final QueueTransitionResult[] outcome = {QueueTransitionResult.NOT_FOUND};
        records.compute(id, (key, existing) -> {
            if (existing == null) {
                outcome[0] = QueueTransitionResult.NOT_FOUND;
                return null;
            }
            TestStatus status = existing.getStatus();
            if (status == TestStatus.QUEUED || status == TestStatus.RUNNING) {
                outcome[0] = QueueTransitionResult.CONFLICT;
                return existing;
            }
            if (status == TestStatus.CREATED
                    || status == TestStatus.PASSED
                    || status == TestStatus.FAILED
                    || status == TestStatus.CANCELLED) {
                existing.setStatus(TestStatus.QUEUED);
                outcome[0] = QueueTransitionResult.SUCCESS;
                return existing;
            }
            outcome[0] = QueueTransitionResult.CONFLICT;
            return existing;
        });
        return outcome[0];
    }
}
