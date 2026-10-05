package com.netverify.controlplane.queue;

/**
 * Thrown when a stream entry cannot be parsed into a valid {@link QueueDelivery}.
 * Callers must not invent UUIDs; M1 surfaces bad payloads explicitly.
 */
public class MalformedQueueMessageException extends RuntimeException {

    private final String messageId;

    public MalformedQueueMessageException(String messageId, String detail) {
        super("Malformed queue message " + messageId + ": " + detail);
        this.messageId = messageId;
    }

    public MalformedQueueMessageException(String messageId, String detail, Throwable cause) {
        super("Malformed queue message " + messageId + ": " + detail, cause);
        this.messageId = messageId;
    }

    public String getMessageId() {
        return messageId;
    }
}
