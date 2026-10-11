package com.netverify.controlplane.worker;

import com.netverify.controlplane.config.ConditionalOnWorkerRole;
import com.netverify.controlplane.config.NetVerifyProperties;
import com.netverify.controlplane.config.QueueProperties;
import com.netverify.controlplane.queue.AutoClaimBatch;
import com.netverify.controlplane.queue.MalformedQueueMessageException;
import com.netverify.controlplane.queue.QueueDelivery;
import com.netverify.controlplane.queue.RunQueue;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Non-daemon Redis Streams consumer. Keeps the web-none JVM alive while
 * {@code running} and owns fair XAUTOCLAIM + blocking XREADGROUP cycling.
 */
@Component
@ConditionalOnWorkerRole
public class RedisRunWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RedisRunWorker.class);
    private static final String THREAD_NAME = "netverify-run-worker";
    private static final Duration DEFAULT_TRANSPORT_BACKOFF = Duration.ofMillis(250);

    private final RunQueue runQueue;
    private final WorkerMessageProcessor processor;
    private final String workerId;
    private final int readBatchSize;
    private final Duration readBlock;
    private final Duration claimMinIdle;
    private final Duration transportBackoff;
    private final Duration stopJoinTimeout;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();
    private Thread consumerThread;
    private volatile String recoveryCursor = "0-0";

    @Autowired
    public RedisRunWorker(
            RunQueue runQueue,
            WorkerMessageProcessor processor,
            QueueProperties queueProperties,
            NetVerifyProperties properties
    ) {
        this(runQueue, processor, queueProperties, properties, DEFAULT_TRANSPORT_BACKOFF, null);
    }

    RedisRunWorker(
            RunQueue runQueue,
            WorkerMessageProcessor processor,
            QueueProperties queueProperties,
            NetVerifyProperties properties,
            Duration transportBackoff
    ) {
        this(runQueue, processor, queueProperties, properties, transportBackoff, null);
    }

    /**
     * @param stopJoinTimeoutOverride test-only join budget; {@code null} uses readBlock+1s
     */
    RedisRunWorker(
            RunQueue runQueue,
            WorkerMessageProcessor processor,
            QueueProperties queueProperties,
            NetVerifyProperties properties,
            Duration transportBackoff,
            Duration stopJoinTimeoutOverride
    ) {
        this.runQueue = Objects.requireNonNull(runQueue, "runQueue");
        this.processor = Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(queueProperties, "queueProperties");
        queueProperties.validate();
        Objects.requireNonNull(properties, "properties");
        if (!StringUtils.hasText(properties.getWorkerId())) {
            throw new IllegalArgumentException("netverify.worker-id must not be blank");
        }
        this.workerId = properties.getWorkerId().trim();
        this.readBatchSize = queueProperties.getReadBatchSize();
        this.readBlock = Duration.ofSeconds(queueProperties.getReadBlockSeconds());
        this.claimMinIdle = Duration.ofSeconds(queueProperties.getClaimMinIdleSeconds());
        this.transportBackoff = Objects.requireNonNull(transportBackoff, "transportBackoff");
        if (this.transportBackoff.isNegative()) {
            throw new IllegalArgumentException("transportBackoff must not be negative");
        }
        if (stopJoinTimeoutOverride != null) {
            if (stopJoinTimeoutOverride.isNegative() || stopJoinTimeoutOverride.isZero()) {
                throw new IllegalArgumentException("stopJoinTimeoutOverride must be positive");
            }
            this.stopJoinTimeout = stopJoinTimeoutOverride;
        } else {
            // Join must outlive a blocked XREADGROUP wait.
            this.stopJoinTimeout = this.readBlock.plusSeconds(1);
        }
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            if (consumerThread != null) {
                if (consumerThread.isAlive()) {
                    if (running.get()) {
                        return;
                    }
                    throw new IllegalStateException("previous worker thread is still stopping");
                }
                consumerThread = null;
            }

            runQueue.ensureConsumerGroup();
            recoveryCursor = "0-0";
            running.set(true);
            Thread thread = new Thread(this::runLoop, THREAD_NAME);
            thread.setDaemon(false);
            consumerThread = thread;
            thread.start();
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            Thread thread = consumerThread;
            if (!running.get() && (thread == null || !thread.isAlive())) {
                if (thread != null) {
                    consumerThread = null;
                }
                return;
            }
            running.set(false);
            if (thread == null) {
                return;
            }
            thread.interrupt();
            try {
                thread.join(stopJoinTimeout.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                log.warn(
                        "worker consumer thread did not finish within join timeout ({} ms); "
                                + "keeping thread reference until it exits",
                        stopJoinTimeout.toMillis()
                );
            } else {
                consumerThread = null;
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    String recoveryCursor() {
        return recoveryCursor;
    }

    Thread consumerThreadForTest() {
        synchronized (lifecycleLock) {
            return consumerThread;
        }
    }

    private void runLoop() {
        try {
            while (running.get()) {
                try {
                    AutoClaimBatch stale = runQueue.claimStale(
                            workerId,
                            claimMinIdle,
                            recoveryCursor,
                            readBatchSize
                    );
                    for (QueueDelivery delivery : stale.deliveries()) {
                        processIsolated(delivery);
                    }
                    recoveryCursor = stale.nextStartId();

                    List<QueueDelivery> fresh = runQueue.read(workerId, readBatchSize, readBlock);
                    for (QueueDelivery delivery : fresh) {
                        processIsolated(delivery);
                    }
                } catch (MalformedQueueMessageException ex) {
                    poisonAcknowledge(ex);
                } catch (RuntimeException ex) {
                    if (!running.get()) {
                        return;
                    }
                    log.warn("Redis transport failure in worker loop — backing off", ex);
                    if (!backoff()) {
                        return;
                    }
                }
            }
        } finally {
            running.set(false);
        }
    }

    private void processIsolated(QueueDelivery delivery) {
        try {
            processor.process(delivery);
        } catch (RuntimeException ex) {
            log.error(
                    "worker processor failed for messageId={} runId={}",
                    delivery.messageId(),
                    delivery.runId(),
                    ex
            );
        }
    }

    private void poisonAcknowledge(MalformedQueueMessageException ex) {
        log.warn(
                "malformed queue message messageId={} — poison ACK without Python",
                ex.getMessageId(),
                ex
        );
        try {
            if (!runQueue.acknowledge(ex.getMessageId())) {
                log.warn(
                        "poison ACK returned false for messageId={}",
                        ex.getMessageId()
                );
            }
        } catch (RuntimeException ackEx) {
            log.warn(
                    "poison ACK failed for messageId={}",
                    ex.getMessageId(),
                    ackEx
            );
        }
    }

    private boolean backoff() {
        if (transportBackoff.isZero()) {
            return running.get();
        }
        try {
            Thread.sleep(transportBackoff.toMillis());
            return running.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
