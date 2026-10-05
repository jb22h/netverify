package com.netverify.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PostgresRedisCombinationGuardTest {

    private static final AtomicInteger RUNNER_INVOCATIONS = new AtomicInteger();

    @BeforeEach
    void resetCounter() {
        RUNNER_INVOCATIONS.set(0);
    }

    @Test
    void combinedProfilesFailBeforeApplicationRunnersExecute() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        PostgresRedisCombinationGuard.class,
                        RecoveryProbeConfiguration.class
                )
                .withPropertyValues("spring.profiles.active=postgres,redis")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    String message = deepestMessage(context.getStartupFailure());
                    assertTrue(message.contains("M3"), message);
                    assertTrue(message.contains("not supported"), message);
                    assertEquals(0, RUNNER_INVOCATIONS.get());
                });
    }

    private static String deepestMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : "";
    }

    @Configuration
    static class RecoveryProbeConfiguration {

        @Bean
        ApplicationRunner recoveryProbe() {
            return (ApplicationArguments args) -> RUNNER_INVOCATIONS.incrementAndGet();
        }
    }
}
