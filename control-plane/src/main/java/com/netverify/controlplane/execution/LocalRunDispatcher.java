package com.netverify.controlplane.execution;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.stereotype.Component;

/**
 * Local dispatcher: bounded executor → {@link LocalRunExecutionCoordinator}.
 */
@Component
@ConditionalOnLocalRunDispatcher
public class LocalRunDispatcher implements RunDispatcher {

    private final RunnerTaskExecutor executor;
    private final LocalRunExecutionCoordinator coordinator;

    public LocalRunDispatcher(RunnerTaskExecutor executor, LocalRunExecutionCoordinator coordinator) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public void dispatch(UUID testId, UUID runId) {
        Objects.requireNonNull(testId, "testId");
        Objects.requireNonNull(runId, "runId");
        try {
            executor.submit(() -> coordinator.execute(testId, runId));
        } catch (RejectedExecutionException ex) {
            throw new DispatchRejectedException(
                    "runner executor is saturated; retry later",
                    ex
            );
        }
    }
}
