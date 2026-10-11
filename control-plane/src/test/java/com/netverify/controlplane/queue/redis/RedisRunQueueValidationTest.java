package com.netverify.controlplane.queue.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netverify.controlplane.config.QueueProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

class RedisRunQueueValidationTest {

    private RedisRunQueue queue;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.opsForStream()).thenReturn(mock(StreamOperations.class));
        queue = new RedisRunQueue(template, new QueueProperties());
    }

    @Test
    void nonBlockingReadRejectsBlankConsumer() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> queue.read("  ", 1)
        );
        assertEquals("consumerName must not be blank", ex.getMessage());
    }

    @Test
    void nonBlockingReadRejectsCountBelowOne() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> queue.read("c1", 0)
        );
        assertEquals("count must be >= 1", ex.getMessage());
    }

    @Test
    void blockingReadRejectsNullBlock() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> queue.read("c1", 1, null)
        );
        assertEquals("block must not be null", ex.getMessage());
    }

    @Test
    void blockingReadRejectsZeroBlock() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> queue.read("c1", 1, Duration.ZERO)
        );
        assertEquals("block must be positive", ex.getMessage());
    }

    @Test
    void blockingReadRejectsNegativeBlock() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> queue.read("c1", 1, Duration.ofMillis(-1))
        );
        assertEquals("block must be positive", ex.getMessage());
    }

    @Test
    void claimStaleRejectsBlankConsumer() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> queue.claimStale(" ", Duration.ofSeconds(1), "0-0", 1)
        );
        assertEquals("consumerName must not be blank", ex.getMessage());
    }

    @Test
    void claimStaleRejectsNullMinIdle() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> queue.claimStale("c1", null, "0-0", 1)
        );
        assertEquals("minIdle must not be null", ex.getMessage());
    }

    @Test
    void claimStaleRejectsZeroMinIdle() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> queue.claimStale("c1", Duration.ZERO, "0-0", 1)
        );
        assertEquals("minIdle must be positive", ex.getMessage());
    }

    @Test
    void claimStaleRejectsBlankStartId() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> queue.claimStale("c1", Duration.ofMillis(1), " ", 1)
        );
        assertEquals("startId must not be blank", ex.getMessage());
    }

    @Test
    void claimStaleRejectsCountBelowOne() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> queue.claimStale("c1", Duration.ofMillis(1), "0-0", 0)
        );
        assertEquals("count must be >= 1", ex.getMessage());
    }
}
