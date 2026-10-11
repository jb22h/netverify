package com.netverify.controlplane.execution;

import java.util.UUID;

/**
 * Hands an accepted run to local execution or Redis transport.
 */
public interface RunDispatcher {

    /**
     * Dispatch a previously queued run.
     *
     * @throws DispatchRejectedException when the local executor rejects the task
     *         or Redis publish fails
     */
    void dispatch(UUID testId, UUID runId);
}
