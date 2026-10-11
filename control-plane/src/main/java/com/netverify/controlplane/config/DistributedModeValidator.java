package com.netverify.controlplane.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Fail-fast validation of {@code netverify.role} against active profiles.
 *
 * <p>Producer/gating slice: {@code role=worker} is rejected until the worker-consumer
 * slice lands. {@code postgres}+{@code redis} requires {@code role=api}.
 */
@Configuration
public class DistributedModeValidator {

    public DistributedModeValidator(Environment environment) {
        Set<String> profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        boolean postgres = profiles.contains("postgres");
        boolean redis = profiles.contains("redis");
        String role = environment.getProperty("netverify.role", "");
        role = role == null ? "" : role.trim();

        if (!StringUtils.hasText(role)) {
            if (postgres && redis) {
                throw new IllegalStateException(
                        "Profiles 'postgres' and 'redis' together require netverify.role=api. "
                                + "Worker role is not enabled until the M3 worker-consumer slice."
                );
            }
            return;
        }

        if ("worker".equals(role)) {
            throw new IllegalStateException(
                    "worker role is not enabled until the M3 worker runtime slice"
            );
        }

        if ("api".equals(role)) {
            if (!postgres || !redis) {
                throw new IllegalStateException(
                        "netverify.role=api requires both 'postgres' and 'redis' profiles"
                );
            }
            return;
        }

        throw new IllegalStateException("unknown netverify.role: " + role);
    }
}
