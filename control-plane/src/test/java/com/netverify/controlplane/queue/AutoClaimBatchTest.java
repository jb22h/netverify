package com.netverify.controlplane.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutoClaimBatchTest {

    @Test
    void deliveriesAreDefensivelyCopied() {
        QueueDelivery delivery = new QueueDelivery(
                "1-0",
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        List<QueueDelivery> mutable = new ArrayList<>();
        mutable.add(delivery);
        AutoClaimBatch batch = new AutoClaimBatch("0-0", mutable);
        mutable.clear();
        assertEquals(1, batch.deliveries().size());
        assertThrows(UnsupportedOperationException.class, () -> batch.deliveries().clear());
    }

    @Test
    void rejectsBlankNextStartId() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new AutoClaimBatch(" ", List.of())
        );
        assertTrue(ex.getMessage().contains("nextStartId"));
    }

    @Test
    void rejectsNullDeliveries() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new AutoClaimBatch("0-0", null)
        );
        assertTrue(ex.getMessage().contains("deliveries"));
    }
}
