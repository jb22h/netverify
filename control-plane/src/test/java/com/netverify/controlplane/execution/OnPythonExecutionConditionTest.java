package com.netverify.controlplane.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Independent Python-execution condition checks — no {@code DistributedModeValidator}.
 */
class OnPythonExecutionConditionTest {

    @Test
    void defaultModeTrue() {
        new ApplicationContextRunner()
                .withUserConfiguration(PythonMarkerConfig.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("pythonMarker"));
                });
    }

    @Test
    void postgresOnlyTrue() {
        new ApplicationContextRunner()
                .withUserConfiguration(PythonMarkerConfig.class)
                .withPropertyValues("spring.profiles.active=postgres")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("pythonMarker"));
                });
    }

    @Test
    void redisOnlyTrue() {
        new ApplicationContextRunner()
                .withUserConfiguration(PythonMarkerConfig.class)
                .withPropertyValues("spring.profiles.active=redis")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("pythonMarker"));
                });
    }

    @Test
    void distributedApiFalse() {
        new ApplicationContextRunner()
                .withUserConfiguration(PythonMarkerConfig.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres,redis",
                        "netverify.role=api"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("pythonMarker"));
                });
    }

    @Test
    void distributedWorkerTrueIndependently() {
        new ApplicationContextRunner()
                .withUserConfiguration(PythonMarkerConfig.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres,redis",
                        "netverify.role=worker"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("pythonMarker"));
                });
    }

    @Configuration
    static class PythonMarkerConfig {
        @Bean
        @ConditionalOnPythonExecution
        String pythonMarker() {
            return "python";
        }
    }
}
