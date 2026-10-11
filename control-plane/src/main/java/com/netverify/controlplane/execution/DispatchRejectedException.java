package com.netverify.controlplane.execution;

/**
 * Raised when a run cannot be handed off after durable QUEUED (local executor
 * saturation or Redis publish failure). Mapped to HTTP 503.
 */
public class DispatchRejectedException extends RuntimeException {

    public DispatchRejectedException(String message) {
        super(message);
    }

    public DispatchRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
