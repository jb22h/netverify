package com.netverify.controlplane.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.netverify.controlplane.api.dto.CreateTestRequest;
import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.execution.RunnerTaskExecutor;
import com.netverify.controlplane.persistence.InMemoryTestRepository;
import com.netverify.controlplane.persistence.TestEntity;
import com.netverify.controlplane.persistence.TestRepository;
import com.netverify.controlplane.runner.PythonRunOutcome;
import com.netverify.controlplane.runner.PythonRunnerBridge;
import com.netverify.controlplane.runner.RunnerResultDto;
import com.netverify.controlplane.store.InMemoryTestStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestDefinitionServiceTest {

    private TestRepository testRepository;
    private InMemoryTestStore store;
    private PythonRunnerBridge bridge;
    private TestService service;

    @BeforeEach
    void setUp() {
        testRepository = new InMemoryTestRepository();
        store = new InMemoryTestStore();
        bridge = mock(PythonRunnerBridge.class);
        service = new TestService(testRepository, store, bridge, new SynchronousExecutor());
    }

    @Test
    void createInsertsIntoRepositoryWithoutRuntimeState() {
        TestRecord created = create("a", "basic_ping_pong", 10, null);

        assertTrue(testRepository.findById(created.getId()).isPresent());
        assertTrue(store.get(created.getId()).isEmpty());
        assertEquals(TestStatus.CREATED, created.getStatus());
        assertNull(created.getStartedAt());
        assertNull(created.getFinishedAt());
    }

    @Test
    void getPersistedDefinitionWithNoRuntimeIsCreated() {
        TestRecord created = create("solo", "basic_ping_pong", 5, null);

        TestRecord viewed = service.getTest(created.getId());

        assertEquals(TestStatus.CREATED, viewed.getStatus());
        assertEquals("solo", viewed.getName());
        assertNull(viewed.getStartedAt());
        assertNull(viewed.getExitCode());
        assertTrue(store.get(created.getId()).isEmpty());
    }

    @Test
    void listIsDeterministicAndComposesRuntime() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T00:00:01Z");
        UUID idEarly = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idLate = UUID.fromString("00000000-0000-0000-0000-000000000002");
        testRepository.insert(new TestEntity(idEarly, "early", "basic_ping_pong", 1, null, t0));
        testRepository.insert(new TestEntity(idLate, "late", "basic_ping_pong", 2, null, t1));

        store.ensurePresent(new TestRecord(idLate, "stale-name", "basic_ping_pong", 2, null, t1));
        store.get(idLate).orElseThrow().setStatus(TestStatus.RUNNING);
        store.get(idLate).orElseThrow().setStartedAt(Instant.parse("2026-01-01T00:00:02Z"));

        List<TestRecord> listed = service.listTests();

        assertEquals(List.of(idEarly, idLate), listed.stream().map(TestRecord::getId).toList());
        assertEquals(TestStatus.CREATED, listed.get(0).getStatus());
        assertEquals(TestStatus.RUNNING, listed.get(1).getStatus());
        assertEquals("late", listed.get(1).getName()); // definition SoT, not runtime copy
        assertNotNull(listed.get(1).getStartedAt());
    }

    @Test
    void composeKeepsRepositoryDefinitionAuthoritative() {
        UUID id = UUID.randomUUID();
        TestEntity entity = new TestEntity(
                id, "canonical", "basic_ping_pong", 7, null, Instant.now()
        );
        TestRecord staleRuntime = new TestRecord(
                id, "wrong-name", "connect_fail_no_server", 99, null, Instant.EPOCH
        );
        staleRuntime.setStatus(TestStatus.PASSED);
        staleRuntime.setExitCode(0);

        TestRecord view = TestService.compose(entity, Optional.of(staleRuntime));

        assertEquals("canonical", view.getName());
        assertEquals("basic_ping_pong", view.getScenario());
        assertEquals(7, view.getCount());
        assertEquals(entity.createdAt(), view.getCreatedAt());
        assertEquals(TestStatus.PASSED, view.getStatus());
        assertEquals(0, view.getExitCode());
    }

    @Test
    void runTestHydratesRepositoryOnlyDefinition() {
        TestRecord created = create("hydrate", "basic_ping_pong", 3, null);
        assertTrue(store.get(created.getId()).isEmpty());
        when(bridge.run(any(), anyInt(), any())).thenReturn(
                PythonRunOutcome.passed(
                        0,
                        new RunnerResultDto(
                                "basic_ping_pong", true, List.of(), 0, 0.01, Map.of()
                        ),
                        "{}",
                        ""
                )
        );

        service.runTest(created.getId());

        assertEquals(TestStatus.PASSED, store.get(created.getId()).orElseThrow().getStatus());
        verify(bridge).run("basic_ping_pong", 3, null);
    }

    @Test
    void unknownDefinitionIs404ForGetAndTerminalAndRun() {
        UUID missing = UUID.randomUUID();
        assertThrows(NotFoundException.class, () -> service.getTest(missing));
        assertThrows(NotFoundException.class, () -> service.getTerminalTest(missing));
        assertThrows(NotFoundException.class, () -> service.runTest(missing));
    }

    @Test
    void knownColdDefinitionTerminalEndpointsReturn409() {
        UUID id = create("cold", "basic_ping_pong", 3, null).getId();

        ConflictException ex = assertThrows(
                ConflictException.class, () -> service.getTerminalTest(id)
        );
        assertTrue(ex.getMessage().contains("CREATED"));
    }

    @Test
    void ensurePresentDoesNotOverwriteQueuedRuntime() {
        TestRepository repo = new InMemoryTestRepository();
        InMemoryTestStore runtime = new InMemoryTestStore();
        TestService queuedService = new TestService(
                repo, runtime, bridge, task -> { /* never run */ }
        );
        UUID id = createWith(queuedService, "q2", "basic_ping_pong", 3, null).getId();
        queuedService.runTest(id);
        assertEquals(TestStatus.QUEUED, runtime.get(id).orElseThrow().getStatus());

        TestRecord other = new TestRecord(
                id, "other", "basic_ping_pong", 3, null, Instant.now()
        );
        TestRecord ensured = runtime.ensurePresent(other);
        assertEquals(TestStatus.QUEUED, ensured.getStatus());
        assertEquals(TestStatus.QUEUED, runtime.get(id).orElseThrow().getStatus());
    }

    private TestRecord create(String name, String scenario, int count, Integer clients) {
        return createWith(service, name, scenario, count, clients);
    }

    private static TestRecord createWith(
            TestService svc, String name, String scenario, int count, Integer clients
    ) {
        CreateTestRequest request = new CreateTestRequest();
        request.setName(name);
        request.setScenario(scenario);
        request.setCount(count);
        request.setClients(clients);
        return svc.createTest(request);
    }

    private static final class SynchronousExecutor implements RunnerTaskExecutor {
        @Override
        public void submit(Runnable task) {
            task.run();
        }
    }
}
