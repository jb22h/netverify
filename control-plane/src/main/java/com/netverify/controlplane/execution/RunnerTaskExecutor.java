package com.netverify.controlplane.execution;

import java.util.concurrent.RejectedExecutionException;

/**
 * Submission boundary for asynchronous runner work.
 *
 * <p>Implementations must be bounded: submission throws
 * {@link RejectedExecutionException} instead of queueing without limit.
 */
public interface RunnerTaskExecutor {

    /**
     * @throws RejectedExecutionException when workers and queue are saturated
     */
    void submit(Runnable task);
}
