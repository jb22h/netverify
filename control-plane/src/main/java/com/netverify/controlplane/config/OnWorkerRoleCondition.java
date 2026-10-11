package com.netverify.controlplane.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Bean-condition primitive: true only for postgres+redis with {@code netverify.role=worker}.
 *
 * <p>Does not authorize full application startup — {@link DistributedModeValidator}
 * still rejects worker role until the worker runtime slice.
 */
public final class OnWorkerRoleCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Set<String> profiles = new HashSet<>(Arrays.asList(context.getEnvironment().getActiveProfiles()));
        if (!profiles.contains("postgres") || !profiles.contains("redis")) {
            return false;
        }
        String role = context.getEnvironment().getProperty("netverify.role", "");
        role = role == null ? "" : role.trim();
        return "worker".equals(role);
    }
}
