package com.netverify.controlplane.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryTestStoreTest {

    private InMemoryTestStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryTestStore();
    }

    @Test
    void tryQueueFromCreatedSucceeds() {
        TestRecord record = newRecord();
        store.save(record);

        assertEquals(QueueTransitionResult.SUCCESS, store.tryQueueForRun(record.getId()));
        assertEquals(TestStatus.QUEUED, store.get(record.getId()).orElseThrow().getStatus());
    }

    @Test
    void tryQueueWhenAlreadyQueuedConflicts() {
        TestRecord record = newRecord();
        store.save(record);
        assertEquals(QueueTransitionResult.SUCCESS, store.tryQueueForRun(record.getId()));
        assertEquals(QueueTransitionResult.CONFLICT, store.tryQueueForRun(record.getId()));
    }

    @Test
    void tryQueueWhenRunningConflicts() {
        TestRecord record = newRecord();
        store.save(record);
        record.setStatus(TestStatus.RUNNING);

        assertEquals(QueueTransitionResult.CONFLICT, store.tryQueueForRun(record.getId()));
        assertEquals(TestStatus.RUNNING, store.get(record.getId()).orElseThrow().getStatus());
    }

    @Test
    void tryQueueMissingReturnsNotFound() {
        assertEquals(
                QueueTransitionResult.NOT_FOUND,
                store.tryQueueForRun(UUID.randomUUID())
        );
    }

    @Test
    void tryQueueFromPassedSucceeds() {
        TestRecord record = newRecord();
        store.save(record);
        record.setStatus(TestStatus.PASSED);

        assertEquals(QueueTransitionResult.SUCCESS, store.tryQueueForRun(record.getId()));
        assertEquals(TestStatus.QUEUED, store.get(record.getId()).orElseThrow().getStatus());
    }

    @Test
    void concurrentTryQueueOnlyOneSucceeds() throws Exception {
        TestRecord record = newRecord();
        store.save(record);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit(() -> {
                start.await();
                QueueTransitionResult result = store.tryQueueForRun(record.getId());
                if (result == QueueTransitionResult.SUCCESS) {
                    successes.incrementAndGet();
                } else if (result == QueueTransitionResult.CONFLICT) {
                    conflicts.incrementAndGet();
                }
                return null;
            });
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, successes.get());
        assertEquals(threads - 1, conflicts.get());
        assertEquals(TestStatus.QUEUED, store.get(record.getId()).orElseThrow().getStatus());
    }

    private static TestRecord newRecord() {
        return new TestRecord(
                UUID.randomUUID(),
                "t",
                "basic_ping_pong",
                10,
                null,
                Instant.now()
        );
    }
}
