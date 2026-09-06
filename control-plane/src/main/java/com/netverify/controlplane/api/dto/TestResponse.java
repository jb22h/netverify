package com.netverify.controlplane.api.dto;

import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import java.time.Instant;
import java.util.UUID;

public class TestResponse {

    private UUID id;
    private String name;
    private String scenario;
    private int count;
    private Integer clients;
    private TestStatus status;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    public static TestResponse from(TestRecord record) {
        TestResponse response = new TestResponse();
        response.id = record.getId();
        response.name = record.getName();
        response.scenario = record.getScenario();
        response.count = record.getCount();
        response.clients = record.getClients();
        response.status = record.getStatus();
        response.createdAt = record.getCreatedAt();
        response.startedAt = record.getStartedAt();
        response.finishedAt = record.getFinishedAt();
        return response;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getScenario() {
        return scenario;
    }

    public int getCount() {
        return count;
    }

    public Integer getClients() {
        return clients;
    }

    public TestStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
