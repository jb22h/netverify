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
 * <p>{@code postgres}+{@code redis} requires {@code role=api} or {@code role=worker}.
 * Worker mode also requires a nonblank {@code netverify.worker-id} and
 * {@code spring.main.web-application-type=none}.
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
                        "Profiles 'postgres' and 'redis' together require netverify.role=api "
                                + "or netverify.role=worker"
                );
            }
            return;
        }

        if ("worker".equals(role)) {
            if (!postgres || !redis) {
                throw new IllegalStateException(
                        "netverify.role=worker requires both 'postgres' and 'redis' profiles"
                );
            }
            String workerId = environment.getProperty("netverify.worker-id", "");
            workerId = workerId == null ? "" : workerId.trim();
            if (!StringUtils.hasText(workerId)) {
                throw new IllegalStateException(
                        "netverify.role=worker requires netverify.worker-id"
                );
            }
            String webType = environment.getProperty("spring.main.web-application-type");
            if (webType == null || !"none".equalsIgnoreCase(webType.trim())) {
                throw new IllegalStateException(
                        "netverify.role=worker requires spring.main.web-application-type=none"
                );
            }
            return;
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
