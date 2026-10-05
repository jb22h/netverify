package com.netverify.controlplane.persistence.worker;

public enum ReclaimOutcome {
    RECLAIMED,
    NOT_FOUND,
    NOT_RUNNING,
    LEASE_STILL_VALID,
    NO_LEASE
}
