package com.netverify.controlplane.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Registers a bean only when postgres+redis profiles are active and
 * {@code netverify.role=worker}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnWorkerRoleCondition.class)
public @interface ConditionalOnWorkerRole {
}
