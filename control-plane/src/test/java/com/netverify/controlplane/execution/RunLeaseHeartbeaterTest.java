package com.netverify.controlplane.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.netverify.controlplane.persistence.worker.WorkerRunLifecycle;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RunLeaseHeartbeaterTest {

    @Test
    void renewRepeatsUntilHandleClosed() {
        WorkerRunLifecycle lifecycle = mock(WorkerRunLifecycle.class);
        when(lifecycle.renewLease(any(), any(), any())).thenReturn(true);

        CapturedTick captured = captureTick(lifecycle);
        UUID runId = UUID.randomUUID();
        LeaseHandle handle = captured.heartbeater().start(runId, "worker-a");

        captured.tick().run();
        captured.tick().run();

        verify(lifecycle, times(2)).renewLease(
                eq(runId),
                eq("worker-a"),
                eq(RunLeaseHeartbeater.LEASE_HORIZON)
        );

        handle.close();
        captured.tick().run();
        verify(lifecycle, times(2)).renewLease(any(), any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void falseRenewalMarksOwnershipLostAndCancelsFuture() {
        WorkerRunLifecycle lifecycle = mock(WorkerRunLifecycle.class);
        when(lifecycle.renewLease(any(), any(), any())).thenReturn(false);

        ScheduledFuture future = mock(ScheduledFuture.class);
        CapturedTick captured = captureTick(lifecycle, future);
        LeaseHandle handle = captured.heartbeater().start(UUID.randomUUID(), "worker-a");

        captured.tick().run();
        assertTrue(handle.ownershipLost());
        verify(future).cancel(false);

        captured.tick().run();
        verify(lifecycle, times(1)).renewLease(any(), any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void renewalExceptionMarksOwnershipLostAndCancelsFuture() {
        WorkerRunLifecycle lifecycle = mock(WorkerRunLifecycle.class);
        when(lifecycle.renewLease(any(), any(), any())).thenThrow(new RuntimeException("db down"));

        ScheduledFuture future = mock(ScheduledFuture.class);
        CapturedTick captured = captureTick(lifecycle, future);
        LeaseHandle handle = captured.heartbeater().start(UUID.randomUUID(), "worker-a");

        captured.tick().run();
        assertTrue(handle.ownershipLost());
        verify(future).cancel(false);

        captured.tick().run();
        verify(lifecycle, times(1)).renewLease(any(), any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void handleCloseCancelsFutureAndIsIdempotent() {
        WorkerRunLifecycle lifecycle = mock(WorkerRunLifecycle.class);
        ScheduledFuture future = mock(ScheduledFuture.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any())).thenReturn(future);

        RunLeaseHeartbeater heartbeater = new RunLeaseHeartbeater(lifecycle, scheduler);
        LeaseHandle handle = heartbeater.start(UUID.randomUUID(), "worker-a");
        handle.close();
        handle.close();

        verify(future, times(2)).cancel(false);
    }

    @Test
    void heartbeaterCloseShutsDownScheduler() {
        WorkerRunLifecycle lifecycle = mock(WorkerRunLifecycle.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any()))
                .thenReturn(mock(ScheduledFuture.class));

        RunLeaseHeartbeater heartbeater = new RunLeaseHeartbeater(lifecycle, scheduler);
        heartbeater.close();

        verify(scheduler).shutdownNow();
    }

    @Test
    void closeReleasesDaemonSchedulerThread() throws InterruptedException {
        WorkerRunLifecycle lifecycle = mock(WorkerRunLifecycle.class);
        when(lifecycle.renewLease(any(), any(), any())).thenReturn(true);

        RunLeaseHeartbeater heartbeater = new RunLeaseHeartbeater(lifecycle);
        heartbeater.start(UUID.randomUUID(), "worker-a").close();
        heartbeater.close();

        for (int attempt = 0; attempt < 20; attempt++) {
            if (!heartbeatThreadPresent()) {
                return;
            }
            Thread.sleep(50);
        }
        assertFalse(heartbeatThreadPresent(), "netverify-lease-heartbeat thread still alive after close");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ownershipLostStopsFurtherRenewAttempts() {
        WorkerRunLifecycle lifecycle = mock(WorkerRunLifecycle.class);
        when(lifecycle.renewLease(any(), any(), any())).thenReturn(false);

        ScheduledFuture future = mock(ScheduledFuture.class);
        CapturedTick captured = captureTick(lifecycle, future);
        LeaseHandle handle = captured.heartbeater().start(UUID.randomUUID(), "worker-a");

        captured.tick().run();
        captured.tick().run();

        verify(lifecycle, times(1)).renewLease(any(), any(), any());
        assertTrue(handle.ownershipLost());
        verify(future).cancel(false);
    }

    private static CapturedTick captureTick(WorkerRunLifecycle lifecycle) {
        return captureTick(lifecycle, mock(ScheduledFuture.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CapturedTick captureTick(WorkerRunLifecycle lifecycle, ScheduledFuture future) {
        AtomicReference<Runnable> tick = new AtomicReference<>();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    tick.set(invocation.getArgument(0));
                    return future;
                });
        RunLeaseHeartbeater heartbeater = new RunLeaseHeartbeater(lifecycle, scheduler);
        Runnable runCapturedTick = () -> {
            Runnable scheduled = tick.get();
            if (scheduled != null) {
                scheduled.run();
            }
        };
        return new CapturedTick(heartbeater, runCapturedTick);
    }

    private static boolean heartbeatThreadPresent() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> "netverify-lease-heartbeat".equals(thread.getName()));
    }

    private record CapturedTick(RunLeaseHeartbeater heartbeater, Runnable tick) {
    }
}
