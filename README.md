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
- **Phase 10** — Queue and Distributed Workers: complete
- **Phase 11** — Docker: complete

**Current phase:** Phase 11 — Docker: complete
**Next phase:** Phase 12 — React / TypeScript Dashboard

Phase 8 adds a Java 21 / Maven / Spring Boot control plane under `control-plane/`. Tests are created and started over HTTP; a bounded Java executor launches `automation/runner.py --json` via `ProcessBuilder`, which drives the existing C `net_server` / `net_client`.

Phase 9 adds optional PostgreSQL durability (`postgres` Spring profile): immutable definitions in `tests`, every execution in `test_runs`, and `GET /api/tests/{id}/runs` history. The default profile remains completely DB-free (`InMemoryTestRepository` + `InMemoryRunLifecycleStore`).

Phase 10 adds optional distributed execution: the same Spring Boot JAR runs as either an HTTP **API** process or a headless **worker** process. PostgreSQL remains authoritative for run state and lease fencing; Redis Streams is transport only (at-least-once delivery).

Phase 11 packages that distributed stack in Docker: one application image for API and worker roles, plus official PostgreSQL and Redis Compose services. React and AWS remain later phases.

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
├── Dockerfile
├── docker-compose.yml
├── .env.example
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

## Control Plane (Phases 8–10)

Build the C binaries first, then point the control plane at the repo root and start Spring Boot:

```bash
make
export NETVERIFY_REPO_ROOT="$PWD"
cd control-plane
mvn spring-boot:run
```

Optional config (defaults shown): `netverify.runner-timeout-seconds=120`, `netverify.python-command=python3`. `NETVERIFY_REPO_ROOT` overrides `netverify.repo-root`.

### Profiles

| Profile / role | Role |
|----------------|------|
| default (DB-free) | Local in-memory store; process-local; restart loses state |
| `postgres` | JDBC + Flyway; local executor still runs Python in-process |
| `postgres,redis` + `netverify.role=api` | Distributed API: HTTP + PG write + Redis publish; **no** Python |
| `postgres,redis` + `netverify.role=worker` | Distributed worker: headless consumer; PG claim/fence + Python/C |

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

Also: executor rejection → `QUEUED → FAILED` (`LAUNCH`); under **postgres-only** (no redis), startup recovery cancels orphan `QUEUED`/`RUNNING` rows as `CANCELLED` (single-instance assumption). That cancel-all path is **disabled** when the `redis` profile is active (distributed mode uses leases instead). PostgreSQL allows at most one active (`QUEUED`/`RUNNING`) run per test via a partial unique index; transitions use conditional SQL updates.

Under `postgres`, definitions, full run history, and latest results/logs/metrics survive backend restart; a persisted test can be run again after restart.

### Architecture (local Phases 8–9)

```text
TestService
  → TestRepository          (definitions)
  → RunLifecycleStore       (runs / history)
```

- Default: `InMemoryTestRepository` + `InMemoryRunLifecycleStore`
- Postgres: `JdbcTestRepository` + `JdbcRunLifecycleStore` → `TestRunRepository`
- Stack: Spring JDBC / `JdbcTemplate`, Flyway (V1+V2 lease columns), PostgreSQL JSONB for `runner_result`, relational `failure_category`. No JPA.

### Distributed architecture (Phase 10)

```text
HTTP client
    → Spring API process (role=api)
    → PostgreSQL (authoritative state)
    → Redis Stream (transport)
    → Spring worker process (role=worker, web=none)
    → Python runner
    → C net_server / net_client
```

API and worker are **separate OS/JVM processes** using the **same** Spring Boot JAR.

| Concern | Redis | PostgreSQL |
|---------|-------|------------|
| Role | At-least-once transport (consumer group, PEL, XAUTOCLAIM) | Authoritative run state, ownership, results |
| Message | Exactly `runId` + `testId` | Full definition, lifecycle, logs, metrics |
| Execution authority | **No** — Redis ownership alone never authorizes Python | **Yes** — successful PG claim + valid lease |

Duplicate Redis delivery is possible; PG fencing prevents a second effective execution of the same active run in the verified scenarios. This is **not** generic exactly-once messaging.

**Lease defaults:** PG claim/renew horizon **120s**; heartbeat every **30s**; Redis XAUTOCLAIM min-idle **150s** (intentionally greater than 120s). Expired `RUNNING` is reclaimed to `FAILED` / `TIMEOUT` (no automatic Python retry). `RUNNING_WITHOUT_LEASE` is never stolen automatically (no ACK; not auto-repaired).

**Crash / recovery matrix:**

| | Failure window | Outcome |
|---|----------------|---------|
| **A** | Redis delivered, die before PG claim | Later XAUTOCLAIM → new worker PG-claims and executes once |
| **B** | Die after PG claim / during execution | Peers blocked while lease valid; after expiry → reclaim `FAILED`/`TIMEOUT` (no automatic rerun) |
| **C** | PG terminal committed, die before Redis ACK | Redelivery → `ALREADY_TERMINAL` → ACK only |
| **D** | Multiple healthy workers | Shared consumer group; distinct worker IDs; PG fencing blocks duplicate execution of the same run |
| **E** | Worker restart | Restart does not bypass a still-valid PG lease; terminal/redelivery follow PG state |

**Accepted Phase 10 boundaries** (deliberate scope, not hidden bugs): DB→Redis is a dual write (no transactional outbox); crash after PG `QUEUED` before Redis publish can leave an orphan `QUEUED` row; no stale-QUEUED republisher; no DB-wide lease sweeper; `RUNNING_WITHOUT_LEASE` not auto-repaired; externally lost Redis messages not reconstructed; expired `RUNNING` is terminal `TIMEOUT` not retry; batch size remains 1; postgres-only cancel-all recovery stays single-instance. React/AWS are later phases. Full detail in [PROJECT_SPEC.md](PROJECT_SPEC.md) Phase 10. Docker packaging is Phase 11 (below).

### Distributed launch

Worker only: `NETVERIFY_REPO_ROOT` must point to the NetVerify repository root containing `automation/runner.py`, `net_server`, and `net_client`. Never commit real DB passwords.

**API process:**

```bash
export NETVERIFY_DB_URL='jdbc:postgresql://HOST:5432/DBNAME'
export NETVERIFY_DB_USER='...'
export NETVERIFY_DB_PASSWORD='...'
export NETVERIFY_REDIS_HOST=localhost
export NETVERIFY_REDIS_PORT=6379
cd control-plane
mvn spring-boot:run \
  -Dspring-boot.run.profiles=postgres,redis \
  -Dspring-boot.run.arguments="--netverify.role=api"
```

**Worker process** (separate terminal; distinct `NETVERIFY_WORKER_ID` per worker):

```bash
export NETVERIFY_DB_URL='jdbc:postgresql://HOST:5432/DBNAME'
export NETVERIFY_DB_USER='...'
export NETVERIFY_DB_PASSWORD='...'
export NETVERIFY_REDIS_HOST=localhost
export NETVERIFY_REDIS_PORT=6379
export NETVERIFY_WORKER_ID=worker-1
export NETVERIFY_REPO_ROOT=/path/to/netverify
cd control-plane
mvn spring-boot:run \
  -Dspring-boot.run.profiles=postgres,redis \
  -Dspring-boot.run.arguments="--netverify.role=worker --spring.main.web-application-type=none --netverify.worker-id=${NETVERIFY_WORKER_ID}"
```

### Testing

```bash
cd control-plane

# DB-free unit/API suite (default; PG/Redis ITs skip without env)
mvn test

# Opt-in PostgreSQL integration tests
export NETVERIFY_DB_URL='jdbc:postgresql://HOST:5432/DBNAME'
export NETVERIFY_DB_USER='...'
export NETVERIFY_DB_PASSWORD='...'
mvn test

# Opt-in Redis Streams IT
export NETVERIFY_REDIS_HOST=localhost
export NETVERIFY_REDIS_PORT=6379
mvn -Dtest=RedisRunQueueIntegrationTest test

# Opt-in Phase 10 crash / multi-worker ITs (need both DB + Redis env)
mvn -Dtest=WorkerCrashRecoveryIntegrationTest,WorkerMultiWorkerIntegrationTest test

# Opt-in real Python runner IT (needs built C binaries + repo root)
export NETVERIFY_REPO_ROOT=/path/to/netverify
mvn test
```

Default `mvn test` stays DB/Redis-free; opt-in ITs skip cleanly without env. Notable Phase 10 tests: `WorkerCrashRecoveryIntegrationTest` (crash windows A/B/C), `WorkerMultiWorkerIntegrationTest` (multi-worker D + restart E), `WorkerRunLifecycleIntegrationTest` (PG claim/lease/fencing), `RedisRunQueueIntegrationTest` (stream/group/XAUTOCLAIM).

Verified real distributed smoke (Phase 10 M3): API queued a run while the worker was offline; a later worker consumed the Redis message; Python/C ran only in the worker; PostgreSQL reached a terminal result; worker restart did not duplicate an already-terminal run.

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

## Docker (Phase 11)

Phase 11 makes the Phase 10 distributed stack reproducible with Docker Compose. Phase 10 semantics are unchanged: Redis is at-least-once transport; PostgreSQL is authoritative state and execution ownership; there is no generic exactly-once guarantee, no transactional outbox, and no stale-`QUEUED` republisher. Expired `RUNNING` still becomes `FAILED` / `TIMEOUT`.

### Architecture

```text
HTTP client
    → api container          (same app image, role=api)
    → PostgreSQL + Redis     (official images: postgres:18, redis:8)
    → worker container       (same app image, role=worker)
    → Python runner
    → C net_server / net_client
```

- **API** and **worker** are separate containers/processes using the **same** NetVerify application image (`Dockerfile` → `netverify-app:local`) with different runtime roles/config.
- PostgreSQL and Redis are separate official containers. Cross-container deps use Compose DNS names `postgres` / `redis` — **never** `localhost`.
- The worker image layout includes `automation/runner.py`, `net_server`, and `net_client` at `NETVERIFY_REPO_ROOT=/opt/netverify` (worker only; API does not execute Python).

Milestones: **M1** application image; **M2** Compose stack (`postgres`, `redis`, `api`, `worker`); **M3** optional `worker-2` + real Docker verification.

### Developer workflow

Copy `.env.example` to a local `.env` (gitignored). Never commit a real `.env` or DB password.

```bash
cp .env.example .env
docker compose up --build -d
docker compose ps
docker compose logs -f api worker
docker compose down
```

- `docker compose down` removes containers and the Compose network but **preserves** the named PostgreSQL volume (`netverify-postgres-data`).
- `docker compose down -v` intentionally deletes that volume (destructive).

Default stack: `postgres` + `redis` + `api` (`:8080`) + one worker (`NETVERIFY_WORKER_ID=worker-1`).

### Optional second worker

Worker IDs are part of PostgreSQL lease ownership and Redis consumer identity, so blind `compose scale` with one shared fixed worker ID is unsafe. Use the explicit `worker-2` service behind the Compose profile `multiworker`:

```bash
docker compose --profile multiworker up -d worker-2
```

### Verified Docker smoke

Real verification completed on this stack:

1. Default stack healthy: postgres, redis, api on `:8080`, headless `worker-1`.
2. HTTP `basic_ping_pong`: create → `QUEUED` → `PASSED`, `exitCode=0`, runner metrics present; PostgreSQL `worker_id=worker-1`, `lease_expires_at` cleared.
3. Restart `worker-1`: still exactly one `test_run` (same run UUID), still `PASSED` — no duplicate execution.
4. Multi-worker: stop `worker-1`, start `worker-2` via `--profile multiworker`; new `basic_ping_pong` → `PASSED` with PostgreSQL `worker_id=worker-2`, lease cleared.
5. `docker compose down` preserves `netverify_netverify-postgres-data`.

## Clean

```bash
make clean
```

Removes `net_server` and `net_client`.

## Full Roadmap

See [PROJECT_SPEC.md](PROJECT_SPEC.md) for the complete development plan and future phases.
