package com.netverify.controlplane.execution;

/**
 * Heartbeat scope for a single run. Closing cancels only this run's renewals.
 */
public interface LeaseHandle extends AutoCloseable {

    boolean ownershipLost();

    @Override
    void close();
}
