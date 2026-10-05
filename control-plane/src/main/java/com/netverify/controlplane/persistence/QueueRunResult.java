package com.netverify.controlplane.persistence;

/** Outcome of accepting a new run into the lifecycle store. */
public enum QueueRunResult {
    /** A new QUEUED run was accepted. */
    QUEUED,
    /** An active (QUEUED/RUNNING) run already exists for the test. */
    CONFLICT
}
