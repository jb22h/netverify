package com.netverify.controlplane.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.netverify.controlplane.api.dto.CreateTestRequest;
import com.netverify.controlplane.api.dto.RunSummaryDto;
import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.execution.RunnerTaskExecutor;
import com.netverify.controlplane.persistence.InMemoryRunLifecycleStore;
import com.netverify.controlplane.persistence.InMemoryTestRepository;
import com.netverify.controlplane.persistence.RunLifecycleStore;
import com.netverify.controlplane.persistence.RunSnapshot;
import com.netverify.controlplane.persistence.TestRepository;
import com.netverify.controlplane.runner.PythonRunOutcome;
import com.netverify.controlplane.runner.PythonRunnerBridge;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestServiceRunTest {

    private static final RunnerResultDto PASS_RESULT = new RunnerResultDto(
            "basic_ping_pong", true, List.of(), 0, 0.05, Map.of("requested_count", 3)
    );

    private TestRepository testRepository;
    private RunLifecycleStore lifecycle;
    private PythonRunnerBridge bridge;

    @BeforeEach
    void setUp() {
        testRepository = new InMemoryTestRepository();
        lifecycle = new InMemoryRunLifecycleStore();
        bridge = mock(PythonRunnerBridge.class);
    }

    @Test
    void passingRunTransitionsQueuedRunningPassed() {
        TestService service = serviceWith(new SynchronousExecutor());
        UUID id = createTest(service, "basic_ping_pong", 3, null);
        stubBridge(PythonRunOutcome.passed(0, PASS_RESULT, "{}", ""));

        service.runTest(id);

        TestRecord record = service.getTest(id);
        assertEquals(TestStatus.PASSED, record.getStatus());
        assertNull(record.getFailureCategory());
        assertEquals(0, record.getExitCode());
        assertEquals(PASS_RESULT, record.getRunnerResult());
        assertNotNull(record.getStartedAt());
        assertNotNull(record.getFinishedAt());
    }

    @Test
    void verificationFailureIsRecordedTerminally() {
        assertFailedRun(
                PythonRunOutcome.failed(
                        FailureCategory.VERIFICATION, 1, PASS_RESULT, "{}", "", "failed"
                ),
                FailureCategory.VERIFICATION,
                1
        );
    }

    @Test
    void setupFailureIsRecordedTerminally() {
        assertFailedRun(
                PythonRunOutcome.failed(
                        FailureCategory.SETUP, 2, null, "", "error: bad args", "setup"
                ),
                FailureCategory.SETUP,
                2
        );
    }

    @Test
    void parseFailureIsRecordedTerminally() {
        assertFailedRun(
                PythonRunOutcome.failed(
                        FailureCategory.PARSE, 0, null, "not json", "", "bad json"
                ),
                FailureCategory.PARSE,
                0
        );
    }

    @Test
    void launchFailureIsRecordedTerminally() {
        assertFailedRun(
                PythonRunOutcome.preLaunchFailure(FailureCategory.LAUNCH, "no interpreter"),
                FailureCategory.LAUNCH,
                null
        );
    }

    @Test
    void preLaunchMessageAppearsExactlyOnceInStderr() {
        TestService service = serviceWith(new SynchronousExecutor());
        UUID id = createTest(service, "basic_ping_pong", 3, null);
        stubBridge(PythonRunOutcome.preLaunchFailure(FailureCategory.LAUNCH, "no interpreter"));

        service.runTest(id);

        TestRecord record = service.getTest(id);
        assertEquals("no interpreter", record.getStderr());
        assertEquals(1, countOccurrences(record.getStderr(), "no interpreter"));
        assertEquals("", record.getStdout());
        assertNull(record.getExitCode());
        assertNull(record.getRunnerResult());
        assertEquals(FailureCategory.LAUNCH, record.getFailureCategory());
    }

    @Test
    void queuedAtIsStampedBeforeExecutionCompletes() {
        TestService service = serviceWith(new SynchronousExecutor());
        UUID id = createTest(service, "basic_ping_pong", 3, null);
        stubBridge(PythonRunOutcome.passed(0, PASS_RESULT, "{}", ""));

        TestService.QueuedRun queuedRun = service.runTest(id);

        TestRecord record = service.getTest(id);
        assertFalse(queuedRun.queuedAt().isAfter(record.getStartedAt()));
        assertFalse(queuedRun.queuedAt().isAfter(record.getFinishedAt()));
        assertEquals(
                queuedRun.queuedAt(),
                lifecycle.findLatest(id).orElseThrow().queuedAt()
        );
    }

    @Test
    void timeoutFailureIsRecordedTerminally() {
        assertFailedRun(
                PythonRunOutcome.failed(
                        FailureCategory.TIMEOUT, null, null, "", "", "timed out"
                ),
                FailureCategory.TIMEOUT,
                null
        );
    }

    @Test
    void bridgeExceptionStillEndsInTerminalState() {
        TestService service = serviceWith(new SynchronousExecutor());
        UUID id = createTest(service, "basic_ping_pong", 3, null);
        when(bridge.run(any(), anyInt(), any())).thenThrow(new IllegalStateException("boom"));

        service.runTest(id);

        TestRecord record = service.getTest(id);
        assertEquals(TestStatus.FAILED, record.getStatus());
        assertEquals(FailureCategory.LAUNCH, record.getFailureCategory());
        assertNotNull(record.getFinishedAt());
    }

    @Test
    void rejectedSubmissionFailsRecordInsteadOfLeavingItQueued() {
        TestService service = serviceWith(new RejectingExecutor());
        UUID id = createTest(service, "basic_ping_pong", 3, null);

        assertThrows(ExecutorSaturatedException.class, () -> service.runTest(id));

        TestRecord record = service.getTest(id);
        assertEquals(TestStatus.FAILED, record.getStatus());
        assertEquals(FailureCategory.LAUNCH, record.getFailureCategory());
        assertNotNull(record.getFinishedAt());
        verifyNoInteractions(bridge);

        List<RunSummaryDto> history = service.listRuns(id);
        assertEquals(1, history.size());
        assertEquals(TestStatus.FAILED, history.get(0).status());
        assertEquals(FailureCategory.LAUNCH, history.get(0).failureCategory());
    }

    @Test
    void secondRunWhileQueuedConflicts() {
        TestService service = serviceWith(new NoopExecutor());
        UUID id = createTest(service, "basic_ping_pong", 3, null);

        service.runTest(id);

        assertThrows(ConflictException.class, () -> service.runTest(id));
        assertEquals(TestStatus.QUEUED, service.getTest(id).getStatus());
        assertEquals(1, service.listRuns(id).size());
        assertEquals(TestStatus.QUEUED, service.listRuns(id).get(0).status());
    }

    @Test
    void runUnknownTestThrowsNotFound() {
        TestService service = serviceWith(new SynchronousExecutor());

        assertThrows(NotFoundException.class, () -> service.runTest(UUID.randomUUID()));
    }

    @Test
    void workerDoesNotLaunchWhenRecordIsNotQueued() {
        TestService service = serviceWith(new NoopExecutor());
        UUID id = createTest(service, "basic_ping_pong", 3, null);

        service.executeRun(id, UUID.randomUUID());

        assertTrue(lifecycle.findLatest(id).isEmpty());
        assertEquals(TestStatus.CREATED, service.getTest(id).getStatus());
        verifyNoInteractions(bridge);
    }

    @Test
    void workerIgnoresUnknownId() {
        TestService service = serviceWith(new NoopExecutor());

        service.executeRun(UUID.randomUUID(), UUID.randomUUID());

        verifyNoInteractions(bridge);
    }

    @Test
    void concurrentLoadClientsArePassedToBridge() {
        TestService service = serviceWith(new SynchronousExecutor());
        UUID id = createTest(service, "concurrent_load", 100, 4);
        stubBridge(PythonRunOutcome.passed(0, PASS_RESULT, "{}", ""));

        service.runTest(id);

        org.mockito.Mockito.verify(bridge).run("concurrent_load", 100, 4);
    }

    @Test
    void terminalAccessorRejectsNonTerminalRecord() {
        TestService service = serviceWith(new NoopExecutor());
        UUID id = createTest(service, "basic_ping_pong", 3, null);

        assertThrows(ConflictException.class, () -> service.getTerminalTest(id));

        service.runTest(id);
        assertThrows(ConflictException.class, () -> service.getTerminalTest(id));
    }

    @Test
    void terminalAccessorReturnsFinishedRecord() {
        TestService service = serviceWith(new SynchronousExecutor());
        UUID id = createTest(service, "basic_ping_pong", 3, null);
        stubBridge(PythonRunOutcome.passed(0, PASS_RESULT, "{}", ""));

        service.runTest(id);

        assertEquals(TestStatus.PASSED, service.getTerminalTest(id).getStatus());
    }

    @Test
    void listRunsUnknownIs404AndZeroRunIsEmpty() {
        TestService service = serviceWith(new SynchronousExecutor());
        assertThrows(NotFoundException.class, () -> service.listRuns(UUID.randomUUID()));

        UUID id = createTest(service, "basic_ping_pong", 3, null);
        assertEquals(List.of(), service.listRuns(id));
    }

    @Test
    void listRunsExposesDistinctRunUuidsAndKeepsLatestView() {
        TestService service = serviceWith(new SynchronousExecutor());
        UUID id = createTest(service, "basic_ping_pong", 3, null);
        stubBridge(PythonRunOutcome.passed(0, PASS_RESULT, "{}", ""));

        service.runTest(id);
        stubBridge(PythonRunOutcome.failed(
                FailureCategory.VERIFICATION, 1, PASS_RESULT, "{}", "", "second"
        ));
        service.runTest(id);

        List<RunSummaryDto> history = service.listRuns(id);
        assertEquals(2, history.size());
        assertNotEquals(history.get(0).id(), history.get(1).id());
        assertEquals(id, history.get(0).testId());
        assertEquals(TestStatus.FAILED, history.get(0).status());
        assertEquals(TestStatus.PASSED, history.get(1).status());

        // Latest-view still describes only the newest run.
        assertEquals(TestStatus.FAILED, service.getTest(id).getStatus());
        assertEquals("second", service.getTerminalTest(id).getStderr());
        assertEquals(FailureCategory.VERIFICATION, history.get(0).failureCategory());
        assertEquals(1, history.get(0).exitCode());
    }

    @Test
    void activeQueuedAppearsInHistory() {
        TestService service = serviceWith(new NoopExecutor());
        UUID id = createTest(service, "basic_ping_pong", 3, null);
        service.runTest(id);

        List<RunSummaryDto> history = service.listRuns(id);
        assertEquals(1, history.size());
        assertEquals(TestStatus.QUEUED, history.get(0).status());
        assertNotNull(history.get(0).queuedAt());
        assertNull(history.get(0).finishedAt());
        RunSnapshot latest = lifecycle.findLatest(id).orElseThrow();
        assertEquals(latest.runId(), history.get(0).id());
    }

    private void assertFailedRun(
            PythonRunOutcome outcome,
            FailureCategory expectedCategory,
            Integer expectedExitCode
    ) {
        TestService service = serviceWith(new SynchronousExecutor());
        UUID id = createTest(service, "basic_ping_pong", 3, null);
        stubBridge(outcome);

        service.runTest(id);

        TestRecord record = service.getTest(id);
        assertEquals(TestStatus.FAILED, record.getStatus());
        assertEquals(expectedCategory, record.getFailureCategory());
        assertEquals(expectedExitCode, record.getExitCode());
        assertNotNull(record.getFinishedAt());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int from = text.indexOf(needle); from >= 0; from = text.indexOf(needle, from + 1)) {
            count++;
        }
        return count;
    }

    private void stubBridge(PythonRunOutcome outcome) {
        when(bridge.run(any(), anyInt(), any())).thenReturn(outcome);
    }

    private TestService serviceWith(RunnerTaskExecutor executor) {
        return new TestService(testRepository, lifecycle, bridge, executor);
    }

    private static UUID createTest(
            TestService service,
            String scenario,
            int count,
            Integer clients
    ) {
        CreateTestRequest request = new CreateTestRequest();
        request.setName("t");
        request.setScenario(scenario);
        request.setCount(count);
        request.setClients(clients);
        return service.createTest(request).getId();
    }

    private static final class SynchronousExecutor implements RunnerTaskExecutor {

        @Override
        public void submit(Runnable task) {
            task.run();
        }
    }

    /** Accepts submissions but never runs them, so records stay QUEUED. */
    private static final class NoopExecutor implements RunnerTaskExecutor {

        @Override
        public void submit(Runnable task) {
            // intentionally never executed
        }
    }

    private static final class RejectingExecutor implements RunnerTaskExecutor {

        @Override
        public void submit(Runnable task) {
            throw new RejectedExecutionException("queue full");
        }
    }
}
