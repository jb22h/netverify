package com.netverify.controlplane.execution;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Gates Python runner execution beans: present in local modes and future worker,
 * absent on distributed API.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnPythonExecutionCondition.class)
public @interface ConditionalOnPythonExecution {
}
