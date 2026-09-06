package com.netverify.controlplane;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class TestApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createValidTestReturns201Created() throws Exception {
        mockMvc.perform(
                        post("/api/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "smoke",
                                          "scenario": "basic_ping_pong",
                                          "count": 100
                                        }
                                        """
                                )
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("smoke")))
                .andExpect(jsonPath("$.scenario", is("basic_ping_pong")))
                .andExpect(jsonPath("$.count", is(100)))
                .andExpect(jsonPath("$.clients", nullValue()))
                .andExpect(jsonPath("$.status", is("CREATED")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void unknownScenarioReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "bad",
                                          "scenario": "not_a_real_test",
                                          "count": 10
                                        }
                                        """
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void latencyInjectionReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "latency",
                                          "scenario": "latency_injection",
                                          "count": 3
                                        }
                                        """
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidCountReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "bad-count",
                                          "scenario": "basic_ping_pong",
                                          "count": 0
                                        }
                                        """
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentLoadClientsOverMaxReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "too-many",
                                          "scenario": "concurrent_load",
                                          "count": 10,
                                          "clients": 1001
                                        }
                                        """
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void getKnownIdReturns200() throws Exception {
        MvcResult created = mockMvc.perform(
                        post("/api/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "get-me",
                                          "scenario": "connect_fail_no_server",
                                          "count": 1
                                        }
                                        """
                                )
                )
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id")
                .asText();

        mockMvc.perform(get("/api/tests/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id)))
                .andExpect(jsonPath("$.status", is("CREATED")));
    }

    @Test
    void getUnknownIdReturns404() throws Exception {
        mockMvc.perform(get("/api/tests/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listContainsCreatedRecord() throws Exception {
        MvcResult created = mockMvc.perform(
                        post("/api/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "listed",
                                          "scenario": "basic_ping_pong",
                                          "count": 5
                                        }
                                        """
                                )
                )
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id")
                .asText();

        MvcResult listed = mockMvc.perform(get("/api/tests"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(listed.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode node : array) {
            if (id.equals(node.get("id").asText())
                    && "listed".equals(node.get("name").asText())) {
                found = true;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(found, "created record missing from list");
    }

    @Test
    void concurrentLoadDefaultsClientsToTen() throws Exception {
        mockMvc.perform(
                        post("/api/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "load",
                                          "scenario": "concurrent_load",
                                          "count": 100
                                        }
                                        """
                                )
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clients", is(10)));
    }

    @Test
    void nonConcurrentClientsNormalizedToNull() throws Exception {
        mockMvc.perform(
                        post("/api/tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "ignore-clients",
                                          "scenario": "basic_ping_pong",
                                          "count": 10,
                                          "clients": 50
                                        }
                                        """
                                )
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clients", nullValue()));
    }
}
