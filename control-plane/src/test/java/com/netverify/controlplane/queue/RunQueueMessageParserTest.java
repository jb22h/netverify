package com.netverify.controlplane.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunQueueMessageParserTest {

    @Test
    void toStreamFieldsUsesExactRunIdAndTestIdKeys() {
        UUID runId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID testId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        Map<String, String> fields = RunQueueMessageParser.toStreamFields(
                new RunQueueMessage(runId, testId)
        );

        assertEquals(2, fields.size());
        assertEquals(runId.toString(), fields.get(RunQueueMessage.FIELD_RUN_ID));
        assertEquals(testId.toString(), fields.get(RunQueueMessage.FIELD_TEST_ID));
        assertEquals("runId", RunQueueMessage.FIELD_RUN_ID);
        assertEquals("testId", RunQueueMessage.FIELD_TEST_ID);
    }

    @Test
    void parseAcceptsValidUuidFields() {
        UUID runId = UUID.randomUUID();
        UUID testId = UUID.randomUUID();
        String messageId = "1700000000000-0";

        QueueDelivery delivery = RunQueueMessageParser.parse(
                messageId,
                Map.of(
                        RunQueueMessage.FIELD_RUN_ID, runId.toString(),
                        RunQueueMessage.FIELD_TEST_ID, testId.toString()
                )
        );

        assertEquals(messageId, delivery.messageId());
        assertEquals(runId, delivery.runId());
        assertEquals(testId, delivery.testId());
    }

    @Test
    void parseThrowsForMissingRunId() {
        MalformedQueueMessageException ex = assertThrows(
                MalformedQueueMessageException.class,
                () -> RunQueueMessageParser.parse(
                        "1-0",
                        Map.of(RunQueueMessage.FIELD_TEST_ID, UUID.randomUUID().toString())
                )
        );

        assertEquals("1-0", ex.getMessageId());
        assertTrue(ex.getMessage().contains("runId"));
    }

    @Test
    void parseThrowsForInvalidUuidWithoutFabricatingValues() {
        MalformedQueueMessageException ex = assertThrows(
                MalformedQueueMessageException.class,
                () -> RunQueueMessageParser.parse(
                        "2-0",
                        Map.of(
                                RunQueueMessage.FIELD_RUN_ID, "not-a-uuid",
                                RunQueueMessage.FIELD_TEST_ID, UUID.randomUUID().toString()
                        )
                )
        );

        assertEquals("2-0", ex.getMessageId());
        assertTrue(ex.getMessage().contains("invalid UUID"));
        assertTrue(ex.getMessage().contains("runId"));
    }

    @Test
    void parseThrowsForBlankTestId() {
        MalformedQueueMessageException ex = assertThrows(
                MalformedQueueMessageException.class,
                () -> RunQueueMessageParser.parse(
                        "3-0",
                        Map.of(
                                RunQueueMessage.FIELD_RUN_ID, UUID.randomUUID().toString(),
                                RunQueueMessage.FIELD_TEST_ID, "   "
                        )
                )
        );

        assertEquals("3-0", ex.getMessageId());
        assertTrue(ex.getMessage().contains("testId"));
    }

    @Test
    void runQueueMessageRejectsNullIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RunQueueMessage(null, UUID.randomUUID())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new RunQueueMessage(UUID.randomUUID(), null)
        );
    }
}
