package com.netverify.controlplane.execution;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.persistence.TerminalUpdate;
import com.netverify.controlplane.runner.PythonRunOutcome;
import com.netverify.controlplane.runner.PythonRunnerBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the Python bridge and maps outcomes to {@link TerminalUpdate}.
 * Does not touch lifecycle stores or leases.
 */
@Service
public class RunExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RunExecutionService.class);

    private final PythonRunnerBridge runnerBridge;

    public RunExecutionService(PythonRunnerBridge runnerBridge) {
        this.runnerBridge = runnerBridge;
    }

    public TerminalUpdate execute(String scenario, int count, Integer clients) {
        PythonRunOutcome outcome;
        try {
            outcome = runnerBridge.run(scenario, count, clients);
        } catch (RuntimeException ex) {
            log.warn("runner bridge failed", ex);
            outcome = PythonRunOutcome.preLaunchFailure(
                    FailureCategory.LAUNCH,
                    "unexpected runner error: " + ex
            );
        }
        return toTerminalUpdate(outcome);
    }

    static TerminalUpdate toTerminalUpdate(PythonRunOutcome outcome) {
        String stderr = outcome.stderr() == null ? "" : outcome.stderr();
        if (outcome.message() != null && !outcome.message().isBlank()) {
            stderr = stderr.isBlank() ? outcome.message() : stderr + System.lineSeparator()
                    + outcome.message();
        }
        if (outcome.terminalStatus() == TestStatus.PASSED) {
            return TerminalUpdate.passed(
                    outcome.exitCode(), outcome.runnerResult(), outcome.stdout(), stderr
            );
        }
        return TerminalUpdate.failed(
                outcome.failureCategory() == null ? FailureCategory.LAUNCH : outcome.failureCategory(),
                outcome.exitCode(),
                outcome.runnerResult(),
                outcome.stdout(),
                stderr
        );
    }
}
