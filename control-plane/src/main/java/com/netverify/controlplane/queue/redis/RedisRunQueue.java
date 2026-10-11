package com.netverify.controlplane.queue.redis;

import com.netverify.controlplane.config.QueueProperties;
import com.netverify.controlplane.queue.AutoClaimBatch;
import com.netverify.controlplane.queue.QueueDelivery;
import com.netverify.controlplane.queue.RunQueue;
import com.netverify.controlplane.queue.RunQueueMessage;
import com.netverify.controlplane.queue.RunQueueMessageParser;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.lettuce.core.StreamMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.util.StringUtils;

/**
 * Redis Streams implementation of {@link RunQueue}.
 * Profile-gated; no worker processing loop or leases.
 */
public class RedisRunQueue implements RunQueue {

    private final StringRedisTemplate redisTemplate;
    private final String stream;
    private final String group;

    public RedisRunQueue(StringRedisTemplate redisTemplate, QueueProperties queueProperties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        Objects.requireNonNull(queueProperties, "queueProperties");
        queueProperties.validate();
        this.stream = requireText(queueProperties.getStream(), "netverify.queue.stream");
        this.group = requireText(queueProperties.getGroup(), "netverify.queue.group");
    }

    @Override
    public void ensureConsumerGroup() {
        try {
            redisTemplate.execute((RedisCallback<String>) connection -> {
                connection.streamCommands().xGroupCreate(
                        streamBytes(),
                        group,
                        ReadOffset.from("0-0"),
                        true
                );
                return "OK";
            });
        } catch (Exception ex) {
            if (!isBusyGroup(ex)) {
                throw ex;
            }
        }
    }

    @Override
    public String publish(UUID runId, UUID testId) {
        RunQueueMessage message = new RunQueueMessage(runId, testId);
        Map<String, String> fields = RunQueueMessageParser.toStreamFields(message);
        RecordId recordId = streamOps().add(stream, fields);
        if (recordId == null) {
            throw new IllegalStateException("XADD returned null record id for stream " + stream);
        }
        return recordId.getValue();
    }

    @Override
    public List<QueueDelivery> read(String consumerName, int count) {
        if (!StringUtils.hasText(consumerName)) {
            throw new IllegalArgumentException("consumerName must not be blank");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1");
        }

        List<MapRecord<String, Object, Object>> records = streamOps().read(
                Consumer.from(group, consumerName.trim()),
                StreamReadOptions.empty().count(count),
                StreamOffset.create(stream, ReadOffset.lastConsumed())
        );

        return toDeliveries(records);
    }

    @Override
    public List<QueueDelivery> read(String consumerName, int count, Duration block) {
        if (!StringUtils.hasText(consumerName)) {
            throw new IllegalArgumentException("consumerName must not be blank");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1");
        }
        if (block == null) {
            throw new IllegalArgumentException("block must not be null");
        }
        if (block.isZero() || block.isNegative()) {
            throw new IllegalArgumentException("block must be positive");
        }

        List<MapRecord<String, Object, Object>> records = streamOps().read(
                Consumer.from(group, consumerName.trim()),
                StreamReadOptions.empty().block(block).count(count),
                StreamOffset.create(stream, ReadOffset.lastConsumed())
        );

        return toDeliveries(records);
    }

    @Override
    public AutoClaimBatch claimStale(String consumerName, Duration minIdle, String startId, int count) {
        if (!StringUtils.hasText(consumerName)) {
            throw new IllegalArgumentException("consumerName must not be blank");
        }
        if (minIdle == null) {
            throw new IllegalArgumentException("minIdle must not be null");
        }
        if (minIdle.isZero() || minIdle.isNegative()) {
            throw new IllegalArgumentException("minIdle must be positive");
        }
        if (!StringUtils.hasText(startId)) {
            throw new IllegalArgumentException("startId must not be blank");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1");
        }

        String trimmedConsumer = consumerName.trim();
        String trimmedStart = startId.trim();

        AutoClaimBatch batch = redisTemplate.execute((RedisCallback<AutoClaimBatch>) connection -> {
            Object nativeConnection = connection.getNativeConnection();
            if (!(nativeConnection instanceof RedisClusterAsyncCommands<?, ?>)) {
                throw new IllegalStateException(
                        "Expected Lettuce RedisClusterAsyncCommands for XAUTOCLAIM, got "
                                + (nativeConnection == null
                                ? "null"
                                : nativeConnection.getClass().getName())
                );
            }
            @SuppressWarnings("unchecked")
            RedisClusterAsyncCommands<byte[], byte[]> commands =
                    (RedisClusterAsyncCommands<byte[], byte[]>) nativeConnection;

            XAutoClaimArgs<byte[]> args = XAutoClaimArgs.Builder
                    .xautoclaim(
                            io.lettuce.core.Consumer.from(groupBytes(), utf8(trimmedConsumer)),
                            minIdle,
                            trimmedStart
                    )
                    .count(count);

            RedisFuture<ClaimedMessages<byte[], byte[]>> future = commands.xautoclaim(streamBytes(), args);
            ClaimedMessages<byte[], byte[]> claimed;
            try {
                claimed = future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("XAUTOCLAIM interrupted", ex);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("XAUTOCLAIM failed", cause);
            }

            if (claimed == null || !StringUtils.hasText(claimed.getId())) {
                throw new IllegalStateException("XAUTOCLAIM returned no continuation cursor");
            }

            List<StreamMessage<byte[], byte[]>> messages = claimed.getMessages();
            if (messages == null || messages.isEmpty()) {
                return new AutoClaimBatch(claimed.getId(), List.of());
            }

            List<QueueDelivery> deliveries = new ArrayList<>();
            for (StreamMessage<byte[], byte[]> message : messages) {
                if (message == null || !StringUtils.hasText(message.getId())) {
                    continue;
                }
                Map<byte[], byte[]> body = message.getBody();
                // Deleted stream entries are returned as ids with null/empty bodies — skip.
                if (body == null || body.isEmpty()) {
                    continue;
                }
                deliveries.add(RunQueueMessageParser.parse(message.getId(), toObjectMap(body)));
            }
            return new AutoClaimBatch(claimed.getId(), deliveries);
        });

        if (batch == null) {
            throw new IllegalStateException("XAUTOCLAIM returned null batch");
        }
        return batch;
    }

    @Override
    public boolean acknowledge(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        Long acknowledged = streamOps().acknowledge(stream, group, messageId.trim());
        return acknowledged != null && acknowledged == 1L;
    }

    private List<QueueDelivery> toDeliveries(List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        List<QueueDelivery> deliveries = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            String messageId = record.getId().getValue();
            Map<Object, Object> body = record.getValue() == null
                    ? Collections.emptyMap()
                    : Map.copyOf(record.getValue());
            deliveries.add(RunQueueMessageParser.parse(messageId, body));
        }
        return List.copyOf(deliveries);
    }

    private static Map<Object, Object> toObjectMap(Map<byte[], byte[]> body) {
        Map<Object, Object> fields = new HashMap<>(body.size());
        for (Map.Entry<byte[], byte[]> entry : body.entrySet()) {
            String key = entry.getKey() == null ? null : new String(entry.getKey(), StandardCharsets.UTF_8);
            String value = entry.getValue() == null ? null : new String(entry.getValue(), StandardCharsets.UTF_8);
            fields.put(key, value);
        }
        return fields;
    }

    private StreamOperations<String, Object, Object> streamOps() {
        return redisTemplate.opsForStream();
    }

    private byte[] streamBytes() {
        return utf8(stream);
    }

    private byte[] groupBytes() {
        return utf8(group);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static boolean isBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
