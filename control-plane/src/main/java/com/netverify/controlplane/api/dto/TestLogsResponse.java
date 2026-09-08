package com.netverify.controlplane.api.dto;

import com.netverify.controlplane.domain.TestRecord;
import com.netverify.controlplane.domain.TestStatus;
import java.util.UUID;

/** Captured runner process output (already capped by the bridge). */
public record TestLogsResponse(UUID id, TestStatus status, String stdout, String stderr) {

    public static TestLogsResponse from(TestRecord record) {
        return new TestLogsResponse(
                record.getId(),
                record.getStatus(),
                record.getStdout() == null ? "" : record.getStdout(),
                record.getStderr() == null ? "" : record.getStderr()
        );
    }
}
