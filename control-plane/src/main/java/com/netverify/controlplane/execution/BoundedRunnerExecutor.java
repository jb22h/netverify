package com.netverify.controlplane.execution;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Explicitly bounded worker pool for runner executions.
 *
 * <p>Uses an {@link ArrayBlockingQueue} plus {@link ThreadPoolExecutor.AbortPolicy}
 * so saturation is surfaced to the caller as a rejection. Deliberately not
 * {@code Executors.newFixedThreadPool}, whose queue is unbounded.
 */
@Component
@ConditionalOnLocalRunDispatcher
public class BoundedRunnerExecutor implements RunnerTaskExecutor {

    static final int WORKERS = 2;
    static final int QUEUE_CAPACITY = 16;
    private static final long SHUTDOWN_WAIT_SECONDS = 10;

    private static final Logger log = LoggerFactory.getLogger(BoundedRunnerExecutor.class);

    private final ThreadPoolExecutor executor;

    public BoundedRunnerExecutor() {
        this(WORKERS, QUEUE_CAPACITY);
    }

    BoundedRunnerExecutor(int workers, int queueCapacity) {
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public void submit(Runnable task) {
        executor.execute(task);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("runner executor did not terminate in {}s; forcing shutdown",
                        SHUTDOWN_WAIT_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "netverify-runner-" + counter.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }
}
