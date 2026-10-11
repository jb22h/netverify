package com.netverify.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.netverify.controlplane.execution.BoundedRunnerExecutor;
import com.netverify.controlplane.execution.LocalRunDispatcher;
import com.netverify.controlplane.execution.LocalRunExecutionCoordinator;
import com.netverify.controlplane.execution.RedisRunDispatcher;
import com.netverify.controlplane.execution.RunDispatcher;
import com.netverify.controlplane.execution.RunExecutionService;
import com.netverify.controlplane.execution.RunnerTaskExecutor;
import com.netverify.controlplane.persistence.RunLifecycleStore;
import com.netverify.controlplane.persistence.TestRepository;
import com.netverify.controlplane.persistence.TestRunRepository;
import com.netverify.controlplane.queue.RunQueue;
import com.netverify.controlplane.runner.PythonRunnerBridge;
import com.netverify.controlplane.runner.RunnerOutcomeMapper;
import com.netverify.controlplane.service.TestService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Lightweight context checks for M3 producer/gating bean and role rules.
 * Does not require DB or Redis connections.
 */
class DistributedModeGatingTest {

    @Test
    void defaultModeHasLocalDispatcher() {
        new ApplicationContextRunner()
                .withUserConfiguration(DistributedModeValidator.class, LocalRunDispatcher.class)
                .withBean(RunnerTaskExecutor.class, () -> task -> {})
                .withBean(LocalRunExecutionCoordinator.class, () -> mock(LocalRunExecutionCoordinator.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("localRunDispatcher"));
                });
    }

    @Test
    void postgresOnlyKeepsLocalDispatcherAndRecovery() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        DistributedModeValidator.class,
                        LocalRunDispatcher.class,
                        RedisRunDispatcher.class,
                        ActiveRunRecovery.class
                )
                .withBean(RunnerTaskExecutor.class, () -> task -> {})
                .withBean(LocalRunExecutionCoordinator.class, () -> mock(LocalRunExecutionCoordinator.class))
                .withBean(TestRunRepository.class, () -> mock(TestRunRepository.class))
                .withBean(Flyway.class, () -> mock(Flyway.class))
                .withPropertyValues("spring.profiles.active=postgres")
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> deepest(context.getStartupFailure()));
                    assertEquals(1, context.getBeansOfType(LocalRunDispatcher.class).size());
                    assertTrue(context.getBeansOfType(RedisRunDispatcher.class).isEmpty());
                    assertEquals(1, context.getBeansOfType(ActiveRunRecovery.class).size());
                });
    }

    @Test
    void redisOnlyKeepsLocalDispatcherAndRemainsValid() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        DistributedModeValidator.class,
                        LocalRunDispatcher.class,
                        RedisRunDispatcher.class
                )
                .withBean(RunnerTaskExecutor.class, () -> task -> {})
                .withBean(LocalRunExecutionCoordinator.class, () -> mock(LocalRunExecutionCoordinator.class))
                .withPropertyValues("spring.profiles.active=redis")
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> deepest(context.getStartupFailure()));
                    assertEquals(1, context.getBeansOfType(LocalRunDispatcher.class).size());
                    assertTrue(context.getBeansOfType(RedisRunDispatcher.class).isEmpty());
                });
    }

    @Test
    void postgresAndRedisWithoutRoleFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(DistributedModeValidator.class)
                .withPropertyValues("spring.profiles.active=postgres,redis")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String message = deepest(context.getStartupFailure());
                    assertTrue(message.contains("role=api"), message);
                });
    }

    @Test
    void postgresAndRedisWorkerRoleFailsInThisSlice() {
        new ApplicationContextRunner()
                .withUserConfiguration(DistributedModeValidator.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres,redis",
                        "netverify.role=worker"
                )
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String message = deepest(context.getStartupFailure());
                    assertTrue(
                            message.contains(
                                    "worker role is not enabled until the M3 worker runtime slice"
                            ),
                            message
                    );
                });
    }

    @Test
    void fullWorkerRoleContextStillRejectedByValidator() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        DistributedModeValidator.class,
                        LocalRunDispatcher.class,
                        RedisRunDispatcher.class,
                        LocalRunExecutionCoordinator.class,
                        BoundedRunnerExecutor.class,
                        RunExecutionService.class,
                        PythonRunnerBridge.class,
                        RunnerOutcomeMapper.class,
                        ActiveRunRecovery.class,
                        TestService.class
                )
                .withBean(RunQueue.class, () -> mock(RunQueue.class))
                .withBean(TestRepository.class, () -> mock(TestRepository.class))
                .withBean(RunLifecycleStore.class, () -> mock(RunLifecycleStore.class))
                .withBean(TestRunRepository.class, () -> mock(TestRunRepository.class))
                .withBean(Flyway.class, () -> mock(Flyway.class))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                        com.fasterxml.jackson.databind.ObjectMapper::new)
                .withPropertyValues(
                        "spring.profiles.active=postgres,redis",
                        "netverify.role=worker",
                        "netverify.worker-id=worker-build1"
                )
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String message = deepest(context.getStartupFailure());
                    assertTrue(
                            message.contains(
                                    "worker role is not enabled until the M3 worker runtime slice"
                            ),
                            message
                    );
                });
    }

    @Test
    void apiRoleWithoutBothProfilesFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(DistributedModeValidator.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres",
                        "netverify.role=api"
                )
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String message = deepest(context.getStartupFailure());
                    assertTrue(message.contains("requires both"), message);
                });
    }

    @Test
    void unknownRoleFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(DistributedModeValidator.class)
                .withPropertyValues("netverify.role=orchestrator")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String message = deepest(context.getStartupFailure());
                    assertTrue(message.contains("unknown netverify.role"), message);
                });
    }

    @Test
    void distributedApiWiresRedisDispatcherAndTestServiceWithoutLocalPython() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        DistributedModeValidator.class,
                        LocalRunDispatcher.class,
                        RedisRunDispatcher.class,
                        LocalRunExecutionCoordinator.class,
                        com.netverify.controlplane.execution.BoundedRunnerExecutor.class,
                        RunExecutionService.class,
                        PythonRunnerBridge.class,
                        RunnerOutcomeMapper.class,
                        ActiveRunRecovery.class,
                        TestService.class
                )
                .withBean(RunQueue.class, () -> mock(RunQueue.class))
                .withBean(TestRepository.class, () -> mock(TestRepository.class))
                .withBean(RunLifecycleStore.class, () -> mock(RunLifecycleStore.class))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                        com.fasterxml.jackson.databind.ObjectMapper::new)
                .withPropertyValues(
                        "spring.profiles.active=postgres,redis",
                        "netverify.role=api"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> deepest(context.getStartupFailure()));
                    assertEquals(1, context.getBeansOfType(RedisRunDispatcher.class).size());
                    assertEquals(1, context.getBeansOfType(TestService.class).size());
                    assertNotNull(context.getBean(TestService.class));
                    assertFalse(context.getBeansOfType(RunDispatcher.class).isEmpty());
                    assertTrue(context.getBeansOfType(LocalRunDispatcher.class).isEmpty());
                    assertTrue(context.getBeansOfType(LocalRunExecutionCoordinator.class).isEmpty());
                    assertTrue(context.getBeansOfType(RunnerTaskExecutor.class).isEmpty());
                    assertTrue(context.getBeansOfType(RunExecutionService.class).isEmpty());
                    assertTrue(context.getBeansOfType(PythonRunnerBridge.class).isEmpty());
                    assertTrue(context.getBeansOfType(RunnerOutcomeMapper.class).isEmpty());
                    assertTrue(context.getBeansOfType(ActiveRunRecovery.class).isEmpty());
                });
    }

    private static String deepest(Throwable failure) {
        if (failure == null) {
            return "";
        }
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : "";
    }
}
