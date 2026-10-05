package com.netverify.controlplane.config;

import com.netverify.controlplane.persistence.TestRunRepository;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Single-instance startup recovery: terminalize orphan QUEUED/RUNNING rows so the
 * partial unique active-run index cannot block a test forever after a crash.
 *
 * <p>Constructor depends on {@link Flyway} so migrations complete before recovery.
 * Idempotent: a second call with no active rows updates zero rows.
 */
@Component
@Profile("postgres")
@Order(0)
public class ActiveRunRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ActiveRunRecovery.class);

    public static final String ORPHAN_NOTE = "orphaned by control-plane restart";

    private final TestRunRepository testRunRepository;

    public ActiveRunRecovery(TestRunRepository testRunRepository, Flyway flyway) {
        this.testRunRepository = testRunRepository;
        // Flyway parameter forces migrate() before this bean is constructed.
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = testRunRepository.cancelAllActive(Instant.now(), ORPHAN_NOTE);
        if (recovered > 0) {
            log.warn(
                    "recovered {} orphan active test_runs as CANCELLED ({})",
                    recovered,
                    ORPHAN_NOTE
            );
        } else {
            log.info("active-run recovery: no QUEUED/RUNNING rows to cancel");
        }
    }
}
