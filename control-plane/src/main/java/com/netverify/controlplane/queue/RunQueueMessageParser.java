package com.netverify.controlplane.queue;

import java.util.Map;
import java.util.UUID;

/**
 * Parses Redis Streams field maps into {@link QueueDelivery} values.
 * Invalid or missing UUID fields throw {@link MalformedQueueMessageException}.
 */
public final class RunQueueMessageParser {

    private RunQueueMessageParser() {
    }

    public static QueueDelivery parse(String messageId, Map<Object, Object> fields) {
        if (messageId == null || messageId.isBlank()) {
            throw new MalformedQueueMessageException(
                    String.valueOf(messageId),
                    "messageId must not be blank"
            );
        }
        if (fields == null) {
            throw new MalformedQueueMessageException(messageId, "fields map is null");
        }

        String runIdRaw = stringField(fields, RunQueueMessage.FIELD_RUN_ID);
        String testIdRaw = stringField(fields, RunQueueMessage.FIELD_TEST_ID);

        UUID runId = parseUuid(messageId, RunQueueMessage.FIELD_RUN_ID, runIdRaw);
        UUID testId = parseUuid(messageId, RunQueueMessage.FIELD_TEST_ID, testIdRaw);
        return new QueueDelivery(messageId, runId, testId);
    }

    public static Map<String, String> toStreamFields(RunQueueMessage message) {
        return Map.of(
                RunQueueMessage.FIELD_RUN_ID, message.runId().toString(),
                RunQueueMessage.FIELD_TEST_ID, message.testId().toString()
        );
    }

    private static String stringField(Map<Object, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private static UUID parseUuid(String messageId, String fieldName, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new MalformedQueueMessageException(
                    messageId,
                    "missing or blank field '" + fieldName + "'"
            );
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new MalformedQueueMessageException(
                    messageId,
                    "invalid UUID in field '" + fieldName + "': " + raw,
                    ex
            );
        }
    }
}
