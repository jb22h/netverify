-- Phase 9 M1: durable test definitions and per-execution run history.
-- CREATED is an API-only view for a test with zero runs; it is never a test_runs row.

CREATE TABLE tests (
    id          UUID PRIMARY KEY,
    name        TEXT NOT NULL,
    scenario    TEXT NOT NULL,
    count       INTEGER NOT NULL,
    clients     INTEGER NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE test_runs (
    id                 UUID PRIMARY KEY,
    test_id            UUID NOT NULL REFERENCES tests(id),
    status             TEXT NOT NULL,
    queued_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at         TIMESTAMPTZ NULL,
    finished_at        TIMESTAMPTZ NULL,
    exit_code          INTEGER NULL,
    failure_category   TEXT NULL,
    stdout             TEXT NULL,
    stderr             TEXT NULL,
    runner_result      JSONB NULL,
    CONSTRAINT chk_test_runs_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'PASSED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT chk_test_runs_failure_category CHECK (
        failure_category IS NULL
        OR failure_category IN ('VERIFICATION', 'SETUP', 'LAUNCH', 'TIMEOUT', 'PARSE')
    )
);

-- Deterministic latest-run ordering for later milestones.
CREATE INDEX idx_test_runs_latest
    ON test_runs (test_id, queued_at DESC, id DESC);

-- At most one active (QUEUED or RUNNING) run per test.
CREATE UNIQUE INDEX uq_test_runs_one_active_per_test
    ON test_runs (test_id)
    WHERE status IN ('QUEUED', 'RUNNING');
