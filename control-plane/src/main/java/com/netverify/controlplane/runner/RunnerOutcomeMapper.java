package com.netverify.controlplane.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.execution.ConditionalOnLocalRunDispatcher;
import org.springframework.stereotype.Component;

/**
 * Maps a finished Python runner process to a terminal outcome.
 *
 * <p>Runner exit semantics (automation/runner.py): 0 verification PASS,
 * 1 verification FAIL, 2 setup/config error. Java never re-runs the Python
 * assertions; it only classifies and surfaces them.
 */
@Component
@ConditionalOnLocalRunDispatcher
public class RunnerOutcomeMapper {

    static final int EXIT_PASS = 0;
    static final int EXIT_VERIFICATION_FAIL = 1;
    static final int EXIT_SETUP_ERROR = 2;

    private final ObjectMapper objectMapper;

    public RunnerOutcomeMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PythonRunOutcome map(int exitCode, String stdout, String stderr) {
        RunnerResultDto parsed = null;
        String parseError = null;
        try {
            parsed = parse(stdout);
        } catch (JsonProcessingException ex) {
            parseError = "runner stdout was not a single JSON document: " + ex.getOriginalMessage();
        }

        return switch (exitCode) {
            case EXIT_PASS -> mapPassExit(exitCode, parsed, parseError, stdout, stderr);
            case EXIT_VERIFICATION_FAIL -> parsed == null
                    ? PythonRunOutcome.failed(
                            FailureCategory.PARSE, exitCode, null, stdout, stderr, parseError)
                    : PythonRunOutcome.failed(
                            FailureCategory.VERIFICATION, exitCode, parsed, stdout, stderr,
                            "runner reported verification failure");
            // Exit 2 may legitimately carry no JSON (argument validation writes stderr only).
            case EXIT_SETUP_ERROR -> PythonRunOutcome.failed(
                    FailureCategory.SETUP, exitCode, parsed, stdout, stderr,
                    "runner reported a setup/config error");
            default -> PythonRunOutcome.failed(
                    FailureCategory.LAUNCH, exitCode, parsed, stdout, stderr,
                    "runner exited with unexpected code " + exitCode);
        };
    }

    private static PythonRunOutcome mapPassExit(
            int exitCode,
            RunnerResultDto parsed,
            String parseError,
            String stdout,
            String stderr
    ) {
        if (parsed == null) {
            return PythonRunOutcome.failed(
                    FailureCategory.PARSE, exitCode, null, stdout, stderr, parseError);
        }
        if (!parsed.passed()) {
            // Contract violation: exit 0 means PASS, so passed=false is unusable output.
            return PythonRunOutcome.failed(
                    FailureCategory.PARSE, exitCode, parsed, stdout, stderr,
                    "runner exited 0 but reported passed=false");
        }
        return PythonRunOutcome.passed(exitCode, parsed, stdout, stderr);
    }

    private RunnerResultDto parse(String stdout) throws JsonProcessingException {
        if (stdout == null || stdout.isBlank()) {
            throw new EmptyRunnerOutputException();
        }
        RunnerResultDto dto = objectMapper.readValue(stdout.trim(), RunnerResultDto.class);
        if (dto == null) {
            throw new EmptyRunnerOutputException();
        }
        return dto;
    }

    /** Signals absent runner JSON using the same handling path as malformed JSON. */
    private static final class EmptyRunnerOutputException extends JsonProcessingException {

        private EmptyRunnerOutputException() {
            super("runner produced no JSON on stdout");
        }
    }
}
