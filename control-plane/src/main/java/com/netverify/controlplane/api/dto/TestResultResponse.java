package com.netverify.controlplane.api.dto;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.time.Instant;
import java.util.UUID;

/** Terminal outcome plus the parsed Python runner result when one was produced. */
public record TestResultResponse(
        UUID id,
        String name,
        String scenario,
        TestStatus status,
        Integer exitCode,
        FailureCategory failureCategory,
        Instant startedAt,
        Instant finishedAt,
        RunnerResultDto result
) {

    public static TestResultResponse from(TestRecord record) {
        return new TestResultResponse(
                record.getId(),
                record.getName(),
                record.getScenario(),
                record.getStatus(),
                record.getExitCode(),
                record.getFailureCategory(),
                record.getStartedAt(),
                record.getFinishedAt(),
                runnerResultOf(record)
        );
    }

    static RunnerResultDto runnerResultOf(TestRecord record) {
        return record.getRunnerResult() instanceof RunnerResultDto dto ? dto : null;
    }
}
