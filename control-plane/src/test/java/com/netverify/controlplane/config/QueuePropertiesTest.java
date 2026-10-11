package com.netverify.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class QueuePropertiesTest {

    @Test
    void defaultsMatchPhase10StreamGroupAndWorkerTuning() {
        QueueProperties properties = new QueueProperties();

        assertEquals("netverify:runs", properties.getStream());
        assertEquals("netverify-workers", properties.getGroup());
        assertEquals(2, properties.getReadBlockSeconds());
        assertEquals(1, properties.getReadBatchSize());
        assertEquals(150, properties.getClaimMinIdleSeconds());
        properties.validate();
    }

    @Test
    void readBlockSecondsZeroRejected() {
        QueueProperties properties = new QueueProperties();
        properties.setReadBlockSeconds(0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, properties::validate);
        assertEquals("netverify.queue.read-block-seconds must be >= 1", ex.getMessage());
    }

    @Test
    void claimMinIdleSecondsZeroRejected() {
        QueueProperties properties = new QueueProperties();
        properties.setClaimMinIdleSeconds(0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, properties::validate);
        assertEquals("netverify.queue.claim-min-idle-seconds must be >= 1", ex.getMessage());
    }

    @Test
    void readBatchSizeZeroRejected() {
        QueueProperties properties = new QueueProperties();
        properties.setReadBatchSize(0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, properties::validate);
        assertEquals("netverify.queue.read-batch-size must be == 1", ex.getMessage());
    }

    @Test
    void readBatchSizeTwoRejected() {
        QueueProperties properties = new QueueProperties();
        properties.setReadBatchSize(2);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, properties::validate);
        assertEquals("netverify.queue.read-batch-size must be == 1", ex.getMessage());
    }

    @Test
    void readBatchSizeOneAccepted() {
        QueueProperties properties = new QueueProperties();
        properties.setReadBatchSize(1);
        properties.validate();
        assertEquals(1, properties.getReadBatchSize());
    }
}
