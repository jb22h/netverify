package com.netverify.controlplane.persistence.worker;

public record ClaimResult(ClaimOutcome outcome) {

    public ClaimResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
    }

    public static ClaimResult of(ClaimOutcome outcome) {
        return new ClaimResult(outcome);
    }

    public boolean claimed() {
        return outcome == ClaimOutcome.CLAIMED;
    }
}
