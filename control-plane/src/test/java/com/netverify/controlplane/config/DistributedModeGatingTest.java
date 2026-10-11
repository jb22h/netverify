package com.netverify.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.netverify.controlplane.api.ApiExceptionHandler;
import com.netverify.controlplane.api.TestController;
import com.netverify.controlplane.execution.BoundedRunnerExecutor;
import com.netverify.controlplane.execution.LocalRunDispatcher;
import com.netverify.controlplane.execution.LocalRunExecutionCoordinator;
import com.netverify.controlplane.execution.RedisRunDispatcher;
import com.netverify.controlplane.execution.RunDispatcher;
import com.netverify.controlplane.execution.RunExecutionService;
import com.netverify.controlplane.execution.RunLeaseHeartbeater;
import com.netverify.controlplane.execution.RunnerTaskExecutor;
import com.netverify.controlplane.persistence.RunLifecycleStore;
import com.netverify.controlplane.persistence.TestRepository;
import com.netverify.controlplane.persistence.TestRunRepository;
import com.netverify.controlplane.persistence.worker.WorkerRunLifecycle;
import com.netverify.controlplane.queue.AutoClaimBatch;
import com.netverify.controlplane.queue.RunQueue;
import com.netverify.controlplane.runner.PythonRunnerBridge;
import com.netverify.controlplane.runner.RunnerOutcomeMapper;
import com.netverify.controlplane.service.TestService;
import com.netverify.controlplane.worker.RedisRunWorker;
import com.netverify.controlplane.worker.WorkerMessageProcessor;
import com.netverify.controlplane.worker.WorkerRuntimeConfig;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Lightweight context checks for M3 role gating and worker bean graph.
 * Does not require DB or Redis connections.
 */
class DistributedModeGatingTest {

    private static final String[] VALID_WORKER_PROPS = {
            "spring.profiles.active=postgres,redis",
            "netverify.role=worker",
            "netverify.worker-id=worker-1",
            "spring.main.web-application-type=none"
    };

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
                    assertTrue(message.contains("role=worker"), message);
                });
    }

    @Test
    void validWorkerRolePassesValidator() {
        new ApplicationContextRunner()
                .withUserConfiguration(DistributedModeValidator.class)
                .withPropertyValues(VALID_WORKER_PROPS)
                .run(context -> assertNull(context.getStartupFailure(), () -> deepest(context.getStartupFailure())));
    }

    @Test
    void workerMissingPostgresFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(DistributedModeValidator.class)
                .withPropertyValues(
                        "spring.profiles.active=redis",
                        "netverify.role=worker",
                        "netverify.worker-id=worker-1",
                        "spring.main.web-application-type=none"
                )
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(deepest(context.getStartupFailure()).contains("requires both"), deepest(context.getStartupFailure()));
                });
    }

    @Test
    void workerMissingRedisFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(DistributedModeValidator.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres",
                        "netverify.role=worker",
                        "netverify.worker-id=worker-1",
                        "spring.main.web-application-type=none"
                )
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(deepest(context.getStartupFailure()).contains("requires both"), deepest(context.getStartupFailure()));
                });
    }

    @Test
    void workerBlankWorkerIdFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(DistributedModeValidator.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres,redis",
                        "netverify.role=worker",
                        "netverify.worker-id=   ",
                        "spring.main.web-application-type=none"
                )
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(
                            deepest(context.getStartupFailure()).contains("worker-id"),
                            deepest(context.getStartupFailure())
                    );
                });
    }

    @Test
    void workerMissingWorkerIdFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(DistributedModeValidator.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres,redis",
                        "netverify.role=worker",
                        "spring.main.web-application-type=none"
                )
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(
                            deepest(context.getStartupFailure()).contains("worker-id"),
                            deepest(context.getStartupFailure())
                    );
                });
    }

    @Test
    void workerWebTypeServletFails() {
        assertWorkerWebTypeFails("servlet");
    }

    @Test
    void workerWebTypeReactiveFails() {
        assertWorkerWebTypeFails("reactive");
    }

    @Test
    void workerOmittedWebNoneFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(DistributedModeValidator.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres,redis",
                        "netverify.role=worker",
                        "netverify.worker-id=worker-1"
                )
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(
                            deepest(context.getStartupFailure()).contains("web-application-type=none"),
                            deepest(context.getStartupFailure())
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
    void distributedApiWiresRedisDispatcherAndTestServiceWithoutLocalPythonOrWorkerRuntime() {
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
                        TestService.class,
                        WorkerRuntimeConfig.class,
                        WorkerMessageProcessor.class,
                        RedisRunWorker.class
                )
                .withBean(RunQueue.class, () -> mock(RunQueue.class))
                .withBean(TestRepository.class, () -> mock(TestRepository.class))
                .withBean(RunLifecycleStore.class, () -> mock(RunLifecycleStore.class))
                .withBean(WorkerRunLifecycle.class, () -> mock(WorkerRunLifecycle.class))
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
                    assertTrue(context.getBeansOfType(RedisRunWorker.class).isEmpty());
                    assertTrue(context.getBeansOfType(WorkerMessageProcessor.class).isEmpty());
                    assertTrue(context.getBeansOfType(RunLeaseHeartbeater.class).isEmpty());
                });
    }

    @Test
    void validWorkerBeanGraphHasRuntimeAndExcludesApiSurface() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        DistributedModeValidator.class,
                        WorkerRuntimeConfig.class,
                        WorkerMessageProcessor.class,
                        RedisRunWorker.class,
                        RunExecutionService.class,
                        PythonRunnerBridge.class,
                        RunnerOutcomeMapper.class,
                        LocalRunDispatcher.class,
                        RedisRunDispatcher.class,
                        LocalRunExecutionCoordinator.class,
                        BoundedRunnerExecutor.class,
                        ActiveRunRecovery.class,
                        TestService.class,
                        TestController.class,
                        ApiExceptionHandler.class
                )
                .withBean(RunQueue.class, DistributedModeGatingTest::idleRunQueue)
                .withBean(TestRepository.class, () -> mock(TestRepository.class))
                .withBean(WorkerRunLifecycle.class, () -> mock(WorkerRunLifecycle.class))
                .withBean(RunLifecycleStore.class, () -> mock(RunLifecycleStore.class))
                .withBean(TestRunRepository.class, () -> mock(TestRunRepository.class))
                .withBean(Flyway.class, () -> mock(Flyway.class))
                .withBean(QueueProperties.class, QueueProperties::new)
                .withBean(NetVerifyProperties.class, () -> {
                    NetVerifyProperties properties = new NetVerifyProperties();
                    properties.setWorkerId("worker-1");
                    properties.setRole("worker");
                    return properties;
                })
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                        com.fasterxml.jackson.databind.ObjectMapper::new)
                .withPropertyValues(VALID_WORKER_PROPS)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> deepest(context.getStartupFailure()));

                    assertEquals(1, context.getBeansOfType(WorkerMessageProcessor.class).size());
                    assertEquals(1, context.getBeansOfType(RedisRunWorker.class).size());
                    assertEquals(1, context.getBeansOfType(RunLeaseHeartbeater.class).size());
                    assertEquals(1, context.getBeansOfType(RunExecutionService.class).size());
                    assertEquals(1, context.getBeansOfType(PythonRunnerBridge.class).size());
                    assertEquals(1, context.getBeansOfType(RunnerOutcomeMapper.class).size());
                    assertEquals(1, context.getBeansOfType(WorkerRunLifecycle.class).size());
                    assertEquals(1, context.getBeansOfType(TestRepository.class).size());
                    assertEquals(1, context.getBeansOfType(RunQueue.class).size());

                    assertTrue(context.getBeansOfType(TestService.class).isEmpty());
                    assertTrue(context.getBeansOfType(TestController.class).isEmpty());
                    assertTrue(context.getBeansOfType(ApiExceptionHandler.class).isEmpty());
                    assertTrue(context.getBeansOfType(RunDispatcher.class).isEmpty());
                    assertTrue(context.getBeansOfType(LocalRunDispatcher.class).isEmpty());
                    assertTrue(context.getBeansOfType(RedisRunDispatcher.class).isEmpty());
                    assertTrue(context.getBeansOfType(LocalRunExecutionCoordinator.class).isEmpty());
                    assertTrue(context.getBeansOfType(BoundedRunnerExecutor.class).isEmpty());
                    assertTrue(context.getBeansOfType(RunnerTaskExecutor.class).isEmpty());
                    assertTrue(context.getBeansOfType(ActiveRunRecovery.class).isEmpty());
                });
    }

    private static RunQueue idleRunQueue() {
        RunQueue queue = mock(RunQueue.class);
        Mockito.when(queue.claimStale(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt()))
                .thenReturn(new AutoClaimBatch("0-0", List.of()));
        Mockito.when(queue.read(Mockito.any(), Mockito.anyInt(), Mockito.any()))
                .thenAnswer(invocation -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of();
                });
        return queue;
    }

    private static void assertWorkerWebTypeFails(String webType) {
        new ApplicationContextRunner()
                .withUserConfiguration(DistributedModeValidator.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres,redis",
                        "netverify.role=worker",
                        "netverify.worker-id=worker-1",
                        "spring.main.web-application-type=" + webType
                )
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(
                            deepest(context.getStartupFailure()).contains("web-application-type=none"),
                            deepest(context.getStartupFailure())
                    );
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
