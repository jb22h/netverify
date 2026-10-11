package com.netverify.controlplane.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.netverify.controlplane.config.NetVerifyProperties;
import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import com.netverify.controlplane.execution.LeaseHandle;
import com.netverify.controlplane.execution.RunExecutionService;
import com.netverify.controlplane.execution.RunLeaseHeartbeater;
import com.netverify.controlplane.persistence.TerminalUpdate;
import com.netverify.controlplane.persistence.TestEntity;
import com.netverify.controlplane.persistence.TestRepository;
import com.netverify.controlplane.persistence.worker.ClaimOutcome;
import com.netverify.controlplane.persistence.worker.ClaimResult;
import com.netverify.controlplane.persistence.worker.ReclaimOutcome;
import com.netverify.controlplane.persistence.worker.ReclaimResult;
import com.netverify.controlplane.persistence.worker.WorkerRunLifecycle;
import com.netverify.controlplane.queue.QueueDelivery;
import com.netverify.controlplane.queue.RunQueue;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class WorkerMessageProcessorTest {

    private static final String WORKER_ID = "worker-1";

    private WorkerRunLifecycle lifecycle;
    private TestRepository testRepository;
    private RunExecutionService runExecutionService;
    private RunLeaseHeartbeater heartbeater;
    private RunQueue runQueue;
    private WorkerMessageProcessor processor;
    private QueueDelivery delivery;
    private TestEntity definition;

    @BeforeEach
    void setUp() {
        lifecycle = mock(WorkerRunLifecycle.class);
        testRepository = mock(TestRepository.class);
        runExecutionService = mock(RunExecutionService.class);
        heartbeater = mock(RunLeaseHeartbeater.class);
        runQueue = mock(RunQueue.class);

        NetVerifyProperties properties = new NetVerifyProperties();
        properties.setWorkerId(WORKER_ID);
        processor = new WorkerMessageProcessor(
                lifecycle,
                testRepository,
                runExecutionService,
                heartbeater,
                runQueue,
                properties
        );

        UUID runId = UUID.randomUUID();
        UUID testId = UUID.randomUUID();
        delivery = new QueueDelivery("1-0", runId, testId);
        definition = new TestEntity(testId, "t", "scenario-a", 2, 3, Instant.parse("2024-01-01T00:00:00Z"));
    }

    @Test
    void claimedSuccessExecutesCompletesAndAcks() {
        when(lifecycle.claimForExecution(
                delivery.runId(), delivery.testId(), WORKER_ID, WorkerMessageProcessor.CLAIM_LEASE
        )).thenReturn(ClaimResult.of(ClaimOutcome.CLAIMED));
        LeaseHandle handle = mockHandle(false);
        when(heartbeater.start(delivery.runId(), WORKER_ID)).thenReturn(handle);
        when(testRepository.findById(delivery.testId())).thenReturn(Optional.of(definition));
        TerminalUpdate update = TerminalUpdate.passed(0, null, "", "");
        when(runExecutionService.execute("scenario-a", 2, 3)).thenReturn(update);
        when(lifecycle.complete(eq(delivery.runId()), eq(WORKER_ID), eq(update), any(Instant.class)))
                .thenReturn(true);
        when(runQueue.acknowledge("1-0")).thenReturn(true);

        processor.process(delivery);

        verify(runExecutionService, times(1)).execute("scenario-a", 2, 3);
        verify(lifecycle).complete(eq(delivery.runId()), eq(WORKER_ID), eq(update), any(Instant.class));
        verify(runQueue).acknowledge("1-0");
        verify(handle).close();
    }

    @Test
    void claimedOwnershipLostSkipsCompleteAndAck() {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.CLAIMED));
        LeaseHandle handle = mockHandle(true);
        when(heartbeater.start(delivery.runId(), WORKER_ID)).thenReturn(handle);
        when(testRepository.findById(delivery.testId())).thenReturn(Optional.of(definition));
        when(runExecutionService.execute(any(), anyInt(), any()))
                .thenReturn(TerminalUpdate.passed(0, null, "", ""));

        processor.process(delivery);

        verify(lifecycle, never()).complete(any(), any(), any(), any());
        verify(runQueue, never()).acknowledge(any());
        verify(handle).close();
    }

    @Test
    void claimedCompleteFalseDoesNotAck() {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.CLAIMED));
        LeaseHandle handle = mockHandle(false);
        when(heartbeater.start(delivery.runId(), WORKER_ID)).thenReturn(handle);
        when(testRepository.findById(delivery.testId())).thenReturn(Optional.of(definition));
        when(runExecutionService.execute(any(), anyInt(), any()))
                .thenReturn(TerminalUpdate.passed(0, null, "", ""));
        when(lifecycle.complete(any(), any(), any(), any())).thenReturn(false);

        processor.process(delivery);

        verify(runQueue, never()).acknowledge(any());
        verify(handle).close();
    }

    @Test
    void claimedMissingDefinitionCompletesFailedLaunchAndAcks() {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.CLAIMED));
        LeaseHandle handle = mockHandle(false);
        when(heartbeater.start(delivery.runId(), WORKER_ID)).thenReturn(handle);
        when(testRepository.findById(delivery.testId())).thenReturn(Optional.empty());
        when(lifecycle.complete(any(), any(), any(), any())).thenReturn(true);
        when(runQueue.acknowledge("1-0")).thenReturn(true);

        processor.process(delivery);

        verifyNoInteractions(runExecutionService);
        ArgumentCaptor<TerminalUpdate> updateCaptor = ArgumentCaptor.forClass(TerminalUpdate.class);
        verify(lifecycle).complete(
                eq(delivery.runId()),
                eq(WORKER_ID),
                updateCaptor.capture(),
                any(Instant.class)
        );
        TerminalUpdate update = updateCaptor.getValue();
        assertEquals(TestStatus.FAILED, update.status());
        assertEquals(FailureCategory.LAUNCH, update.failureCategory());
        assertEquals("test definition missing during worker run", update.stderr());
        verify(runQueue).acknowledge("1-0");
        verify(handle).close();
    }

    @Test
    void alreadyTerminalAcksWithoutPython() {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.ALREADY_TERMINAL));
        when(runQueue.acknowledge("1-0")).thenReturn(true);

        processor.process(delivery);

        verifyNoInteractions(runExecutionService, heartbeater, testRepository);
        verify(runQueue).acknowledge("1-0");
    }

    @Test
    void notFoundAcksWithoutPython() {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.NOT_FOUND));
        when(runQueue.acknowledge("1-0")).thenReturn(true);

        processor.process(delivery);

        verifyNoInteractions(runExecutionService, heartbeater);
        verify(runQueue).acknowledge("1-0");
    }

    @Test
    void testIdMismatchAcksWithoutPython() {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.TEST_ID_MISMATCH));
        when(runQueue.acknowledge("1-0")).thenReturn(true);

        processor.process(delivery);

        verifyNoInteractions(runExecutionService, heartbeater);
        verify(runQueue).acknowledge("1-0");
    }

    @Test
    void runningWithValidLeaseNoPythonNoAck() {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.RUNNING_WITH_VALID_LEASE));

        processor.process(delivery);

        verifyNoInteractions(runExecutionService, heartbeater);
        verify(runQueue, never()).acknowledge(any());
        verify(lifecycle, never()).reclaimExpiredRunning(any());
    }

    @Test
    void runningWithoutLeaseNoPythonNoAck() {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.RUNNING_WITHOUT_LEASE));

        processor.process(delivery);

        verifyNoInteractions(runExecutionService, heartbeater);
        verify(runQueue, never()).acknowledge(any());
    }

    @ParameterizedTest
    @EnumSource(ReclaimOutcome.class)
    void expiredRunningHandlesEveryReclaimOutcome(ReclaimOutcome reclaimOutcome) {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.EXPIRED_RUNNING_NEEDS_RECLAIM));
        when(lifecycle.reclaimExpiredRunning(delivery.runId()))
                .thenReturn(ReclaimResult.of(reclaimOutcome));
        when(runQueue.acknowledge("1-0")).thenReturn(true);

        processor.process(delivery);

        verifyNoInteractions(runExecutionService, heartbeater);
        verify(lifecycle).reclaimExpiredRunning(delivery.runId());
        switch (reclaimOutcome) {
            case RECLAIMED, NOT_FOUND, NOT_RUNNING -> verify(runQueue).acknowledge("1-0");
            case LEASE_STILL_VALID, NO_LEASE -> verify(runQueue, never()).acknowledge(any());
        }
    }

    @Test
    void acknowledgeFalseIsTolerated() {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.ALREADY_TERMINAL));
        when(runQueue.acknowledge("1-0")).thenReturn(false);

        processor.process(delivery);

        verify(runQueue).acknowledge("1-0");
    }

    @Test
    void acknowledgeRuntimeExceptionIsTolerated() {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.ALREADY_TERMINAL));
        doThrow(new IllegalStateException("redis down")).when(runQueue).acknowledge("1-0");

        processor.process(delivery);

        verify(runQueue).acknowledge("1-0");
        verify(lifecycle, never()).complete(any(), any(), any(), any());
    }

    @Test
    void heartbeatHandleAlwaysClosesEvenWhenExecuteThrows() {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.CLAIMED));
        LeaseHandle handle = mockHandle(false);
        when(heartbeater.start(delivery.runId(), WORKER_ID)).thenReturn(handle);
        when(testRepository.findById(delivery.testId())).thenReturn(Optional.of(definition));
        when(runExecutionService.execute(any(), anyInt(), any()))
                .thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> processor.process(delivery));
        verify(handle).close();
    }

    @Test
    void validLeaseNeverDuplicatesPython() {
        when(lifecycle.claimForExecution(any(), any(), any(), any()))
                .thenReturn(ClaimResult.of(ClaimOutcome.RUNNING_WITH_VALID_LEASE));

        processor.process(delivery);
        processor.process(delivery);

        verifyNoInteractions(runExecutionService);
        verify(runQueue, never()).acknowledge(any());
    }

    private static LeaseHandle mockHandle(boolean ownershipLost) {
        LeaseHandle handle = mock(LeaseHandle.class);
        when(handle.ownershipLost()).thenReturn(ownershipLost);
        return handle;
    }
}
