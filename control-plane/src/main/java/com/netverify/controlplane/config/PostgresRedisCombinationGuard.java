package com.netverify.controlplane.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fail-fast guard: {@code postgres} + {@code redis} together are unsupported until
 * Phase 10 M3 distributed execution is implemented safely.
 */
@Configuration
@Profile("postgres & redis")
public class PostgresRedisCombinationGuard {

    public PostgresRedisCombinationGuard() {
        throw new IllegalStateException(
                "Profiles 'postgres' and 'redis' together are not supported until Phase 10 M3 "
                        + "distributed execution. Use postgres alone (local durable) or redis alone "
                        + "(queue foundation)."
        );
    }
}
