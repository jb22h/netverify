package com.netverify.controlplane.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netverify.controlplane.execution.RunnerTaskExecutor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** A saturated executor must answer 503 and never leave the record QUEUED. */
@SpringBootTest
@AutoConfigureMockMvc
class RunRejectionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RunnerTaskExecutor executor;

    @Test
    void saturatedExecutorReturns503AndFailsRecord() throws Exception {
        doThrow(new RejectedExecutionException("queue full")).when(executor).submit(any());

        String response = mockMvc.perform(
                        post("/api/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"saturated","scenario":"basic_ping_pong","count":3}
                                        """
                                )
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(post("/api/tests/{id}/run", id))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status", is(503)));

        mockMvc.perform(get("/api/tests/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("FAILED")))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());

        mockMvc.perform(get("/api/tests/{id}/results", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCategory", is("LAUNCH")));
    }
}
