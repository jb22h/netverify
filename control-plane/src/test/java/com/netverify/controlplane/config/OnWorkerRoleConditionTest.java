package com.netverify.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Independent bean-condition checks — does not register {@link DistributedModeValidator}.
 */
class OnWorkerRoleConditionTest {

    @Test
    void matchesOnlyPostgresRedisWorker() {
        new ApplicationContextRunner()
                .withUserConfiguration(WorkerMarkerConfig.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres,redis",
                        "netverify.role=worker"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("workerMarker"));
                });
    }

    @Test
    void falseForDistributedApi() {
        new ApplicationContextRunner()
                .withUserConfiguration(WorkerMarkerConfig.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres,redis",
                        "netverify.role=api"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("workerMarker"));
                });
    }

    @Test
    void falseWithoutBothProfiles() {
        new ApplicationContextRunner()
                .withUserConfiguration(WorkerMarkerConfig.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres",
                        "netverify.role=worker"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("workerMarker"));
                });
    }

    @Configuration
    static class WorkerMarkerConfig {
        @Bean
        @ConditionalOnWorkerRole
        String workerMarker() {
            return "worker";
        }
    }
}
