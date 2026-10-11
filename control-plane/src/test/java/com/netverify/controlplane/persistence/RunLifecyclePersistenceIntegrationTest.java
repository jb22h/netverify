package com.netverify.controlplane.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netverify.controlplane.config.ActiveRunRecovery;
import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.execution.DispatchRejectedException;
import com.netverify.controlplane.execution.LocalRunDispatcher;
import com.netverify.controlplane.execution.LocalRunExecutionCoordinator;
import com.netverify.controlplane.execution.RunExecutionService;
import com.netverify.controlplane.execution.RunnerTaskExecutor;
import com.netverify.controlplane.persistence.jdbc.JdbcRunLifecycleStore;
import com.netverify.controlplane.runner.PythonRunOutcome;
import com.netverify.controlplane.runner.PythonRunnerBridge;
import com.netverify.controlplane.runner.RunnerResultDto;
import com.netverify.controlplane.service.ConflictException;
import com.netverify.controlplane.service.TestService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Opt-in M3 run-lifecycle PostgreSQL tests. Skipped unless DB env vars are set.
 *
 * <p>Latest ordering uses {@code queued_at DESC, id::text DESC}. The {@code id::text}
 * tie-break matches the M2 canonical UUID-text contract for semantic consistency;
 * it is not claimed that the native-UUID index fully covers that expression order.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_URL", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_USER", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_PASSWORD", matches = ".+")
})
class RunLifecyclePersistenceIntegrationTest {

    @Autowired
    private TestRepository testRepository;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActiveRunRecovery activeRunRecovery;

    @Test
    void queuedRunningTerminalAndStaleTransitions() {
        TestEntity def = insertDefinition("lifecycle");
        UUID runId = UUID.randomUUID();
        Instant queuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        assertEquals(1, testRunRepository.tryInsertQueued(runId, def.id(), queuedAt));
        assertEquals(0, testRunRepository.tryInsertQueued(UUID.randomUUID(), def.id(), queuedAt));

        assertEquals(1, testRunRepository.tryMarkRunning(runId, Instant.now()));
        assertEquals(0, testRunRepository.tryMarkRunning(runId, Instant.now()));

        assertEquals(
                1,
                testRunRepository.tryComplete(
                        runId,
                        TestStatus.PASSED,
                        0,
                        null,
                        "{}",
                        "",
                        new RunnerResultDto("basic_ping_pong", true, List.of(), 0, 0.01, Map.of()),
                        Instant.now()
                )
        );
        assertEquals(
                0,
                testRunRepository.tryComplete(
                        runId,
                        TestStatus.FAILED,
                        1,
                        FailureCategory.VERIFICATION,
                        "",
                        "nope",
                        null,
                        Instant.now()
                )
        );

        TestRunEntity latest = testRunRepository.findLatestByTestId(def.id()).orElseThrow();
        assertEquals(runId, latest.id());
        assertEquals(TestStatus.PASSED, latest.status());
    }

    @Test
    void concurrentRunWhileActiveYieldsOneConflict() throws Exception {
        TestEntity def = insertDefinition("concurrent");
        AtomicReference<Runnable> heldTask = new AtomicReference<>();

        // Capture the accepted worker without executing it so the active row remains
        // while both callers race through tryInsertQueued.
        RunnerTaskExecutor blockingExecutor = task -> {
            if (!heldTask.compareAndSet(null, task)) {
                throw new IllegalStateException("unexpected second worker submission");
            }
        };

        PythonRunnerBridge bridge = mock(PythonRunnerBridge.class);
        when(bridge.run(any(), anyInt(), any())).thenReturn(
                PythonRunOutcome.passed(
                        0,
                        new RunnerResultDto("basic_ping_pong", true, List.of(), 0, 0.01, Map.of()),
                        "{}",
                        ""
                )
        );

        TestService service = new TestService(
                testRepository,
                new JdbcRunLifecycleStore(testRunRepository),
                new LocalRunDispatcher(
                        blockingExecutor,
                        new LocalRunExecutionCoordinator(
                                new JdbcRunLifecycleStore(testRunRepository),
                                testRepository,
                                new RunExecutionService(bridge)
                        )
                )
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        List<RunCallOutcome> outcomes = new ArrayList<>();
        try {
            Callable<RunCallOutcome> call = () -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS), "release barrier timed out");
                try {
                    service.runTest(def.id());
                    return RunCallOutcome.ACCEPTED;
                } catch (ConflictException ex) {
                    return RunCallOutcome.CONFLICT;
                }
            };
            Future<RunCallOutcome> first = callers.submit(call);
            Future<RunCallOutcome> second = callers.submit(call);

            assertTrue(ready.await(5, TimeUnit.SECONDS), "callers never became ready");
            go.countDown();

            outcomes.add(first.get(10, TimeUnit.SECONDS));
            outcomes.add(second.get(10, TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(
                EnumSet.of(RunCallOutcome.ACCEPTED, RunCallOutcome.CONFLICT),
                EnumSet.copyOf(outcomes)
        );
        assertEquals(1, outcomes.stream().filter(o -> o == RunCallOutcome.ACCEPTED).count());
        assertEquals(1, outcomes.stream().filter(o -> o == RunCallOutcome.CONFLICT).count());

        assertNotNull(heldTask.get(), "accepted worker task was not captured");

        Integer activeWhileHeld = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM test_runs
                WHERE test_id = ? AND status IN ('QUEUED', 'RUNNING')
                """,
                Integer.class,
                def.id()
        );
        assertEquals(1, activeWhileHeld);

        heldTask.get().run();

        Integer activeAfter = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM test_runs
                WHERE test_id = ? AND status IN ('QUEUED', 'RUNNING')
                """,
                Integer.class,
                def.id()
        );
        assertEquals(0, activeAfter);
        assertEquals(TestStatus.PASSED, service.getTest(def.id()).getStatus());
    }

    @Test
    void tryInsertQueuedWithMissingDefinitionPropagatesForeignKeyViolation() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> testRunRepository.tryInsertQueued(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Instant.now()
                )
        );
    }

    @Test
    void executorRejectionFailsQueuedAndAllowsRetry() {
        TestEntity def = insertDefinition("reject");
        RunnerTaskExecutor rejecting = task -> {
            throw new RejectedExecutionException("full");
        };
        TestService service = localTestService(
                testRepository,
                testRunRepository,
                mock(PythonRunnerBridge.class),
                rejecting
        );

        assertThrows(DispatchRejectedException.class, () -> service.runTest(def.id()));
        assertEquals(TestStatus.FAILED, service.getTest(def.id()).getStatus());
        assertEquals(
                FailureCategory.LAUNCH,
                service.getTerminalTest(def.id()).getFailureCategory()
        );

        Integer active = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM test_runs
                WHERE test_id = ? AND status IN ('QUEUED', 'RUNNING')
                """,
                Integer.class,
                def.id()
        );
        assertEquals(0, active);

        PythonRunnerBridge bridge = mock(PythonRunnerBridge.class);
        when(bridge.run(any(), anyInt(), any())).thenReturn(
                PythonRunOutcome.passed(
                        0,
                        new RunnerResultDto("basic_ping_pong", true, List.of(), 0, 0.01, Map.of()),
                        "{}",
                        ""
                )
        );
        TestService retry = new TestService(
                testRepository,
                new JdbcRunLifecycleStore(testRunRepository),
                new LocalRunDispatcher(
                        Runnable::run,
                        new LocalRunExecutionCoordinator(
                                new JdbcRunLifecycleStore(testRunRepository),
                                testRepository,
                                new RunExecutionService(bridge)
                        )
                )
        );
        retry.runTest(def.id());
        assertEquals(TestStatus.PASSED, retry.getTest(def.id()).getStatus());
    }

    @Test
    void latestTieBreakUsesIdTextDesc() {
        TestEntity def = insertDefinition("tie");
        Instant same = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        TieRunIds tie = freshTieRunIds();

        testRunRepository.insert(new TestRunEntity(
                tie.low(), def.id(), TestStatus.PASSED, same, same, same,
                0, null, "low", "", null, null, null
        ));
        testRunRepository.insert(new TestRunEntity(
                tie.high(), def.id(), TestStatus.PASSED, same, same, same,
                0, null, "high", "", null, null, null
        ));

        TestRunEntity latest = testRunRepository.findLatestByTestId(def.id()).orElseThrow();
        assertEquals("high", latest.stdout());
        assertEquals(tie.high(), latest.id());
    }

    @Test
    void startupRecoveryCancelsActiveAndAllowsRerun() {
        TestEntity def = insertDefinition("recover");
        UUID orphan = UUID.randomUUID();
        assertEquals(1, testRunRepository.tryInsertQueued(orphan, def.id(), Instant.now()));
        assertEquals(1, testRunRepository.tryMarkRunning(orphan, Instant.now()));

        activeRunRecovery.run(new DefaultApplicationArguments(new String[0]));

        TestRunEntity orphanRow = testRunRepository.findById(orphan).orElseThrow();
        assertEquals(TestStatus.CANCELLED, orphanRow.status());
        assertNotNull(orphanRow.finishedAt());
        assertTrue(orphanRow.stderr().contains(ActiveRunRecovery.ORPHAN_NOTE));
        assertNull(orphanRow.failureCategory());

        // Idempotent: no active rows remain for a second recovery pass.
        activeRunRecovery.run(new DefaultApplicationArguments(new String[0]));
        assertEquals(0, testRunRepository.cancelAllActive(
                Instant.now(), ActiveRunRecovery.ORPHAN_NOTE
        ));

        PythonRunnerBridge bridge = mock(PythonRunnerBridge.class);
        when(bridge.run(any(), anyInt(), any())).thenReturn(
                PythonRunOutcome.passed(
                        0,
                        new RunnerResultDto("basic_ping_pong", true, List.of(), 0, 0.01, Map.of()),
                        "{}",
                        ""
                )
        );
        TestService service = new TestService(
                testRepository,
                new JdbcRunLifecycleStore(testRunRepository),
                new LocalRunDispatcher(
                        Runnable::run,
                        new LocalRunExecutionCoordinator(
                                new JdbcRunLifecycleStore(testRunRepository),
                                testRepository,
                                new RunExecutionService(bridge)
                        )
                )
        );
        service.runTest(def.id());
        assertEquals(TestStatus.PASSED, service.getTest(def.id()).getStatus());
    }

    @Test
    void recoveryIsSafeWithZeroActiveRows() {
        int recovered = testRunRepository.cancelAllActive(
                Instant.now(), ActiveRunRecovery.ORPHAN_NOTE
        );
        assertTrue(recovered >= 0);
        assertEquals(0, testRunRepository.cancelAllActive(
                Instant.now(), ActiveRunRecovery.ORPHAN_NOTE
        ));
    }

    @Test
    void persistedTerminalSurvivesFreshService() {
        TestEntity def = insertDefinition("fresh");
        PythonRunnerBridge bridge = mock(PythonRunnerBridge.class);
        when(bridge.run(any(), anyInt(), any())).thenReturn(
                PythonRunOutcome.passed(
                        0,
                        new RunnerResultDto(
                                "basic_ping_pong", true, List.of(), 0, 0.02,
                                Map.of("requested_count", 3)
                        ),
                        "{\"ok\":true}",
                        ""
                )
        );
        new TestService(
                testRepository,
                new JdbcRunLifecycleStore(testRunRepository),
                new LocalRunDispatcher(
                        Runnable::run,
                        new LocalRunExecutionCoordinator(
                                new JdbcRunLifecycleStore(testRunRepository),
                                testRepository,
                                new RunExecutionService(bridge)
                        )
                )
        ).runTest(def.id());

        TestService cold = localTestService(
                testRepository,
                testRunRepository,
                mock(PythonRunnerBridge.class),
                Runnable::run
        );
        assertEquals(TestStatus.PASSED, cold.getTerminalTest(def.id()).getStatus());
        assertEquals("{\"ok\":true}", cold.getTerminalTest(def.id()).getStdout());
        assertEquals(
                3,
                ((Number) ((RunnerResultDto) cold.getTerminalTest(def.id()).getRunnerResult())
                        .metrics()
                        .get("requested_count")).intValue()
        );
    }

    @Test
    void acceptedRunsGetDistinctInternalIds() {
        TestEntity def = insertDefinition("ids");
        PythonRunnerBridge bridge = mock(PythonRunnerBridge.class);
        when(bridge.run(any(), anyInt(), any())).thenReturn(
                PythonRunOutcome.passed(
                        0,
                        new RunnerResultDto("basic_ping_pong", true, List.of(), 0, 0.01, Map.of()),
                        "{}",
                        ""
                )
        );
        TestService service = new TestService(
                testRepository,
                new JdbcRunLifecycleStore(testRunRepository),
                new LocalRunDispatcher(
                        Runnable::run,
                        new LocalRunExecutionCoordinator(
                                new JdbcRunLifecycleStore(testRunRepository),
                                testRepository,
                                new RunExecutionService(bridge)
                        )
                )
        );
        service.runTest(def.id());
        service.runTest(def.id());

        List<UUID> runIds = jdbcTemplate.query(
                "SELECT id FROM test_runs WHERE test_id = ? ORDER BY queued_at",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                def.id()
        );
        assertEquals(2, runIds.size());
        assertFalse(runIds.get(0).equals(runIds.get(1)));
    }

    @Test
    void findAllByTestIdAndListRunsEmptyThenNewestFirst() {
        TestEntity def = insertDefinition("history");
        JdbcRunLifecycleStore lifecycle = new JdbcRunLifecycleStore(testRunRepository);
        assertEquals(List.of(), testRunRepository.findAllByTestId(def.id()));
        assertEquals(List.of(), lifecycle.listRuns(def.id()));

        Instant older = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(30);
        Instant newer = older.plusSeconds(10);
        UUID runOld = UUID.randomUUID();
        UUID runNew = UUID.randomUUID();
        insertCompleted(runOld, def.id(), older, "old");
        insertCompleted(runNew, def.id(), newer, "new");

        List<TestRunEntity> rows = testRunRepository.findAllByTestId(def.id());
        assertEquals(List.of(runNew, runOld), rows.stream().map(TestRunEntity::id).toList());
        assertEquals(
                List.of(runNew, runOld),
                lifecycle.listRuns(def.id()).stream().map(RunSnapshot::runId).toList()
        );
    }

    @Test
    void listRunsTieBreakUsesIdTextDesc() {
        TestEntity def = insertDefinition("history-tie");
        Instant same = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        TieRunIds tie = freshTieRunIds();
        insertCompleted(tie.low(), def.id(), same, "low");
        insertCompleted(tie.high(), def.id(), same, "high");

        assertEquals(
                List.of(tie.high(), tie.low()),
                testRunRepository.findAllByTestId(def.id()).stream()
                        .map(TestRunEntity::id)
                        .toList()
        );
    }

    @Test
    void historySurvivesFreshServiceAndKeepsLatestOnlyDetails() {
        TestEntity def = insertDefinition("history-fresh");
        PythonRunnerBridge bridge = mock(PythonRunnerBridge.class);
        when(bridge.run(any(), anyInt(), any()))
                .thenReturn(PythonRunOutcome.passed(
                        0,
                        new RunnerResultDto("basic_ping_pong", true, List.of(), 0, 0.01, Map.of()),
                        "first",
                        ""
                ))
                .thenReturn(PythonRunOutcome.failed(
                        FailureCategory.VERIFICATION,
                        1,
                        null,
                        "second",
                        "fail",
                        "nope"
                ));
        TestService first = new TestService(
                testRepository,
                new JdbcRunLifecycleStore(testRunRepository),
                new LocalRunDispatcher(
                        Runnable::run,
                        new LocalRunExecutionCoordinator(
                                new JdbcRunLifecycleStore(testRunRepository),
                                testRepository,
                                new RunExecutionService(bridge)
                        )
                )
        );
        first.runTest(def.id());
        first.runTest(def.id());

        TestService cold = localTestService(
                testRepository,
                testRunRepository,
                mock(PythonRunnerBridge.class),
                Runnable::run
        );
        assertEquals(2, cold.listRuns(def.id()).size());
        assertEquals(TestStatus.FAILED, cold.listRuns(def.id()).get(0).status());
        assertEquals(TestStatus.FAILED, cold.getTerminalTest(def.id()).getStatus());
        assertEquals("second", cold.getTerminalTest(def.id()).getStdout());
    }

    @Test
    void rejectedAndRecoveredRunsAppearInHistory() {
        TestEntity def = insertDefinition("history-reject-recover");
        TestService rejecting = localTestService(
                testRepository,
                testRunRepository,
                mock(PythonRunnerBridge.class),
                task -> {
                    throw new RejectedExecutionException("full");
                }
        );
        assertThrows(DispatchRejectedException.class, () -> rejecting.runTest(def.id()));
        assertEquals(1, rejecting.listRuns(def.id()).size());
        assertEquals(TestStatus.FAILED, rejecting.listRuns(def.id()).get(0).status());
        assertEquals(
                FailureCategory.LAUNCH,
                rejecting.listRuns(def.id()).get(0).failureCategory()
        );

        UUID orphan = UUID.randomUUID();
        assertEquals(1, testRunRepository.tryInsertQueued(orphan, def.id(), Instant.now()));
        assertEquals(1, testRunRepository.tryMarkRunning(orphan, Instant.now()));
        activeRunRecovery.run(new DefaultApplicationArguments(new String[0]));

        TestService cold = localTestService(
                testRepository,
                testRunRepository,
                mock(PythonRunnerBridge.class),
                Runnable::run
        );
        List<com.netverify.controlplane.api.dto.RunSummaryDto> history = cold.listRuns(def.id());
        assertTrue(history.stream().anyMatch(r -> r.id().equals(orphan)
                && r.status() == TestStatus.CANCELLED
                && r.finishedAt() != null
                && r.failureCategory() == null));
        assertTrue(history.stream().anyMatch(r ->
                r.status() == TestStatus.FAILED && r.failureCategory() == FailureCategory.LAUNCH
        ));
    }

    private void insertCompleted(UUID runId, UUID testId, Instant queuedAt, String stdout) {
        testRunRepository.insert(new TestRunEntity(
                runId,
                testId,
                TestStatus.PASSED,
                queuedAt,
                queuedAt,
                queuedAt,
                0,
                null,
                stdout,
                "",
                null,
                null,
                null
        ));
    }

    private TestEntity insertDefinition(String name) {
        TestEntity entity = new TestEntity(
                UUID.randomUUID(), name, "basic_ping_pong", 3, null, Instant.now()
        );
        testRepository.insert(entity);
        return entity;
    }

    /**
     * Fresh run PKs with obvious canonical-text order (no retry loop).
     * Keeps the first 35 chars of a random UUID, then ends with {@code 0} / {@code f}.
     */
    private static TieRunIds freshTieRunIds() {
        String prefix = UUID.randomUUID().toString().substring(0, 35);
        UUID low = UUID.fromString(prefix + "0");
        UUID high = UUID.fromString(prefix + "f");
        assertTrue(low.toString().compareTo(high.toString()) < 0);
        return new TieRunIds(low, high);
    }

    private record TieRunIds(UUID low, UUID high) {
    }

    private enum RunCallOutcome {
        ACCEPTED,
        CONFLICT
    }

    private static TestService localTestService(
            TestRepository testRepository,
            TestRunRepository testRunRepository,
            PythonRunnerBridge bridge,
            RunnerTaskExecutor executor
    ) {
        RunLifecycleStore store = new JdbcRunLifecycleStore(testRunRepository);
        return new TestService(
                testRepository,
                store,
                new LocalRunDispatcher(
                        executor,
                        new LocalRunExecutionCoordinator(
                                store, testRepository, new RunExecutionService(bridge)
                        )
                )
        );
    }

}
