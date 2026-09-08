package com.netverify.controlplane.service;

/** Bounded runner executor refused the submission; the caller should retry later. */
public class ExecutorSaturatedException extends RuntimeException {

    public ExecutorSaturatedException(String message) {
        super(message);
    }
}
