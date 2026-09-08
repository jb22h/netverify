package com.netverify.controlplane.api.dto;

import com.netverify.controlplane.domain.TestStatus;
import java.time.Instant;
import java.util.UUID;

/** Acknowledgement for an accepted (queued) run request. */
public record RunAcceptedResponse(UUID id, TestStatus status, Instant queuedAt) {

    public static RunAcceptedResponse queued(UUID id, Instant queuedAt) {
        return new RunAcceptedResponse(id, TestStatus.QUEUED, queuedAt);
    }
}
