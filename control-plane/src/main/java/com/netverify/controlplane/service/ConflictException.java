package com.netverify.controlplane.service;

public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
