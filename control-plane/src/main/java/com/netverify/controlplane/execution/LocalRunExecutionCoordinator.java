package com.netverify.controlplane.execution;

import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.persistence.RunLifecycleStore;
import com.netverify.controlplane.persistence.TerminalUpdate;
import com.netverify.controlplane.persistence.TestEntity;
import com.netverify.controlplane.persistence.TestRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local (in-process) run orchestration: QUEUED → RUNNING → terminal via
 * {@link RunLifecycleStore}. No Redis / lease ownership.
 */
@Component
@ConditionalOnLocalRunDispatcher
public class LocalRunExecutionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LocalRunExecutionCoordinator.class);

    private final RunLifecycleStore lifecycle;
    private final TestRepository testRepository;
    private final RunExecutionService runExecutionService;

    public LocalRunExecutionCoordinator(
            RunLifecycleStore lifecycle,
            TestRepository testRepository,
            RunExecutionService runExecutionService
    ) {
        this.lifecycle = lifecycle;
        this.testRepository = testRepository;
        this.runExecutionService = runExecutionService;
    }

    public void execute(UUID testId, UUID runId) {
        try {
            if (!lifecycle.tryStartRunning(testId, runId, Instant.now())) {
                // State changed underneath us; do not launch Python.
                return;
            }

            TestEntity entity = testRepository.findById(testId).orElse(null);
            if (entity == null) {
                log.error("definition missing for running test {}", testId);
                lifecycle.tryComplete(
                        testId,
                        runId,
                        TerminalUpdate.failed(
                                FailureCategory.LAUNCH, null, null, "",
                                "test definition missing during run"
                        ),
                        Instant.now()
                );
                return;
            }

            TerminalUpdate update = runExecutionService.execute(
                    entity.scenario(), entity.count(), entity.clients()
            );
            lifecycle.tryComplete(testId, runId, update, Instant.now());
        } catch (RuntimeException ex) {
            log.error("unexpected worker failure for test {}", testId, ex);
            lifecycle.tryComplete(
                    testId,
                    runId,
                    TerminalUpdate.failed(
                            FailureCategory.LAUNCH, null, null, "",
                            "unexpected worker error: " + ex
                    ),
                    Instant.now()
            );
        }
    }
}
