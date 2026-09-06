package com.netverify.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * In-memory test record with immutable configuration and mutable runtime state.
 *
 * <p>Runtime fields require cross-thread visibility because HTTP threads read
 * while executor threads update them asynchronously.
 *
 * <p>Lifecycle state transitions must still be coordinated through
 * {@code InMemoryTestStore} atomic operations.
 *
 * <p>{@code volatile} provides visibility, not compound-operation atomicity.
 */
public final class TestRecord {

    private final UUID id;
    private final String name;
    private final String scenario;
    private final int count;
    private final Integer clients;
    private final Instant createdAt;

    private volatile TestStatus status;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile Integer exitCode;
    private volatile Object runnerResult;
    private volatile String stdout;
    private volatile String stderr;
    private volatile FailureCategory failureCategory;

    public TestRecord(
            UUID id,
            String name,
            String scenario,
            int count,
            Integer clients,
            Instant createdAt
    ) {
        this.id = id;
        this.name = name;
        this.scenario = scenario;
        this.count = count;
        this.clients = clients;
        this.createdAt = createdAt;
        this.status = TestStatus.CREATED;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TestStatus getStatus() {
        return status;
    }

    public void setStatus(TestStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public Object getRunnerResult() {
        return runnerResult;
    }

    public void setRunnerResult(Object runnerResult) {
        this.runnerResult = runnerResult;
    }

    public String getStdout() {
        return stdout;
    }

    public void setStdout(String stdout) {
        this.stdout = stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public void setStderr(String stderr) {
        this.stderr = stderr;
    }

    public FailureCategory getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(FailureCategory failureCategory) {
        this.failureCategory = failureCategory;
    }
}
