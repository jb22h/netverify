package com.netverify.controlplane.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class CreateTestRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    @NotBlank
    private String scenario;

    @NotNull
    @Min(1)
    @Max(1_000_000)
    private Integer count;

    /** Required/meaningful only for concurrent_load; ignored otherwise. */
    @Min(1)
    @Max(1000)
    private Integer clients;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public Integer getClients() {
        return clients;
    }

    public void setClients(Integer clients) {
        this.clients = clients;
    }
}
