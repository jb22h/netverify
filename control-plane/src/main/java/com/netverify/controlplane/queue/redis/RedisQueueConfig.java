package com.netverify.controlplane.queue.redis;

import com.netverify.controlplane.config.QueueProperties;
import com.netverify.controlplane.config.RedisProperties;
import com.netverify.controlplane.queue.RunQueue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/**
 * Explicit Redis Streams wiring for the {@code redis} profile only.
 * Default and postgres-only startups never create these beans.
 */
@Configuration
@Profile("redis")
public class RedisQueueConfig {

    @Bean(destroyMethod = "destroy")
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties properties) {
        requireConfigured(properties);
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration();
        standalone.setHostName(properties.getHost().trim());
        standalone.setPort(properties.getPort());
        return new LettuceConnectionFactory(standalone);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }

    @Bean
    public RunQueue runQueue(StringRedisTemplate stringRedisTemplate, QueueProperties queueProperties) {
        return new RedisRunQueue(stringRedisTemplate, queueProperties);
    }

    private static void requireConfigured(RedisProperties properties) {
        if (!StringUtils.hasText(properties.getHost())) {
            throw new IllegalStateException(
                    "redis profile requires NETVERIFY_REDIS_HOST (netverify.redis.host)"
            );
        }
        if (properties.getPort() <= 0 || properties.getPort() > 65535) {
            throw new IllegalStateException(
                    "redis profile requires a valid NETVERIFY_REDIS_PORT (netverify.redis.port)"
            );
        }
    }
}
