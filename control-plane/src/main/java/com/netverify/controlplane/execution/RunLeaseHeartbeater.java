package com.netverify.controlplane.execution;

import com.netverify.controlplane.persistence.worker.WorkerRunLifecycle;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.util.StringUtils;

/**
 * Shared scheduler that renews worker leases during execution. Not wired into
 * local {@code TestService} in M2.
 */
public final class RunLeaseHeartbeater implements AutoCloseable {

    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    static final Duration LEASE_HORIZON = Duration.ofSeconds(120);

    private final WorkerRunLifecycle workerRunLifecycle;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RunLeaseHeartbeater(WorkerRunLifecycle workerRunLifecycle) {
        this.workerRunLifecycle = Objects.requireNonNull(workerRunLifecycle, "workerRunLifecycle");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "netverify-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    RunLeaseHeartbeater(WorkerRunLifecycle workerRunLifecycle, ScheduledExecutorService scheduler) {
        this.workerRunLifecycle = Objects.requireNonNull(workerRunLifecycle, "workerRunLifecycle");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public LeaseHandle start(UUID runId, String workerId) {
        ensureOpen();
        Objects.requireNonNull(runId, "runId");
        if (!StringUtils.hasText(workerId)) {
            throw new IllegalArgumentException("workerId must not be blank");
        }

        LeaseSession session = new LeaseSession();
        String trimmedWorker = workerId.trim();

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> tick(runId, trimmedWorker, session),
                HEARTBEAT_INTERVAL.toSeconds(),
                HEARTBEAT_INTERVAL.toSeconds(),
                TimeUnit.SECONDS
        );
        session.publishFuture(future);

        return session;
    }

    private void tick(UUID runId, String workerId, LeaseSession session) {
        if (session.isInactive()) {
            return;
        }
        try {
            if (!workerRunLifecycle.renewLease(runId, workerId, LEASE_HORIZON)) {
                session.markOwnershipLost();
            }
        } catch (RuntimeException ex) {
            session.markOwnershipLost();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("RunLeaseHeartbeater is closed");
        }
    }

    /**
     * Per-run heartbeat state. The scheduled callback and {@link LeaseHandle} share
     * this object so ownership loss can cancel the same {@link ScheduledFuture}.
     */
    private static final class LeaseSession implements LeaseHandle {

        private final AtomicBoolean ownershipLost = new AtomicBoolean(false);
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        void publishFuture(ScheduledFuture<?> future) {
            futureRef.set(future);
            if (isInactive()) {
                future.cancel(false);
            }
        }

        boolean isInactive() {
            return stopped.get() || ownershipLost.get();
        }

        void markOwnershipLost() {
            ownershipLost.set(true);
            cancelFuture();
        }

        private void cancelFuture() {
            ScheduledFuture<?> future = futureRef.get();
            if (future != null) {
                future.cancel(false);
            }
        }

        @Override
        public boolean ownershipLost() {
            return ownershipLost.get();
        }

        @Override
        public void close() {
            stopped.set(true);
            cancelFuture();
        }
    }
}
