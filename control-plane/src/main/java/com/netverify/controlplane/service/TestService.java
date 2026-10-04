package com.netverify.controlplane.service;

import com.netverify.controlplane.api.dto.CreateTestRequest;
import com.netverify.controlplane.domain.AllowedScenarios;
import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.execution.RunnerTaskExecutor;
import com.netverify.controlplane.persistence.TestEntity;
import com.netverify.controlplane.persistence.TestRepository;
import com.netverify.controlplane.runner.PythonRunOutcome;
import com.netverify.controlplane.runner.PythonRunnerBridge;
import com.netverify.controlplane.store.InMemoryTestStore;
import com.netverify.controlplane.store.QueueTransitionResult;
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
    private final InMemoryTestStore store;
    private final PythonRunnerBridge runnerBridge;
    private final RunnerTaskExecutor executor;

    public TestService(
            TestRepository testRepository,
            InMemoryTestStore store,
            PythonRunnerBridge runnerBridge,
            RunnerTaskExecutor executor
    ) {
        this.testRepository = testRepository;
        this.store = store;
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
        // Definition-only create: no runtime row until /run.
        return compose(entity, Optional.empty());
    }

    public List<TestRecord> listTests() {
        return testRepository.list().stream()
                .map(entity -> compose(entity, store.get(entity.id())))
                .toList();
    }

    public TestRecord getTest(UUID id) {
        TestEntity entity = requireDefinition(id);
        return compose(entity, store.get(id));
    }

    /**
     * Returns a composed view only once the in-memory run is terminal.
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
     * Queues a run atomically and hands execution to the bounded executor.
     *
     * @return the id/queue timestamp of the accepted run
     */
    public QueuedRun runTest(UUID id) {
        TestEntity entity = requireDefinition(id);
        store.ensurePresent(skeletonFrom(entity));

        QueueTransitionResult queued = store.tryQueueForRun(id);
        switch (queued) {
            case NOT_FOUND -> throw new NotFoundException("test not found: " + id);
            case CONFLICT -> throw new ConflictException(
                    "test " + id + " is already queued or running"
            );
            case SUCCESS -> { /* proceed */ }
        }

        // Stamped before submission so a synchronous or very fast executor cannot
        // report a queuedAt later than the run it already finished.
        Instant queuedAt = Instant.now();
        try {
            executor.submit(() -> executeRun(id));
        } catch (RejectedExecutionException ex) {
            // Never leave the record stuck in QUEUED when nothing will run it.
            store.failQueued(
                    id, FailureCategory.LAUNCH, "runner executor rejected submission: " + ex
            );
            throw new ExecutorSaturatedException(
                    "runner executor is saturated; retry later"
            );
        }
        return new QueuedRun(id, queuedAt);
    }

    void executeRun(UUID id) {
        try {
            TestRecord record = store.get(id).orElse(null);
            if (record == null) {
                return;
            }
            if (store.tryStartRunning(id) != QueueTransitionResult.SUCCESS) {
                // State changed underneath us; do not launch Python.
                return;
            }

            // Prefer durable definition fields over any stale copies on the runtime row.
            TestEntity entity = testRepository.findById(id).orElse(null);
            String scenario = entity != null ? entity.scenario() : record.getScenario();
            int count = entity != null ? entity.count() : record.getCount();
            Integer clients = entity != null ? entity.clients() : record.getClients();

            PythonRunOutcome outcome;
            try {
                outcome = runnerBridge.run(scenario, count, clients);
            } catch (RuntimeException ex) {
                log.warn("runner bridge failed for test {}", id, ex);
                outcome = PythonRunOutcome.preLaunchFailure(
                        FailureCategory.LAUNCH, "unexpected runner error: " + ex
                );
            }
            store.completeRun(id, toTerminalUpdate(outcome));
        } catch (RuntimeException ex) {
            // Last resort: a worker must never strand a record in RUNNING.
            log.error("unexpected worker failure for test {}", id, ex);
            store.completeRun(
                    id,
                    TerminalUpdate.failed(
                            FailureCategory.LAUNCH, null, null, "",
                            "unexpected worker error: " + ex
                    )
            );
        }
    }

    private TestEntity requireDefinition(UUID id) {
        return testRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("test not found: " + id));
    }

    /**
     * Definition fields always from {@link TestEntity}; mutable run fields from
     * runtime when present, otherwise CREATED with null run metadata.
     */
    static TestRecord compose(TestEntity entity, Optional<TestRecord> runtime) {
        TestRecord view = skeletonFrom(entity);
        if (runtime.isEmpty()) {
            return view;
        }
        TestRecord run = runtime.get();
        view.setStatus(run.getStatus());
        view.setStartedAt(run.getStartedAt());
        view.setFinishedAt(run.getFinishedAt());
        view.setExitCode(run.getExitCode());
        view.setRunnerResult(run.getRunnerResult());
        view.setStdout(run.getStdout());
        view.setStderr(run.getStderr());
        view.setFailureCategory(run.getFailureCategory());
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

    /** Accepted-run acknowledgement, independent of how fast the worker starts. */
    public record QueuedRun(UUID id, Instant queuedAt) {
    }
}
