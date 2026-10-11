package com.netverify.controlplane.worker;

import com.netverify.controlplane.config.ConditionalOnWorkerRole;
import com.netverify.controlplane.config.NetVerifyProperties;
import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.execution.LeaseHandle;
import com.netverify.controlplane.execution.RunExecutionService;
import com.netverify.controlplane.execution.RunLeaseHeartbeater;
import com.netverify.controlplane.persistence.TerminalUpdate;
import com.netverify.controlplane.persistence.TestEntity;
import com.netverify.controlplane.persistence.TestRepository;
import com.netverify.controlplane.persistence.worker.ClaimResult;
import com.netverify.controlplane.persistence.worker.ReclaimResult;
import com.netverify.controlplane.persistence.worker.WorkerRunLifecycle;
import com.netverify.controlplane.queue.QueueDelivery;
import com.netverify.controlplane.queue.RunQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Processes exactly one {@link QueueDelivery} against PostgreSQL lease fencing.
 * Redis ACK is attempted only after authoritative terminal / reclaim outcomes.
 */
@Component
@ConditionalOnWorkerRole
public class WorkerMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(WorkerMessageProcessor.class);
    static final Duration CLAIM_LEASE = Duration.ofSeconds(120);

    private final WorkerRunLifecycle lifecycle;
    private final TestRepository testRepository;
    private final RunExecutionService runExecutionService;
    private final RunLeaseHeartbeater heartbeater;
    private final RunQueue runQueue;
    private final String workerId;

    public WorkerMessageProcessor(
            WorkerRunLifecycle lifecycle,
            TestRepository testRepository,
            RunExecutionService runExecutionService,
            RunLeaseHeartbeater heartbeater,
            RunQueue runQueue,
            NetVerifyProperties properties
    ) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.testRepository = Objects.requireNonNull(testRepository, "testRepository");
        this.runExecutionService = Objects.requireNonNull(runExecutionService, "runExecutionService");
        this.heartbeater = Objects.requireNonNull(heartbeater, "heartbeater");
        this.runQueue = Objects.requireNonNull(runQueue, "runQueue");
        Objects.requireNonNull(properties, "properties");
        if (!StringUtils.hasText(properties.getWorkerId())) {
            throw new IllegalArgumentException("netverify.worker-id must not be blank");
        }
        this.workerId = properties.getWorkerId().trim();
    }

    public void process(QueueDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");

        ClaimResult claim = lifecycle.claimForExecution(
                delivery.runId(),
                delivery.testId(),
                workerId,
                CLAIM_LEASE
        );

        switch (claim.outcome()) {
            case CLAIMED -> handleClaimed(delivery);
            case ALREADY_TERMINAL -> acknowledge(delivery.messageId());
            case NOT_FOUND -> {
                log.warn(
                        "run not found for messageId={} runId={} — ACK without Python",
                        delivery.messageId(),
                        delivery.runId()
                );
                acknowledge(delivery.messageId());
            }
            case TEST_ID_MISMATCH -> {
                log.error(
                        "testId mismatch for messageId={} runId={} testId={} — ACK without Python",
                        delivery.messageId(),
                        delivery.runId(),
                        delivery.testId()
                );
                acknowledge(delivery.messageId());
            }
            case RUNNING_WITH_VALID_LEASE -> {
                // Another worker still owns a valid lease — leave pending.
            }
            case RUNNING_WITHOUT_LEASE -> log.warn(
                    "running without lease for messageId={} runId={} — never steal, no ACK",
                    delivery.messageId(),
                    delivery.runId()
            );
            case EXPIRED_RUNNING_NEEDS_RECLAIM -> handleExpiredRunning(delivery);
        }
    }

    private void handleClaimed(QueueDelivery delivery) {
        try (LeaseHandle handle = heartbeater.start(delivery.runId(), workerId)) {
            Optional<TestEntity> definition = testRepository.findById(delivery.testId());
            TerminalUpdate terminalUpdate;
            if (definition.isEmpty()) {
                terminalUpdate = TerminalUpdate.failed(
                        FailureCategory.LAUNCH,
                        null,
                        null,
                        "",
                        "test definition missing during worker run"
                );
            } else {
                TestEntity entity = definition.get();
                terminalUpdate = runExecutionService.execute(
                        entity.scenario(),
                        entity.count(),
                        entity.clients()
                );
            }

            if (handle.ownershipLost()) {
                return;
            }

            boolean completed = lifecycle.complete(
                    delivery.runId(),
                    workerId,
                    terminalUpdate,
                    Instant.now()
            );
            if (completed) {
                acknowledge(delivery.messageId());
            }
        }
    }

    private void handleExpiredRunning(QueueDelivery delivery) {
        ReclaimResult reclaim = lifecycle.reclaimExpiredRunning(delivery.runId());
        switch (reclaim.outcome()) {
            case RECLAIMED, NOT_FOUND, NOT_RUNNING -> acknowledge(delivery.messageId());
            case LEASE_STILL_VALID, NO_LEASE -> {
                // Leave pending for a later reclaim / owner.
            }
        }
    }

    void acknowledge(String messageId) {
        try {
            if (runQueue.acknowledge(messageId)) {
                return;
            }
            log.warn(
                    "acknowledge returned false for messageId={} — PostgreSQL remains authoritative",
                    messageId
            );
        } catch (RuntimeException ex) {
            log.warn(
                    "acknowledge failed for messageId={} — pending entry may recover later",
                    messageId,
                    ex
            );
        }
    }
}
