# NetVerify

NetVerify is a network verification and test automation platform that runs repeatable TCP/network tests, collects logs and performance metrics, and determines whether the tested system behaves correctly.

## Current Status

- **Phase 0** — Environment and Repository: complete
- **Phase 1** — Minimal TCP Client and Server in C: complete
- **Phase 2** — Protocol and Multiple Messages: complete
- **Phase 3** — Multiple Clients and Concurrency: complete
- **Phase 4** — Structured Logging and Metrics: complete
- **Phase 5** — Python Automation Layer: complete
- **Phase 6** — Failure Injection: complete
- **Phase 7** — Stress and Performance Testing: complete
- **Phase 8** — Java Spring Boot Control Plane: complete
- **Phase 9** — PostgreSQL Persistence: complete

**Current phase:** Phase 9 — PostgreSQL Persistence: complete
**Next phase:** Phase 10 — Queue and Workers

Phase 8 adds a Java 21 / Maven / Spring Boot control plane under `control-plane/`. Tests are created and started over HTTP; a bounded Java executor launches `automation/runner.py --json` via `ProcessBuilder`, which drives the existing C `net_server` / `net_client`.

Phase 9 adds optional PostgreSQL durability (`postgres` Spring profile): immutable definitions in `tests`, every execution in `test_runs`, and `GET /api/tests/{id}/runs` history. The default profile remains completely DB-free (`InMemoryTestRepository` + `InMemoryRunLifecycleStore`). Redis, Docker, React, AWS, and distributed workers are not part of Phase 9.

HTTP scenarios: `basic_ping_pong`, `connect_fail_no_server`, `server_crash_mid_session`, `concurrent_load`. `latency_injection` is intentionally not exposed (requires privileged `tc`/`netem`).

## Directory Layout

```text
netverify/
├── automation/
│   ├── runner.py
│   ├── logparse.py
│   ├── assertions.py
│   └── netem.py
├── control-plane/
│   ├── pom.xml
│   └── src/
├── networking/
│   ├── client/
│   │   └── client.c
│   └── server/
│       └── server.c
├── Makefile
├── PROJECT_SPEC.md
└── README.md
```

See [PROJECT_SPEC.md](PROJECT_SPEC.md) for the full roadmap.

## Prerequisites

- Linux (WSL2 / Ubuntu)
- GCC
- GNU Make
- Python 3
- Java 21
- Maven

## Build

```bash
make
```

This produces `net_server` and `net_client`.

## Run (C client/server)

Use two terminals from the project root.

**Terminal 1:**

```bash
./net_server --port 9000
```

**Terminal 2:**

```bash
./net_client --host 127.0.0.1 --port 9000 --count 100
```

**Expected output (abbreviated):**

```text
Server: PING 1 … PING 100, then session metrics
Client: PONG 1 … PONG 100, then RTT and session metrics
```

## Automation (examples)

```bash
python3 automation/runner.py --all
python3 automation/runner.py --test concurrent_load --clients 100 --count 1000
```

## Control Plane (Phases 8–9)

Build the C binaries first, then point the control plane at the repo root and start Spring Boot:

```bash
make
export NETVERIFY_REPO_ROOT="$PWD"
cd control-plane
mvn spring-boot:run
```

Optional config (defaults shown): `netverify.runner-timeout-seconds=120`, `netverify.python-command=python3`. `NETVERIFY_REPO_ROOT` overrides `netverify.repo-root`.

### Profiles

| Profile | Persistence |
|---------|-------------|
| default (DB-free) | `InMemoryTestRepository` + `InMemoryRunLifecycleStore` (process-local; restart loses state) |
| `postgres` | `JdbcTestRepository` + `JdbcRunLifecycleStore` → `TestRunRepository` (Flyway V1 schema) |

PostgreSQL credentials (required for the `postgres` profile; never commit real values):

```bash
export NETVERIFY_DB_URL='jdbc:postgresql://HOST:5432/DBNAME'
export NETVERIFY_DB_USER='...'
export NETVERIFY_DB_PASSWORD='...'
cd control-plane
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

### Endpoints

```text
POST /api/tests
GET  /api/tests
GET  /api/tests/{id}                 # latest-run view
POST /api/tests/{id}/run             # response id = TEST id (backward-compatible)
GET  /api/tests/{id}/runs            # compact newest-first history (run UUID per entry)
GET  /api/tests/{id}/results         # latest terminal run
GET  /api/tests/{id}/logs            # latest terminal run
GET  /api/tests/{id}/metrics         # latest terminal run
```

History (`GET .../runs`): unknown test → 404; known with zero runs → `[]`; otherwise newest-first (`queued_at DESC`, then run UUID canonical text `DESC`). Each entry exposes `id` (run UUID), `testId`, `status`, `queuedAt`, `startedAt`, `finishedAt`, `exitCode`, `failureCategory`. It omits `stdout` / `stderr` / `runnerResult` (those remain on the latest-only detail endpoints). No pagination yet (current scale limitation). There is no `GET /runs/{runId}`.

### Run lifecycle

Each execution has its own internal run UUID. Transitions:

```text
QUEUED → RUNNING → PASSED | FAILED
```

Also: executor rejection → `QUEUED → FAILED` (`LAUNCH`); startup recovery cancels orphan `QUEUED`/`RUNNING` rows as `CANCELLED` (single-instance assumption; no multi-instance coordination). PostgreSQL allows at most one active (`QUEUED`/`RUNNING`) run per test via a partial unique index; transitions use conditional SQL updates.

Under `postgres`, definitions, full run history, and latest results/logs/metrics survive backend restart; a persisted test can be run again after restart.

### Architecture

```text
TestService
  → TestRepository          (definitions)
  → RunLifecycleStore       (runs / history)
```

- Default: `InMemoryTestRepository` + `InMemoryRunLifecycleStore`
- Postgres: `JdbcTestRepository` + `JdbcRunLifecycleStore` → `TestRunRepository`
- Stack: Spring JDBC / `JdbcTemplate`, Flyway (V1), PostgreSQL JSONB for `runner_result`, relational `failure_category`. No JPA. The old `InMemoryTestStore` lifecycle was retired in Phase 9.

### Testing

```bash
cd control-plane

# DB-free unit/API suite (default profile; PG ITs skip without DB env)
mvn test

# Opt-in PostgreSQL integration tests
export NETVERIFY_DB_URL='jdbc:postgresql://HOST:5432/DBNAME'
export NETVERIFY_DB_USER='...'
export NETVERIFY_DB_PASSWORD='...'
mvn test

# Opt-in real Python runner IT (needs built C binaries + repo root)
export NETVERIFY_REPO_ROOT=/path/to/netverify
mvn test
```

Phase 9 final verification: **114** tests. DB-free run skips PostgreSQL ITs as expected. With `NETVERIFY_DB_*` + `NETVERIFY_REPO_ROOT`: **114** tests, **0** failures / errors / skipped. Real HTTP restart check confirmed two distinct persisted run IDs, history ordering, and identical history/results/logs/metrics after JVM restart.

Minimal curl flow (server on port 8080):

```bash
# Create
curl -s -X POST http://localhost:8080/api/tests \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo","scenario":"basic_ping_pong","count":10}'
# → note the returned "id" (test id)

# Queue a run (response id is still the test id)
curl -s -X POST http://localhost:8080/api/tests/<id>/run

# Poll latest view, history, then terminal output
curl -s http://localhost:8080/api/tests/<id>
curl -s http://localhost:8080/api/tests/<id>/runs
curl -s http://localhost:8080/api/tests/<id>/results
curl -s http://localhost:8080/api/tests/<id>/metrics
curl -s http://localhost:8080/api/tests/<id>/logs
```

## Clean

```bash
make clean
```

Removes `net_server` and `net_client`.

## Full Roadmap

See [PROJECT_SPEC.md](PROJECT_SPEC.md) for the complete development plan and future phases.
