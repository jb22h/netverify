package com.netverify.controlplane.queue.redis;

import com.netverify.controlplane.config.QueueProperties;
import com.netverify.controlplane.queue.MalformedQueueMessageException;
import com.netverify.controlplane.queue.QueueDelivery;
import com.netverify.controlplane.queue.RunQueue;
import com.netverify.controlplane.queue.RunQueueMessage;
import com.netverify.controlplane.queue.RunQueueMessageParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
 * Profile-gated; no worker processing, XAUTOCLAIM, or leases.
 */
public class RedisRunQueue implements RunQueue {

    private final StringRedisTemplate redisTemplate;
    private final String stream;
    private final String group;

    public RedisRunQueue(StringRedisTemplate redisTemplate, QueueProperties queueProperties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        Objects.requireNonNull(queueProperties, "queueProperties");
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

    @Override
    public boolean acknowledge(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        Long acknowledged = streamOps().acknowledge(stream, group, messageId.trim());
        return acknowledged != null && acknowledged == 1L;
    }

    private StreamOperations<String, Object, Object> streamOps() {
        return redisTemplate.opsForStream();
    }

    private byte[] streamBytes() {
        return stream.getBytes(StandardCharsets.UTF_8);
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
