package com.netverify.controlplane.execution;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True unless both {@code postgres} and {@code redis} profiles are active
 * (covers default, postgres-only, and redis-only M1 smoke).
 */
public final class OnLocalRunDispatcherCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !distributedProfilesActive(context.getEnvironment());
    }

    static boolean distributedProfilesActive(Environment environment) {
        Set<String> profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        return profiles.contains("postgres") && profiles.contains("redis");
    }
}
