package com.netverify.controlplane.runner;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;

/**
 * Typed result of one Python runner execution. The bridge never mutates a
 * {@code TestRecord}; the caller applies this outcome through the store.
 */
public record PythonRunOutcome(
        TestStatus terminalStatus,
        FailureCategory failureCategory,
        Integer exitCode,
        RunnerResultDto runnerResult,
        String stdout,
        String stderr,
        String message
) {

    public static PythonRunOutcome passed(
            Integer exitCode,
            RunnerResultDto result,
            String stdout,
            String stderr
    ) {
        return new PythonRunOutcome(TestStatus.PASSED, null, exitCode, result, stdout, stderr, null);
    }

    public static PythonRunOutcome failed(
            FailureCategory category,
            Integer exitCode,
            RunnerResultDto result,
            String stdout,
            String stderr,
            String message
    ) {
        return new PythonRunOutcome(
                TestStatus.FAILED, category, exitCode, result, stdout, stderr, message
        );
    }

    /**
     * Failure before or during process launch: nothing was executed, so there is
     * no captured output. The reason travels in {@code message} only; the caller
     * appends it to stderr exactly once when persisting the outcome.
     */
    public static PythonRunOutcome preLaunchFailure(FailureCategory category, String message) {
        return new PythonRunOutcome(TestStatus.FAILED, category, null, null, "", "", message);
    }
}
