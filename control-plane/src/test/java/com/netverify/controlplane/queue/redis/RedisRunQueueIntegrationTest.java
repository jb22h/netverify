package com.netverify.controlplane.queue.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netverify.controlplane.queue.QueueDelivery;
import com.netverify.controlplane.queue.RunQueue;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Opt-in Redis Streams integration test. Skipped unless NETVERIFY_REDIS_HOST
 * is set (and optionally NETVERIFY_REDIS_PORT). Default {@code mvn test} never
 * requires Redis.
 *
 * <p>Uses a test-specific stream and group so repeated runs cannot collide with
 * production defaults ({@code netverify:runs} / {@code netverify-workers}).
 */
@SpringBootTest
@ActiveProfiles("redis")
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_REDIS_HOST", matches = ".+")
})
class RedisRunQueueIntegrationTest {

    private static final String ISOLATION_SUFFIX = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void isolateQueue(DynamicPropertyRegistry registry) {
        registry.add("netverify.queue.stream", () -> "netverify:runs:m1-it:" + ISOLATION_SUFFIX);
        registry.add("netverify.queue.group", () -> "netverify-workers-m1-it-" + ISOLATION_SUFFIX);
    }

    @Autowired
    private RunQueue runQueue;

    @Test
    void ensurePublishReadAcknowledgeRoundTrip() {
        UUID runId = UUID.randomUUID();
        UUID testId = UUID.randomUUID();
        String consumer = "m1-it-" + UUID.randomUUID();

        runQueue.ensureConsumerGroup();
        // Second call must be idempotent (BUSYGROUP is not an error).
        runQueue.ensureConsumerGroup();

        String messageId = runQueue.publish(runId, testId);
        assertNotNull(messageId);
        assertFalse(messageId.isBlank());

        List<QueueDelivery> deliveries = runQueue.read(consumer, 10);
        assertEquals(1, deliveries.size());

        QueueDelivery delivery = deliveries.getFirst();
        assertEquals(messageId, delivery.messageId());
        assertEquals(runId, delivery.runId());
        assertEquals(testId, delivery.testId());

        assertTrue(runQueue.acknowledge(delivery.messageId()));
        assertFalse(runQueue.acknowledge(delivery.messageId()));
    }
}
