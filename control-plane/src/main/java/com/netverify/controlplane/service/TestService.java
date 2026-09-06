package com.netverify.controlplane.service;

import com.netverify.controlplane.api.dto.CreateTestRequest;
import com.netverify.controlplane.domain.AllowedScenarios;
import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.store.InMemoryTestStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TestService {

    private final InMemoryTestStore store;

    public TestService(InMemoryTestStore store) {
        this.store = store;
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

        TestRecord record = new TestRecord(
                UUID.randomUUID(),
                name,
                scenario,
                count,
                clients,
                Instant.now()
        );
        store.save(record);
        return record;
    }

    public List<TestRecord> listTests() {
        return store.list();
    }

    public TestRecord getTest(UUID id) {
        return store.get(id)
                .orElseThrow(() -> new NotFoundException("test not found: " + id));
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
}
