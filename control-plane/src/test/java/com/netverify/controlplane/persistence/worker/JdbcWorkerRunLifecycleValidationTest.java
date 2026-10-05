package com.netverify.controlplane.persistence.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcWorkerRunLifecycleValidationTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcWorkerRunLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        lifecycle = new JdbcWorkerRunLifecycle(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void claimRejectsNullLeaseDuration() {
        assertThrows(
                NullPointerException.class,
                () -> lifecycle.claimForExecution(
                        UUID.randomUUID(), UUID.randomUUID(), "worker-a", null
                )
        );
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void claimRejectsZeroLeaseDuration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> lifecycle.claimForExecution(
                        UUID.randomUUID(), UUID.randomUUID(), "worker-a", Duration.ZERO
                )
        );
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void claimRejectsNegativeLeaseDuration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> lifecycle.claimForExecution(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "worker-a",
                        Duration.ofSeconds(-1)
                )
        );
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void claimRejectsSubSecondLeaseDuration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> lifecycle.claimForExecution(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "worker-a",
                        Duration.ofMillis(999)
                )
        );
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void renewRejectsSubSecondLeaseDuration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> lifecycle.renewLease(
                        UUID.randomUUID(), "worker-a", Duration.ofNanos(500_000_000)
                )
        );
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void claimAcceptsOneSecondLeaseDuration() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);

        lifecycle.claimForExecution(
                UUID.randomUUID(), UUID.randomUUID(), "worker-a", Duration.ofSeconds(1)
        );

        verify(jdbcTemplate).update(anyString(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reclaimRetrySucceedsWhenLeaseExpiresBetweenUpdateAndClassify() throws Exception {
        UUID runId = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(runId))).thenReturn(0, 1);
        stubReclaimRow("RUNNING", false, true);

        ReclaimResult result = lifecycle.reclaimExpiredRunning(runId);

        assertEquals(ReclaimOutcome.RECLAIMED, result.outcome());
        verify(jdbcTemplate, times(2)).update(anyString(), eq(runId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reclaimDoesNotMapRunningExpiredLeaseToNotRunning() throws Exception {
        UUID runId = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(runId))).thenReturn(0, 0);
        stubReclaimRow("RUNNING", false, true);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> lifecycle.reclaimExpiredRunning(runId)
        );
        assertEquals(
                true,
                thrown.getMessage().contains("expired lease"),
                thrown.getMessage()
        );
        verify(jdbcTemplate, times(2)).update(anyString(), eq(runId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reclaimClassifiesValidLeaseWithoutRetry() throws Exception {
        UUID runId = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(runId))).thenReturn(0);
        stubReclaimRow("RUNNING", true, true);

        ReclaimResult result = lifecycle.reclaimExpiredRunning(runId);

        assertEquals(ReclaimOutcome.LEASE_STILL_VALID, result.outcome());
        verify(jdbcTemplate, times(1)).update(anyString(), eq(runId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reclaimClassifiesMissingRow() {
        UUID runId = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(runId))).thenReturn(0);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(runId)))
                .thenThrow(new EmptyResultDataAccessException(1));

        ReclaimResult result = lifecycle.reclaimExpiredRunning(runId);

        assertEquals(ReclaimOutcome.NOT_FOUND, result.outcome());
        verify(jdbcTemplate, times(1)).update(anyString(), eq(runId));
    }

    @SuppressWarnings("unchecked")
    private void stubReclaimRow(String status, boolean leaseValid, boolean hasLease)
            throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any()))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("status")).thenReturn(status);
                    when(rs.getBoolean("lease_valid")).thenReturn(leaseValid);
                    when(rs.getBoolean("has_lease")).thenReturn(hasLease);
                    return mapper.mapRow(rs, 0);
                });
    }
}
