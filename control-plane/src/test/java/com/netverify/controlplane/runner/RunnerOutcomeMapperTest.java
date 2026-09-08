package com.netverify.controlplane.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import org.junit.jupiter.api.Test;

class RunnerOutcomeMapperTest {

    private static final String PASS_JSON = """
            {
              "name": "basic_ping_pong",
              "passed": true,
              "failures": [],
              "client_exit_code": 0,
              "duration_s": 0.056,
              "metrics": {"requested_count": 3, "client_rtt_avg_us": 120}
            }
            """;

    private static final String FAIL_JSON = """
            {
              "name": "basic_ping_pong",
              "passed": false,
              "failures": ["client messages_sent: expected 3, got 2"],
              "client_exit_code": 1,
              "duration_s": 0.02,
              "metrics": {"requested_count": 3}
            }
            """;

    private final RunnerOutcomeMapper mapper = new RunnerOutcomeMapper(new ObjectMapper());

    @Test
    void exitZeroWithPassingJsonIsPassed() {
        PythonRunOutcome outcome = mapper.map(0, PASS_JSON, "");

        assertEquals(TestStatus.PASSED, outcome.terminalStatus());
        assertNull(outcome.failureCategory());
        assertEquals(0, outcome.exitCode());
        assertNotNull(outcome.runnerResult());
        assertTrue(outcome.runnerResult().passed());
        assertEquals("basic_ping_pong", outcome.runnerResult().name());
        assertEquals(3, outcome.runnerResult().metrics().get("requested_count"));
        assertEquals(0.056, outcome.runnerResult().durationSeconds(), 1e-9);
    }

    @Test
    void exitOneWithValidJsonIsVerificationFailure() {
        PythonRunOutcome outcome = mapper.map(1, FAIL_JSON, "");

        assertEquals(TestStatus.FAILED, outcome.terminalStatus());
        assertEquals(FailureCategory.VERIFICATION, outcome.failureCategory());
        assertEquals(1, outcome.exitCode());
        assertEquals(1, outcome.runnerResult().failures().size());
    }

    @Test
    void exitTwoIsSetupFailureEvenWithoutJson() {
        PythonRunOutcome outcome = mapper.map(2, "", "error: --count must be >= 1");

        assertEquals(TestStatus.FAILED, outcome.terminalStatus());
        assertEquals(FailureCategory.SETUP, outcome.failureCategory());
        assertEquals(2, outcome.exitCode());
        assertNull(outcome.runnerResult());
        assertEquals("error: --count must be >= 1", outcome.stderr());
    }

    @Test
    void exitTwoWithJsonKeepsParsedResult() {
        PythonRunOutcome outcome = mapper.map(2, FAIL_JSON, "");

        assertEquals(FailureCategory.SETUP, outcome.failureCategory());
        assertNotNull(outcome.runnerResult());
    }

    @Test
    void exitZeroWithMalformedJsonIsParseFailure() {
        PythonRunOutcome outcome = mapper.map(0, "not json at all", "");

        assertEquals(TestStatus.FAILED, outcome.terminalStatus());
        assertEquals(FailureCategory.PARSE, outcome.failureCategory());
        assertNull(outcome.runnerResult());
    }

    @Test
    void exitZeroWithEmptyStdoutIsParseFailure() {
        PythonRunOutcome outcome = mapper.map(0, "   ", "");

        assertEquals(FailureCategory.PARSE, outcome.failureCategory());
    }

    @Test
    void exitZeroButPassedFalseIsParseFailure() {
        PythonRunOutcome outcome = mapper.map(0, FAIL_JSON, "");

        assertEquals(TestStatus.FAILED, outcome.terminalStatus());
        assertEquals(FailureCategory.PARSE, outcome.failureCategory());
        assertNotNull(outcome.runnerResult());
    }

    @Test
    void exitOneWithMalformedJsonIsParseFailure() {
        PythonRunOutcome outcome = mapper.map(1, "{", "");

        assertEquals(FailureCategory.PARSE, outcome.failureCategory());
    }

    @Test
    void unexpectedExitCodeIsLaunchFailureAndPreservesOutput() {
        PythonRunOutcome outcome = mapper.map(137, "partial", "killed");

        assertEquals(TestStatus.FAILED, outcome.terminalStatus());
        assertEquals(FailureCategory.LAUNCH, outcome.failureCategory());
        assertEquals(137, outcome.exitCode());
        assertEquals("partial", outcome.stdout());
        assertEquals("killed", outcome.stderr());
    }

    @Test
    void unknownJsonFieldsAreIgnored() {
        PythonRunOutcome outcome = mapper.map(
                0,
                """
                {"name":"t","passed":true,"metrics":{},"future_field":123}
                """,
                ""
        );

        assertEquals(TestStatus.PASSED, outcome.terminalStatus());
    }
}
