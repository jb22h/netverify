package com.netverify.controlplane.service;

import com.netverify.controlplane.api.dto.CreateTestRequest;
import com.netverify.controlplane.domain.AllowedScenarios;
import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.execution.RunnerTaskExecutor;
import com.netverify.controlplane.persistence.QueueRunResult;
import com.netverify.controlplane.persistence.RunLifecycleStore;
import com.netverify.controlplane.persistence.RunSnapshot;
import com.netverify.controlplane.persistence.TestEntity;
import com.netverify.controlplane.persistence.TestRepository;
import com.netverify.controlplane.runner.PythonRunOutcome;
import com.netverify.controlplane.runner.PythonRunnerBridge;
import com.netverify.controlplane.store.TerminalUpdate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TestService {

    private static final Logger log = LoggerFactory.getLogger(TestService.class);

    private final TestRepository testRepository;
    private final RunLifecycleStore lifecycle;
    private final PythonRunnerBridge runnerBridge;
    private final RunnerTaskExecutor executor;

    public TestService(
            TestRepository testRepository,
            RunLifecycleStore lifecycle,
            PythonRunnerBridge runnerBridge,
            RunnerTaskExecutor executor
    ) {
        this.testRepository = testRepository;
        this.lifecycle = lifecycle;
        this.runnerBridge = runnerBridge;
        this.executor = executor;
    }

    public TestRecord createTest(CreateTestRequest request) {
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty()) {
            throw new BadRequestException("name must not be blank");
        }
        if (name.length() > AllowedScenarios.NAME_MAX_LENGTH) {
            throw new BadRequestException(
                    "name length must be <= " + AllowedScenarios.NAME_MAX_LENGTH
            );
        }

        String scenario = request.getScenario() == null ? "" : request.getScenario().trim();
        if (scenario.isEmpty()) {
            throw new BadRequestException("scenario must not be blank");
        }
        if ("latency_injection".equals(scenario)) {
            throw new BadRequestException(
                    "scenario latency_injection is not allowed through the HTTP control plane"
            );
        }
        if (!AllowedScenarios.isAllowed(scenario)) {
            throw new BadRequestException("unknown scenario: " + scenario);
        }

        Integer count = request.getCount();
        if (count == null
                || count < AllowedScenarios.COUNT_MIN
                || count > AllowedScenarios.COUNT_MAX) {
            throw new BadRequestException(
                    "count must be between "
                            + AllowedScenarios.COUNT_MIN
                            + " and "
                            + AllowedScenarios.COUNT_MAX
            );
        }

        Integer clients = normalizeClients(scenario, request.getClients());

        TestEntity entity = new TestEntity(
                UUID.randomUUID(),
                name,
                scenario,
                count,
                clients,
                Instant.now()
        );
        testRepository.insert(entity);
        return compose(entity, Optional.empty());
    }

    public List<TestRecord> listTests() {
        return testRepository.list().stream()
                .map(entity -> compose(entity, lifecycle.findLatest(entity.id())))
                .toList();
    }

    public TestRecord getTest(UUID id) {
        TestEntity entity = requireDefinition(id);
        return compose(entity, lifecycle.findLatest(id));
    }

    /**
     * Returns a composed view only once the latest run is terminal.
     * Unknown definitions → 404; known but cold / non-terminal → 409.
     */
    public TestRecord getTerminalTest(UUID id) {
        TestRecord record = getTest(id);
        if (!isTerminal(record.getStatus())) {
            throw new ConflictException(
                    "test " + id + " has no result yet (status " + record.getStatus() + ")"
            );
        }
        return record;
    }

    /**
     * Queues a run and hands execution to the bounded executor.
     *
     * @return test id and accepted queued timestamp (public id remains the test id)
     */
    public QueuedRun runTest(UUID id) {
        TestEntity definition = requireDefinition(id);
        UUID runId = UUID.randomUUID();
        Instant queuedAt = Instant.now();

        QueueRunResult queued = lifecycle.tryQueue(definition, runId, queuedAt);
        if (queued == QueueRunResult.CONFLICT) {
            throw new ConflictException("test " + id + " is already queued or running");
        }

        try {
            executor.submit(() -> executeRun(id, runId));
        } catch (RejectedExecutionException ex) {
            lifecycle.tryFailQueued(
                    id,
                    runId,
                    FailureCategory.LAUNCH,
                    "runner executor rejected submission: " + ex,
                    Instant.now()
            );
            throw new ExecutorSaturatedException(
                    "runner executor is saturated; retry later"
            );
        }
        return new QueuedRun(id, queuedAt);
    }

    void executeRun(UUID testId, UUID runId) {
        try {
            if (!lifecycle.tryStartRunning(testId, runId, Instant.now())) {
                // State changed underneath us; do not launch Python.
                return;
            }

            TestEntity entity = testRepository.findById(testId).orElse(null);
            if (entity == null) {
                log.error("definition missing for running test {}", testId);
                lifecycle.tryComplete(
                        testId,
                        runId,
                        TerminalUpdate.failed(
                                FailureCategory.LAUNCH, null, null, "",
                                "test definition missing during run"
                        ),
                        Instant.now()
                );
                return;
            }

            PythonRunOutcome outcome;
            try {
                outcome = runnerBridge.run(
                        entity.scenario(), entity.count(), entity.clients()
                );
            } catch (RuntimeException ex) {
                log.warn("runner bridge failed for test {}", testId, ex);
                outcome = PythonRunOutcome.preLaunchFailure(
                        FailureCategory.LAUNCH, "unexpected runner error: " + ex
                );
            }
            lifecycle.tryComplete(testId, runId, toTerminalUpdate(outcome), Instant.now());
        } catch (RuntimeException ex) {
            log.error("unexpected worker failure for test {}", testId, ex);
            lifecycle.tryComplete(
                    testId,
                    runId,
                    TerminalUpdate.failed(
                            FailureCategory.LAUNCH, null, null, "",
                            "unexpected worker error: " + ex
                    ),
                    Instant.now()
            );
        }
    }

    private TestEntity requireDefinition(UUID id) {
        return testRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("test not found: " + id));
    }

    /**
     * Definition fields always from {@link TestEntity}; mutable run fields from
     * {@link RunSnapshot} when present, otherwise CREATED with null run metadata.
     */
    static TestRecord compose(TestEntity entity, Optional<RunSnapshot> latest) {
        TestRecord view = skeletonFrom(entity);
        if (latest.isEmpty()) {
            return view;
        }
        RunSnapshot run = latest.get();
        view.setStatus(run.status());
        view.setStartedAt(run.startedAt());
        view.setFinishedAt(run.finishedAt());
        view.setExitCode(run.exitCode());
        view.setRunnerResult(run.runnerResult());
        view.setStdout(run.stdout());
        view.setStderr(run.stderr());
        view.setFailureCategory(run.failureCategory());
        return view;
    }

    static TestRecord skeletonFrom(TestEntity entity) {
        return new TestRecord(
                entity.id(),
                entity.name(),
                entity.scenario(),
                entity.count(),
                entity.clients(),
                entity.createdAt()
        );
    }

    private static TerminalUpdate toTerminalUpdate(PythonRunOutcome outcome) {
        String stderr = outcome.stderr() == null ? "" : outcome.stderr();
        if (outcome.message() != null && !outcome.message().isBlank()) {
            stderr = stderr.isBlank() ? outcome.message() : stderr + System.lineSeparator()
                    + outcome.message();
        }
        if (outcome.terminalStatus() == TestStatus.PASSED) {
            return TerminalUpdate.passed(
                    outcome.exitCode(), outcome.runnerResult(), outcome.stdout(), stderr
            );
        }
        return TerminalUpdate.failed(
                outcome.failureCategory() == null ? FailureCategory.LAUNCH : outcome.failureCategory(),
                outcome.exitCode(),
                outcome.runnerResult(),
                outcome.stdout(),
                stderr
        );
    }

    private static boolean isTerminal(TestStatus status) {
        return status == TestStatus.PASSED
                || status == TestStatus.FAILED
                || status == TestStatus.CANCELLED;
    }

    private static Integer normalizeClients(String scenario, Integer requested) {
        if (!AllowedScenarios.isConcurrentLoad(scenario)) {
            return null;
        }
        int clients = requested == null ? AllowedScenarios.CLIENTS_DEFAULT : requested;
        if (clients < AllowedScenarios.CLIENTS_MIN
                || clients > AllowedScenarios.CLIENTS_MAX) {
            throw new BadRequestException(
                    "clients must be between "
                            + AllowedScenarios.CLIENTS_MIN
                            + " and "
                            + AllowedScenarios.CLIENTS_MAX
                            + " for concurrent_load"
            );
        }
        return clients;
    }

    /** Accepted-run acknowledgement; public {@code id} is the test id. */
    public record QueuedRun(UUID id, Instant queuedAt) {
    }
}
