package com.netverify.controlplane.api.dto;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.util.Map;
import java.util.UUID;

/**
 * Scenario metrics exactly as the Python runner reported them. Metrics stay an
 * untyped map because each scenario exposes a different set; a failed run
 * without a parsed result reports an empty map plus its failure category.
 */
public record TestMetricsResponse(
        UUID id,
        TestStatus status,
        FailureCategory failureCategory,
        Map<String, Object> metrics
) {

    public static TestMetricsResponse from(TestRecord record) {
        RunnerResultDto result = TestResultResponse.runnerResultOf(record);
        return new TestMetricsResponse(
                record.getId(),
                record.getStatus(),
                record.getFailureCategory(),
                result == null ? Map.of() : result.metrics()
        );
    }
}
