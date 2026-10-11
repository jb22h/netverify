package com.netverify.controlplane.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * True when {@code netverify.role} is blank (local modes) or {@code api}.
 */
public final class OnApiSurfaceCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String role = context.getEnvironment().getProperty("netverify.role", "");
        if (!StringUtils.hasText(role)) {
            return true;
        }
        return "api".equals(role.trim());
    }
}
