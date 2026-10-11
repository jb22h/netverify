package com.netverify.controlplane.execution;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.netverify.controlplane.queue.RunQueue;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RunDispatcherTest {

    @Test
    void localDispatcherSubmitsCoordinator() {
        AtomicBoolean executed = new AtomicBoolean(false);
        RunnerTaskExecutor executor = task -> {
            task.run();
            executed.set(true);
        };
        LocalRunExecutionCoordinator coordinator = mock(LocalRunExecutionCoordinator.class);
        LocalRunDispatcher dispatcher = new LocalRunDispatcher(executor, coordinator);
        UUID testId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        dispatcher.dispatch(testId, runId);

        verify(coordinator).execute(testId, runId);
        org.junit.jupiter.api.Assertions.assertTrue(executed.get());
    }

    @Test
    void localDispatcherMapsRejection() {
        RunnerTaskExecutor executor = task -> {
            throw new RejectedExecutionException("full");
        };
        LocalRunDispatcher dispatcher = new LocalRunDispatcher(
                executor, mock(LocalRunExecutionCoordinator.class)
        );

        DispatchRejectedException ex = assertThrows(
                DispatchRejectedException.class,
                () -> dispatcher.dispatch(UUID.randomUUID(), UUID.randomUUID())
        );
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("saturated"),
                ex.getMessage()
        );
    }

    @Test
    void redisDispatcherPublishesExactIds() {
        RunQueue queue = mock(RunQueue.class);
        when(queue.publish(any(), any())).thenReturn("1-0");
        RedisRunDispatcher dispatcher = new RedisRunDispatcher(queue);
        UUID testId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        dispatcher.dispatch(testId, runId);

        verify(queue).publish(runId, testId);
    }

    @Test
    void redisDispatcherMapsPublishFailure() {
        RunQueue queue = mock(RunQueue.class);
        when(queue.publish(any(), any())).thenThrow(new IllegalStateException("down"));
        RedisRunDispatcher dispatcher = new RedisRunDispatcher(queue);

        DispatchRejectedException ex = assertThrows(
                DispatchRejectedException.class,
                () -> dispatcher.dispatch(UUID.randomUUID(), UUID.randomUUID())
        );
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("publish failed"),
                ex.getMessage()
        );
    }
}
