package com.netverify.controlplane.execution;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True for local/single-process modes and for the future worker role.
 * False for distributed API ({@code postgres}+{@code redis}+{@code role=api}).
 */
public final class OnPythonExecutionCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        if (!OnLocalRunDispatcherCondition.distributedProfilesActive(environment)) {
            return true;
        }
        String role = environment.getProperty("netverify.role", "");
        role = role == null ? "" : role.trim();
        return "worker".equals(role);
    }
}
