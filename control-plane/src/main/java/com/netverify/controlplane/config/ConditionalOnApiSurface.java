package com.netverify.controlplane.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * API surface (controllers / TestService) when role is blank/absent or {@code api}.
 * Blank is treated as absent even if the property is bound to an empty string.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnApiSurfaceCondition.class)
public @interface ConditionalOnApiSurface {
}
