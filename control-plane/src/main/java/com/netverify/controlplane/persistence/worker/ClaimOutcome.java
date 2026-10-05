package com.netverify.controlplane.persistence.worker;

public enum ClaimOutcome {
    CLAIMED,
    NOT_FOUND,
    TEST_ID_MISMATCH,
    ALREADY_TERMINAL,
    RUNNING_WITH_VALID_LEASE,
    EXPIRED_RUNNING_NEEDS_RECLAIM,
    RUNNING_WITHOUT_LEASE
}
