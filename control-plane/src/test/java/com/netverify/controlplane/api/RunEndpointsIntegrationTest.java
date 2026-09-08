package com.netverify.controlplane.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.runner.PythonRunOutcome;
import com.netverify.controlplane.runner.PythonRunnerBridge;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RunEndpointsIntegrationTest {

    private static final String PASS_STDOUT = """
            {"name":"basic_ping_pong","passed":true,"failures":[],"client_exit_code":0,
             "duration_s":0.05,"metrics":{"requested_count":3}}
            """;

    private static final RunnerResultDto PASS_RESULT = new RunnerResultDto(
            "basic_ping_pong", true, List.of(), 0, 0.05, Map.of("requested_count", 3)
    );

    private static final Set<String> TERMINAL = Set.of("PASSED", "FAILED", "CANCELLED");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PythonRunnerBridge runnerBridge;

    @Test
    void runQueuesThenReportsTerminalResultLogsAndMetrics() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(runnerBridge.run(any(), anyInt(), any())).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("worker was never released");
            }
            return PythonRunOutcome.passed(0, PASS_RESULT, PASS_STDOUT, "runner stderr");
        });

        String id = createTest("queued", "basic_ping_pong", 3, null);

        mockMvc.perform(post("/api/tests/{id}/run", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id", is(id)))
                .andExpect(jsonPath("$.status", is("QUEUED")))
                .andExpect(jsonPath("$.queuedAt").isNotEmpty());

        assertTrue(started.await(5, TimeUnit.SECONDS), "worker never started");

        // Non-terminal reads are conflicts, and the test cannot be started twice.
        mockMvc.perform(get("/api/tests/{id}/results", id)).andExpect(status().isConflict());
        mockMvc.perform(get("/api/tests/{id}/logs", id)).andExpect(status().isConflict());
        mockMvc.perform(get("/api/tests/{id}/metrics", id)).andExpect(status().isConflict());
        mockMvc.perform(post("/api/tests/{id}/run", id)).andExpect(status().isConflict());

        release.countDown();
        awaitTerminal(id);

        mockMvc.perform(get("/api/tests/{id}/results", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PASSED")))
                .andExpect(jsonPath("$.exitCode", is(0)))
                .andExpect(jsonPath("$.failureCategory", nullValue()))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.result.name", is("basic_ping_pong")))
                .andExpect(jsonPath("$.result.passed", is(true)))
                .andExpect(jsonPath("$.result.client_exit_code", is(0)));

        mockMvc.perform(get("/api/tests/{id}/logs", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stdout", containsString("\"passed\":true")))
                .andExpect(jsonPath("$.stderr", containsString("runner stderr")));

        mockMvc.perform(get("/api/tests/{id}/metrics", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PASSED")))
                .andExpect(jsonPath("$.metrics.requested_count", is(3)));
    }

    @Test
    void terminalTestCanBeRunAgain() throws Exception {
        when(runnerBridge.run(any(), anyInt(), any()))
                .thenReturn(PythonRunOutcome.passed(0, PASS_RESULT, PASS_STDOUT, ""));

        String id = createTest("rerun", "basic_ping_pong", 3, null);
        mockMvc.perform(post("/api/tests/{id}/run", id)).andExpect(status().isAccepted());
        awaitTerminal(id);

        mockMvc.perform(post("/api/tests/{id}/run", id)).andExpect(status().isAccepted());
        awaitTerminal(id);

        mockMvc.perform(get("/api/tests/{id}/results", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PASSED")));
    }

    @Test
    void failedRunWithoutParsedResultStillExposesMetricsAndLogs() throws Exception {
        when(runnerBridge.run(any(), anyInt(), any())).thenReturn(
                PythonRunOutcome.preLaunchFailure(FailureCategory.LAUNCH, "no interpreter")
        );

        String id = createTest("launch-fail", "basic_ping_pong", 3, null);
        mockMvc.perform(post("/api/tests/{id}/run", id)).andExpect(status().isAccepted());
        awaitTerminal(id);

        mockMvc.perform(get("/api/tests/{id}/results", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("FAILED")))
                .andExpect(jsonPath("$.failureCategory", is("LAUNCH")))
                .andExpect(jsonPath("$.result", nullValue()));

        mockMvc.perform(get("/api/tests/{id}/metrics", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCategory", is("LAUNCH")))
                .andExpect(jsonPath("$.metrics").isMap())
                .andExpect(jsonPath("$.metrics").isEmpty());

        mockMvc.perform(get("/api/tests/{id}/logs", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stderr", containsString("no interpreter")));
    }

    @Test
    void runUnknownTestReturns404() throws Exception {
        mockMvc.perform(post("/api/tests/{id}/run", "00000000-0000-0000-0000-000000000009"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resultReadsForUnknownTestReturn404() throws Exception {
        String missing = "00000000-0000-0000-0000-00000000000a";
        mockMvc.perform(get("/api/tests/{id}/results", missing)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tests/{id}/logs", missing)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tests/{id}/metrics", missing)).andExpect(status().isNotFound());
    }

    @Test
    void malformedIdReturns400() throws Exception {
        mockMvc.perform(get("/api/tests/{id}/results", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void latencyInjectionCannotBeCreated() throws Exception {
        mockMvc.perform(
                        post("/api/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"nope","scenario":"latency_injection","count":3}
                                        """
                                )
                )
                .andExpect(status().isBadRequest());
    }

    private String createTest(String name, String scenario, int count, Integer clients)
            throws Exception {
        StringBuilder body = new StringBuilder()
                .append("{\"name\":\"").append(name)
                .append("\",\"scenario\":\"").append(scenario)
                .append("\",\"count\":").append(count);
        if (clients != null) {
            body.append(",\"clients\":").append(clients);
        }
        body.append("}");

        String response = mockMvc.perform(
                        post("/api/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body.toString())
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private void awaitTerminal(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (TERMINAL.contains(statusOf(id))) {
                return;
            }
            Thread.sleep(25);
        }
        fail("test " + id + " never reached a terminal state (last status " + statusOf(id) + ")");
    }

    private String statusOf(String id) throws Exception {
        String body = mockMvc.perform(get("/api/tests/{id}", id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("status").asText();
    }
}
