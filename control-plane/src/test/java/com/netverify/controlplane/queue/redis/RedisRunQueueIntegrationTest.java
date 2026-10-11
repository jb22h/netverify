package com.netverify.controlplane.queue.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netverify.controlplane.queue.AutoClaimBatch;
import com.netverify.controlplane.queue.QueueDelivery;
import com.netverify.controlplane.queue.RunQueue;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * Each test deletes that stream key so group/PEL state cannot leak across methods.
 */
@SpringBootTest
@ActiveProfiles("redis")
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "NETVERIFY_REDIS_HOST", matches = ".+")
})
class RedisRunQueueIntegrationTest {

    private static final String ISOLATION_SUFFIX = UUID.randomUUID().toString();
    private static final String STREAM = "netverify:runs:m3-it:" + ISOLATION_SUFFIX;
    private static final String GROUP = "netverify-workers-m3-it-" + ISOLATION_SUFFIX;

    @DynamicPropertySource
    static void isolateQueue(DynamicPropertyRegistry registry) {
        registry.add("netverify.queue.stream", () -> STREAM);
        registry.add("netverify.queue.group", () -> GROUP);
    }

    @Autowired
    private RunQueue runQueue;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void deleteTestStream() {
        // Removes stream entries and consumer-group / PEL state for this key only.
        stringRedisTemplate.delete(STREAM);
    }

    @Test
    void ensureConsumerGroupIsIdempotent() {
        runQueue.ensureConsumerGroup();
        runQueue.ensureConsumerGroup();
    }

    @Test
    void publishBeforeGroupCreationIsLaterReadableFromZeroOffset() {
        UUID runId = UUID.randomUUID();
        UUID testId = UUID.randomUUID();
        String consumer = "m3-it-before-group-" + UUID.randomUUID();

        // Fresh key: XADD before any consumer group exists.
        String messageId = runQueue.publish(runId, testId);
        assertNotNull(messageId);

        runQueue.ensureConsumerGroup();

        List<QueueDelivery> deliveries = runQueue.read(consumer, 1);
        assertEquals(1, deliveries.size());
        assertEquals(messageId, deliveries.getFirst().messageId());
        assertEquals(runId, deliveries.getFirst().runId());
        assertEquals(testId, deliveries.getFirst().testId());
        assertTrue(runQueue.acknowledge(messageId));
    }

    @Test
    void ensurePublishReadAcknowledgeRoundTrip() {
        UUID runId = UUID.randomUUID();
        UUID testId = UUID.randomUUID();
        String consumer = "m3-it-" + UUID.randomUUID();

        runQueue.ensureConsumerGroup();
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

    @Test
    void nonBlockingReadOnEmptyStreamReturnsPromptly() {
        runQueue.ensureConsumerGroup();
        String consumer = "m3-it-empty-" + UUID.randomUUID();

        long started = System.nanoTime();
        List<QueueDelivery> deliveries = runQueue.read(consumer, 1);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(deliveries.isEmpty());
        assertTrue(elapsedMs < 1_000, "non-blocking empty read took " + elapsedMs + "ms");
    }

    @Test
    void blockingReadDeliversPublishedMessage() {
        runQueue.ensureConsumerGroup();
        UUID runId = UUID.randomUUID();
        UUID testId = UUID.randomUUID();
        String consumer = "m3-it-block-" + UUID.randomUUID();

        String messageId = runQueue.publish(runId, testId);
        List<QueueDelivery> deliveries = runQueue.read(consumer, 1, Duration.ofSeconds(2));
        assertEquals(1, deliveries.size());
        assertEquals(messageId, deliveries.getFirst().messageId());
        assertTrue(runQueue.acknowledge(messageId));
    }

    @Test
    void blockingReadValidationRejectsZeroBlock() {
        runQueue.ensureConsumerGroup();
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> runQueue.read("c1", 1, Duration.ZERO)
        );
        assertEquals("block must be positive", ex.getMessage());
    }

    @Test
    void claimStaleAdvancesXAutoClaimCursorAcrossPendingEntries() throws InterruptedException {
        runQueue.ensureConsumerGroup();
        String owner = "m3-it-owner-" + UUID.randomUUID();
        String reclaim = "m3-it-reclaim-" + UUID.randomUUID();

        String messageIdA = runQueue.publish(UUID.randomUUID(), UUID.randomUUID());
        String messageIdB = runQueue.publish(UUID.randomUUID(), UUID.randomUUID());
        Set<String> published = Set.of(messageIdA, messageIdB);

        List<QueueDelivery> owned = runQueue.read(owner, 2);
        assertEquals(2, owned.size());
        Set<String> pendingIds = new HashSet<>();
        for (QueueDelivery delivery : owned) {
            pendingIds.add(delivery.messageId());
        }
        assertEquals(published, pendingIds);

        // Allow a tiny idle window so XAUTOCLAIM with a short min-idle can reclaim.
        Thread.sleep(50);
        Duration minIdle = Duration.ofMillis(10);

        AutoClaimBatch first = runQueue.claimStale(reclaim, minIdle, "0-0", 1);
        assertEquals(1, first.deliveries().size());
        assertNotEquals("0-0", first.nextStartId(), "first cursor must advance past the scan start");
        String claimedA = first.deliveries().getFirst().messageId();
        assertTrue(published.contains(claimedA));

        AutoClaimBatch second = runQueue.claimStale(reclaim, minIdle, first.nextStartId(), 1);
        assertEquals(1, second.deliveries().size());
        String claimedB = second.deliveries().getFirst().messageId();
        assertTrue(published.contains(claimedB));
        assertNotEquals(claimedA, claimedB, "cursor continuation must not redeliver the same id");

        Set<String> claimed = Set.of(claimedA, claimedB);
        assertEquals(published, claimed);

        String cursor = second.nextStartId();
        // Drain any trailing empty pages until Redis signals end-of-PEL with 0-0.
        for (int i = 0; i < 8 && !"0-0".equals(cursor); i++) {
            AutoClaimBatch page = runQueue.claimStale(reclaim, minIdle, cursor, 1);
            assertTrue(page.deliveries().isEmpty(), "unexpected extra delivery after both claims");
            cursor = page.nextStartId();
        }
        assertEquals("0-0", cursor, "XAUTOCLAIM scan should finish with continuation cursor 0-0");

        assertTrue(runQueue.acknowledge(claimedA));
        assertTrue(runQueue.acknowledge(claimedB));
    }
}
