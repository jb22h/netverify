ALTER TABLE test_runs
    ADD COLUMN worker_id TEXT NULL,
    ADD COLUMN lease_expires_at TIMESTAMPTZ NULL;

CREATE INDEX idx_test_runs_running_with_lease
    ON test_runs (lease_expires_at)
    WHERE status = 'RUNNING'
      AND lease_expires_at IS NOT NULL;
