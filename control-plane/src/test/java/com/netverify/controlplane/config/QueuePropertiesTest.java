package com.netverify.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class QueuePropertiesTest {

    @Test
    void defaultsMatchPhase10StreamAndGroup() {
        QueueProperties properties = new QueueProperties();

        assertEquals("netverify:runs", properties.getStream());
        assertEquals("netverify-workers", properties.getGroup());
    }
}
