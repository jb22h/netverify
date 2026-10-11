package com.netverify.controlplane.execution;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * True when {@code postgres} + {@code redis} are active and {@code netverify.role=api}.
 */
public final class OnRedisRunDispatcherCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        if (!OnLocalRunDispatcherCondition.distributedProfilesActive(environment)) {
            return false;
        }
        String role = environment.getProperty("netverify.role", "");
        return StringUtils.hasText(role) && "api".equals(role.trim());
    }
}
