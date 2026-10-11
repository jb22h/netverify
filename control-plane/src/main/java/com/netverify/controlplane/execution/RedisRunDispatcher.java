package com.netverify.controlplane.execution;

import com.netverify.controlplane.queue.RunQueue;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Distributed API dispatcher: publishes {@code runId}+{@code testId} to Redis only.
 * Never executes Python.
 */
@Component
@ConditionalOnRedisRunDispatcher
public class RedisRunDispatcher implements RunDispatcher {

    private final RunQueue runQueue;

    public RedisRunDispatcher(RunQueue runQueue) {
        this.runQueue = Objects.requireNonNull(runQueue, "runQueue");
    }

    @Override
    public void dispatch(UUID testId, UUID runId) {
        Objects.requireNonNull(testId, "testId");
        Objects.requireNonNull(runId, "runId");
        try {
            runQueue.publish(runId, testId);
        } catch (RuntimeException ex) {
            throw new DispatchRejectedException(
                    "run queue publish failed; retry later",
                    ex
            );
        }
    }
}
