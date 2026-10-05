package com.netverify.controlplane.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.persistence.TerminalUpdate;
import com.netverify.controlplane.runner.PythonRunOutcome;
import com.netverify.controlplane.runner.PythonRunnerBridge;
import com.netverify.controlplane.runner.RunnerResultDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunExecutionServiceTest {

    private final PythonRunnerBridge bridge = mock(PythonRunnerBridge.class);
    private final RunExecutionService service = new RunExecutionService(bridge);

    @Test
    void mapsPassedOutcome() {
        when(bridge.run(any(), anyInt(), any())).thenReturn(
                PythonRunOutcome.passed(
                        0,
                        new RunnerResultDto("basic_ping_pong", true, List.of(), 0, 0.01, Map.of()),
                        "{}",
                        ""
                )
        );

        TerminalUpdate update = service.execute("basic_ping_pong", 3, null);

        assertEquals(TestStatus.PASSED, update.status());
        assertEquals(0, update.exitCode());
    }

    @Test
    void mapsFailedCategoryFromOutcome() {
        when(bridge.run(any(), anyInt(), any())).thenReturn(
                PythonRunOutcome.failed(
                        FailureCategory.VERIFICATION, 1, null, "{}", "", "bad"
                )
        );

        TerminalUpdate update = service.execute("basic_ping_pong", 3, null);

        assertEquals(TestStatus.FAILED, update.status());
        assertEquals(FailureCategory.VERIFICATION, update.failureCategory());
        assertTrue(update.stderr().contains("bad"));
    }

    @Test
    void bridgeRuntimeExceptionBecomesLaunchFailure() {
        when(bridge.run(any(), anyInt(), any())).thenThrow(new RuntimeException("boom"));

        TerminalUpdate update = service.execute("basic_ping_pong", 3, null);

        assertEquals(TestStatus.FAILED, update.status());
        assertEquals(FailureCategory.LAUNCH, update.failureCategory());
        assertTrue(update.stderr().contains("unexpected runner error"));
        assertTrue(update.stderr().contains("boom"));
    }
}
