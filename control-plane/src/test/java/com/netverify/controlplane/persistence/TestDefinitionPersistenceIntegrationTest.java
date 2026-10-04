package com.netverify.controlplane.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netverify.controlplane.api.dto.CreateTestRequest;
import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.execution.RunnerTaskExecutor;
import com.netverify.controlplane.runner.PythonRunOutcome;
import com.netverify.controlplane.runner.PythonRunnerBridge;
import com.netverify.controlplane.runner.RunnerResultDto;
import com.netverify.controlplane.service.TestService;
import com.netverify.controlplane.store.InMemoryTestStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Opt-in M2 definition-durability tests. Skipped unless PostgreSQL env vars are set.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_URL", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_USER", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_DB_PASSWORD", matches = ".+")
})
class TestDefinitionPersistenceIntegrationTest {

    @Autowired
    private TestRepository testRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void listOrdersByCreatedAtThenCanonicalUuidText() {
        Instant sameCreatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        // Insert in reverse of canonical text order so the query must sort.
        UUID firstInsert = idA.toString().compareTo(idB.toString()) > 0 ? idA : idB;
        UUID secondInsert = firstInsert.equals(idA) ? idB : idA;
        testRepository.insert(new TestEntity(
                firstInsert, "tie-1", "basic_ping_pong", 1, null, sameCreatedAt
        ));
        testRepository.insert(new TestEntity(
                secondInsert, "tie-2", "basic_ping_pong", 1, null, sameCreatedAt
        ));

        List<UUID> expected = List.of(idA, idB).stream()
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .toList();
        List<UUID> actual = testRepository.list().stream()
                .map(TestEntity::id)
                .filter(id -> id.equals(idA) || id.equals(idB))
                .toList();
        assertEquals(expected, actual);
    }

    @Test
    void coldRuntimeStoreStillReadsPersistedDefinitionAsCreated() {
        UUID id = UUID.randomUUID();
        testRepository.insert(new TestEntity(
                id, "durable", "basic_ping_pong", 4, null, Instant.now()
        ));

        // Fresh runtime store simulates backend restart.
        InMemoryTestStore coldStore = new InMemoryTestStore();
        PythonRunnerBridge bridge = mock(PythonRunnerBridge.class);
        TestService service = new TestService(
                testRepository, coldStore, bridge, Runnable::run
        );

        TestRecord viewed = service.getTest(id);
        assertEquals(TestStatus.CREATED, viewed.getStatus());
        assertEquals("durable", viewed.getName());
        assertTrue(coldStore.get(id).isEmpty());
    }

    @Test
    void runTestOnRepositoryOnlyDefinitionWorksAndDoesNotWriteTestRuns() {
        UUID id = UUID.randomUUID();
        testRepository.insert(new TestEntity(
                id, "run-me", "basic_ping_pong", 3, null, Instant.now()
        ));

        InMemoryTestStore coldStore = new InMemoryTestStore();
        PythonRunnerBridge bridge = mock(PythonRunnerBridge.class);
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
        RunnerTaskExecutor executor = Runnable::run;
        TestService service = new TestService(testRepository, coldStore, bridge, executor);

        service.runTest(id);

        assertEquals(TestStatus.PASSED, coldStore.get(id).orElseThrow().getStatus());
        Integer runRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_runs WHERE test_id = ?",
                Integer.class,
                id
        );
        assertEquals(0, runRows);
    }

    @Test
    void createViaServicePersistsDefinitionWithoutRuntimeOrTestRuns() {
        InMemoryTestStore coldStore = new InMemoryTestStore();
        TestService service = new TestService(
                testRepository, coldStore, mock(PythonRunnerBridge.class), Runnable::run
        );
        CreateTestRequest request = new CreateTestRequest();
        request.setName("svc-create");
        request.setScenario("basic_ping_pong");
        request.setCount(8);

        TestRecord created = service.createTest(request);

        assertTrue(testRepository.findById(created.getId()).isPresent());
        assertTrue(coldStore.get(created.getId()).isEmpty());
        Integer runRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_runs WHERE test_id = ?",
                Integer.class,
                created.getId()
        );
        assertEquals(0, runRows);
    }
}
