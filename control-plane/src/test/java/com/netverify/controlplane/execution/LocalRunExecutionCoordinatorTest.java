package com.netverify.controlplane.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.persistence.InMemoryRunLifecycleStore;
import com.netverify.controlplane.persistence.InMemoryTestRepository;
import com.netverify.controlplane.persistence.RunLifecycleStore;
import com.netverify.controlplane.persistence.TestEntity;
import com.netverify.controlplane.persistence.TestRepository;
import com.netverify.controlplane.runner.PythonRunOutcome;
import com.netverify.controlplane.runner.PythonRunnerBridge;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalRunExecutionCoordinatorTest {

    private static final RunnerResultDto PASS = new RunnerResultDto(
            "basic_ping_pong", true, List.of(), 0, 0.01, Map.of()
    );

    private TestRepository testRepository;
    private RunLifecycleStore lifecycle;
    private PythonRunnerBridge bridge;
    private LocalRunExecutionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        testRepository = new InMemoryTestRepository();
        lifecycle = new InMemoryRunLifecycleStore();
        bridge = mock(PythonRunnerBridge.class);
        coordinator = new LocalRunExecutionCoordinator(
                lifecycle, testRepository, new RunExecutionService(bridge)
        );
    }

    @Test
    void successfulExecutionPreservesPassedTerminal() {
        UUID testId = insertDefinition();
        UUID runId = UUID.randomUUID();
        assertEquals(
                com.netverify.controlplane.persistence.QueueRunResult.QUEUED,
                lifecycle.tryQueue(testRepository.findById(testId).orElseThrow(), runId, Instant.now())
        );
        when(bridge.run(any(), anyInt(), any())).thenReturn(PythonRunOutcome.passed(0, PASS, "{}", ""));

        coordinator.execute(testId, runId);

        assertEquals(TestStatus.PASSED, lifecycle.findLatest(testId).orElseThrow().status());
        assertNull(lifecycle.findLatest(testId).orElseThrow().failureCategory());
    }

    @Test
    void definitionMissingAfterStartBecomesFailedLaunch() {
        UUID testId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        TestEntity orphanDef = new TestEntity(
                testId, "gone", "basic_ping_pong", 3, null, Instant.now()
        );
        // Queue via lifecycle without leaving definition for lookup after start:
        // insert definition, queue, then remove is not supported — simulate by
        // starting with queued row then deleting from repository after queue.
        testRepository.insert(orphanDef);
        lifecycle.tryQueue(orphanDef, runId, Instant.now());
        // Replace repository with empty for lookup after start by using a custom coordinator
        TestRepository emptyRepo = new InMemoryTestRepository();
        LocalRunExecutionCoordinator missingDefCoordinator = new LocalRunExecutionCoordinator(
                lifecycle, emptyRepo, new RunExecutionService(bridge)
        );

        missingDefCoordinator.execute(testId, runId);

        assertEquals(TestStatus.FAILED, lifecycle.findLatest(testId).orElseThrow().status());
        assertEquals(
                FailureCategory.LAUNCH,
                lifecycle.findLatest(testId).orElseThrow().failureCategory()
        );
        assertTrue(lifecycle.findLatest(testId).orElseThrow().stderr()
                .contains("test definition missing during run"));
        verifyNoInteractions(bridge);
    }

    @Test
    void bridgeRuntimeExceptionBecomesFailedLaunch() {
        UUID testId = insertDefinition();
        UUID runId = UUID.randomUUID();
        lifecycle.tryQueue(testRepository.findById(testId).orElseThrow(), runId, Instant.now());
        when(bridge.run(any(), anyInt(), any())).thenThrow(new IllegalStateException("boom"));

        coordinator.execute(testId, runId);

        assertEquals(TestStatus.FAILED, lifecycle.findLatest(testId).orElseThrow().status());
        assertEquals(
                FailureCategory.LAUNCH,
                lifecycle.findLatest(testId).orElseThrow().failureCategory()
        );
    }

    @Test
    void unexpectedOrchestrationFailureBecomesFailedLaunch() {
        RunLifecycleStore broken = mock(RunLifecycleStore.class);
        when(broken.tryStartRunning(any(), any(), any())).thenReturn(true);
        when(broken.tryComplete(any(), any(), any(), any())).thenReturn(true);
        TestRepository repo = mock(TestRepository.class);
        when(repo.findById(any())).thenThrow(new IllegalStateException("repo down"));

        LocalRunExecutionCoordinator failing = new LocalRunExecutionCoordinator(
                broken, repo, new RunExecutionService(bridge)
        );
        UUID testId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        failing.execute(testId, runId);

        org.mockito.Mockito.verify(broken).tryComplete(
                org.mockito.ArgumentMatchers.eq(testId),
                org.mockito.ArgumentMatchers.eq(runId),
                org.mockito.ArgumentMatchers.argThat(update ->
                        update.status() == TestStatus.FAILED
                                && update.failureCategory() == FailureCategory.LAUNCH
                                && update.stderr().contains("unexpected worker error:")
                ),
                any()
        );
        verifyNoInteractions(bridge);
    }

    private UUID insertDefinition() {
        UUID id = UUID.randomUUID();
        testRepository.insert(new TestEntity(id, "t", "basic_ping_pong", 3, null, Instant.now()));
        return id;
    }
}
