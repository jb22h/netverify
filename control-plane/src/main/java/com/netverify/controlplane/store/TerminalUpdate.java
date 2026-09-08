package com.netverify.controlplane.store;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;

/**
 * All terminal runtime fields applied together by
 * {@link InMemoryTestStore#completeRun}, so readers never observe a
 * half-updated terminal record.
 */
public record TerminalUpdate(
        TestStatus status,
        Integer exitCode,
        Object runnerResult,
        String stdout,
        String stderr,
        FailureCategory failureCategory
) {

    public TerminalUpdate {
        if (status != TestStatus.PASSED && status != TestStatus.FAILED) {
            throw new IllegalArgumentException("terminal status must be PASSED or FAILED: " + status);
        }
        if (status == TestStatus.FAILED && failureCategory == null) {
            throw new IllegalArgumentException("FAILED requires a failureCategory");
        }
    }

    public static TerminalUpdate passed(Integer exitCode, Object runnerResult, String stdout, String stderr) {
        return new TerminalUpdate(TestStatus.PASSED, exitCode, runnerResult, stdout, stderr, null);
    }

    public static TerminalUpdate failed(
            FailureCategory category,
            Integer exitCode,
            Object runnerResult,
            String stdout,
            String stderr
    ) {
        return new TerminalUpdate(TestStatus.FAILED, exitCode, runnerResult, stdout, stderr, category);
    }
}
