package com.netverify.controlplane.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.netverify.controlplane.config.NetVerifyProperties;
import com.netverify.controlplane.config.QueueProperties;
import com.netverify.controlplane.queue.AutoClaimBatch;
import com.netverify.controlplane.queue.MalformedQueueMessageException;
import com.netverify.controlplane.queue.QueueDelivery;
import com.netverify.controlplane.queue.RunQueue;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RedisRunWorkerTest {

    private static final String WORKER_ID = "worker-1";
    private static final AutoClaimBatch EMPTY_BATCH = new AutoClaimBatch("0-0", List.of());

    private RunQueue runQueue;
    private WorkerMessageProcessor processor;
    private RedisRunWorker worker;

    @BeforeEach
    void setUp() {
        runQueue = mock(RunQueue.class);
        processor = mock(WorkerMessageProcessor.class);
        worker = newWorker(Duration.ZERO, null);
    }

    @AfterEach
    void tearDown() {
        worker.stop();
    }

    @Test
    void startSynchronouslyCallsEnsureConsumerGroup() throws Exception {
        CountDownLatch ensureDone = new CountDownLatch(1);
        CountDownLatch holdLoop = new CountDownLatch(1);
        doAnswer(invocation -> {
            ensureDone.countDown();
            return null;
        }).when(runQueue).ensureConsumerGroup();
        when(runQueue.claimStale(any(), any(), any(), anyInt()))
                .thenAnswer(invocation -> awaitHold(holdLoop));
        when(runQueue.read(any(), anyInt(), any())).thenReturn(List.of());

        worker.start();

        assertTrue(ensureDone.await(2, TimeUnit.SECONDS));
        verify(runQueue, times(1)).ensureConsumerGroup();
        assertTrue(worker.isRunning());
        holdLoop.countDown();
    }

    @Test
    void ensureConsumerGroupFailurePreventsRunning() {
        doThrow(new IllegalStateException("group failed")).when(runQueue).ensureConsumerGroup();

        IllegalStateException ex = assertThrows(IllegalStateException.class, worker::start);

        assertEquals("group failed", ex.getMessage());
        assertFalse(worker.isRunning());
        verify(runQueue, never()).claimStale(any(), any(), any(), anyInt());
    }

    @Test
    void consumerThreadIsNonDaemon() throws Exception {
        CountDownLatch inLoop = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        when(runQueue.claimStale(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            inLoop.countDown();
            return awaitHold(hold);
        });
        when(runQueue.read(any(), anyInt(), any())).thenReturn(List.of());

        worker.start();
        assertTrue(inLoop.await(2, TimeUnit.SECONDS));

        Thread thread = worker.consumerThreadForTest();
        assertEquals("netverify-run-worker", thread.getName());
        assertFalse(thread.isDaemon());
        hold.countDown();
    }

    @Test
    void startWhileAlreadyRunningIsIdempotent() throws Exception {
        CountDownLatch inLoop = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        when(runQueue.claimStale(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            inLoop.countDown();
            return awaitHold(hold);
        });
        when(runQueue.read(any(), anyInt(), any())).thenReturn(List.of());

        worker.start();
        assertTrue(inLoop.await(2, TimeUnit.SECONDS));
        Thread first = worker.consumerThreadForTest();

        worker.start();

        assertTrue(worker.isRunning());
        assertEquals(first, worker.consumerThreadForTest());
        verify(runQueue, times(1)).ensureConsumerGroup();
        hold.countDown();
    }

    @Test
    void loopUsesWorkerIdAndAdvancesCursorThenReadsFresh() throws Exception {
        CountDownLatch secondClaim = new CountDownLatch(1);
        AtomicReference<String> secondStartId = new AtomicReference<>();
        AtomicInteger claimCalls = new AtomicInteger();

        QueueDelivery staleDelivery = delivery("1-0");
        QueueDelivery freshDelivery = delivery("2-0");

        when(runQueue.claimStale(eq(WORKER_ID), any(), any(), eq(1))).thenAnswer(invocation -> {
            int n = claimCalls.incrementAndGet();
            String startId = invocation.getArgument(2);
            if (n == 1) {
                assertEquals("0-0", startId);
                return new AutoClaimBatch("1-0", List.of(staleDelivery));
            }
            secondStartId.set(startId);
            secondClaim.countDown();
            return interruptiblePark(50);
        });
        when(runQueue.read(eq(WORKER_ID), eq(1), any())).thenReturn(List.of(freshDelivery));

        worker.start();
        assertTrue(secondClaim.await(2, TimeUnit.SECONDS));

        assertEquals("1-0", secondStartId.get());
        verify(processor).process(staleDelivery);
        verify(processor, atLeastOnce()).process(freshDelivery);
        verify(runQueue, atLeastOnce()).read(eq(WORKER_ID), eq(1), any(Duration.class));
    }

    @Test
    void malformedMessageIsPoisonAcked() throws Exception {
        CountDownLatch ackLatch = new CountDownLatch(1);
        when(runQueue.claimStale(any(), any(), any(), anyInt()))
                .thenThrow(new MalformedQueueMessageException("9-9", "bad fields"))
                .thenAnswer(invocation -> {
                    try {
                        ackLatch.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return EMPTY_BATCH;
                    }
                    return interruptiblePark(20);
                });
        when(runQueue.acknowledge("9-9")).thenAnswer(invocation -> {
            ackLatch.countDown();
            return true;
        });
        when(runQueue.read(any(), anyInt(), any())).thenReturn(List.of());

        worker.start();
        assertTrue(ackLatch.await(2, TimeUnit.SECONDS));

        verify(runQueue).acknowledge("9-9");
        verify(processor, never()).process(any());
    }

    @Test
    void transportRuntimeExceptionContinuesLoop() throws Exception {
        CountDownLatch recovered = new CountDownLatch(1);
        AtomicInteger claims = new AtomicInteger();
        when(runQueue.claimStale(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            int n = claims.incrementAndGet();
            if (n == 1) {
                throw new IllegalStateException("redis blip");
            }
            recovered.countDown();
            return interruptiblePark(30);
        });
        when(runQueue.read(any(), anyInt(), any())).thenReturn(List.of());

        worker.start();
        assertTrue(recovered.await(2, TimeUnit.SECONDS));
        assertTrue(claims.get() >= 2);
    }

    @Test
    void processorRuntimeExceptionIsIsolated() throws Exception {
        CountDownLatch processedBoth = new CountDownLatch(2);
        QueueDelivery first = delivery("1-0");
        QueueDelivery second = delivery("2-0");

        when(runQueue.claimStale(any(), any(), any(), anyInt()))
                .thenReturn(new AutoClaimBatch("0-0", List.of(first, second)))
                .thenAnswer(invocation -> {
                    try {
                        processedBoth.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return EMPTY_BATCH;
                    }
                    return interruptiblePark(30);
                });
        when(runQueue.read(any(), anyInt(), any())).thenReturn(List.of());
        doAnswer(invocation -> {
            processedBoth.countDown();
            throw new IllegalStateException("processor boom");
        }).when(processor).process(first);
        doAnswer(invocation -> {
            processedBoth.countDown();
            return null;
        }).when(processor).process(second);

        worker.start();
        assertTrue(processedBoth.await(2, TimeUnit.SECONDS));
        verify(processor).process(first);
        verify(processor).process(second);
    }

    @Test
    void stopInterruptsJoinsClearsThreadAndIsRunningFalse() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        when(runQueue.claimStale(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            blocked.countDown();
            return interruptiblePark(10_000);
        });
        when(runQueue.read(any(), anyInt(), any())).thenReturn(List.of());

        worker.start();
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        assertTrue(worker.isRunning());

        worker.stop();

        assertFalse(worker.isRunning());
        assertNull(worker.consumerThreadForTest());
    }

    @Test
    void startRejectsSecondConsumerWhilePreviousThreadStillStopping() throws Exception {
        worker = newWorker(Duration.ZERO, Duration.ofMillis(40));

        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean releaseSticky = new AtomicBoolean(false);
        when(runQueue.claimStale(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            entered.countDown();
            // Ignore interrupt until the test explicitly releases — simulates a stuck stop.
            while (!releaseSticky.get()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    // swallow so join timeout path can observe a still-alive thread
                }
            }
            return EMPTY_BATCH;
        });
        when(runQueue.read(any(), anyInt(), any())).thenReturn(List.of());

        worker.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread sticky = worker.consumerThreadForTest();
        assertNotNull(sticky);
        assertTrue(sticky.isAlive());

        worker.stop();

        assertFalse(worker.isRunning());
        assertEquals(sticky, worker.consumerThreadForTest());
        assertTrue(sticky.isAlive());

        IllegalStateException ex = assertThrows(IllegalStateException.class, worker::start);
        assertEquals("previous worker thread is still stopping", ex.getMessage());
        verify(runQueue, times(1)).ensureConsumerGroup();

        releaseSticky.set(true);
        sticky.join(1_000);
        assertFalse(sticky.isAlive());
    }

    private RedisRunWorker newWorker(Duration transportBackoff, Duration stopJoinTimeoutOverride) {
        QueueProperties queueProperties = new QueueProperties();
        NetVerifyProperties properties = new NetVerifyProperties();
        properties.setWorkerId(WORKER_ID);
        return new RedisRunWorker(
                runQueue,
                processor,
                queueProperties,
                properties,
                transportBackoff,
                stopJoinTimeoutOverride
        );
    }

    private static AutoClaimBatch awaitHold(CountDownLatch hold) {
        try {
            hold.await(2, TimeUnit.SECONDS);
            return EMPTY_BATCH;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return EMPTY_BATCH;
        }
    }

    private static AutoClaimBatch interruptiblePark(long millis) {
        try {
            Thread.sleep(millis);
            return EMPTY_BATCH;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return EMPTY_BATCH;
        }
    }

    private static QueueDelivery delivery(String messageId) {
        return new QueueDelivery(messageId, UUID.randomUUID(), UUID.randomUUID());
    }
}
