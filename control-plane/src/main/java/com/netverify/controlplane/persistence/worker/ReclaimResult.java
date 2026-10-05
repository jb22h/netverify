package com.netverify.controlplane.persistence.worker;

public record ReclaimResult(ReclaimOutcome outcome) {

    public ReclaimResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
    }

    public static ReclaimResult of(ReclaimOutcome outcome) {
        return new ReclaimResult(outcome);
    }

    public boolean reclaimed() {
        return outcome == ReclaimOutcome.RECLAIMED;
    }
}
