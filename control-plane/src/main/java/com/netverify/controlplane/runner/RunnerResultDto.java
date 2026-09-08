package com.netverify.controlplane.runner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Python runner JSON result, kept in the runner's own snake_case shape so the
 * HTTP contract mirrors {@code automation/runner.py --json} instead of inventing
 * a second metrics schema.
 *
 * <p>Metrics stay an untyped map because each scenario exposes a different set.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunnerResultDto(
        @JsonProperty("name") String name,
        @JsonProperty("passed") boolean passed,
        @JsonProperty("failures") List<String> failures,
        @JsonProperty("client_exit_code") Integer clientExitCode,
        @JsonProperty("duration_s") double durationSeconds,
        @JsonProperty("metrics") Map<String, Object> metrics
) {

    public List<String> failures() {
        return failures == null ? List.of() : failures;
    }

    public Map<String, Object> metrics() {
        return metrics == null ? Map.of() : metrics;
    }
}
