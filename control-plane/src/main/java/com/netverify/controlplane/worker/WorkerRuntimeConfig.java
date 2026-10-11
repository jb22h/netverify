package com.netverify.controlplane.worker;

import com.netverify.controlplane.config.ConditionalOnWorkerRole;
import com.netverify.controlplane.execution.RunLeaseHeartbeater;
import com.netverify.controlplane.persistence.worker.WorkerRunLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared worker-only runtime beans. Spring owns {@link RunLeaseHeartbeater} shutdown.
 */
@Configuration
@ConditionalOnWorkerRole
public class WorkerRuntimeConfig {

    @Bean(destroyMethod = "close")
    public RunLeaseHeartbeater runLeaseHeartbeater(WorkerRunLifecycle workerRunLifecycle) {
        return new RunLeaseHeartbeater(workerRunLifecycle);
    }
}
