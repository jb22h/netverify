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
- **Phase 12** — React + TypeScript Dashboard: complete
- **Phase 13** — CI/CD (CI): complete

**Current phase:** Phase 13 — CI/CD (CI): complete
**Next phase:** Phase 14 — Cloud / AWS

Phase 8–12 provide the Spring Boot control plane, PostgreSQL, Redis Streams workers, Docker Compose, and the React dashboard. Phase 13 adds GitHub Actions **CI** (build/test/image validation). **Deployment / CD and AWS are not implemented.**

HTTP scenarios (dashboard + API): `basic_ping_pong`, `connect_fail_no_server`, `server_crash_mid_session`, `concurrent_load`. `latency_injection` is intentionally not exposed via HTTP and is excluded from CI (privileged `tc`/`netem`).

## Quick start (Docker Compose)

Copy `.env.example` to a local `.env` (gitignored). Never commit a real `.env` or DB password.

```bash
cp .env.example .env
docker compose up --build -d
```

Then open:

- **Dashboard:** http://localhost:3000
- **API (debug):** http://localhost:8080

Optional second worker (distinct `NETVERIFY_WORKER_ID`; not blind `scale`):

```bash
docker compose --profile multiworker up --build -d
```

Useful commands:

```bash
docker compose ps
docker compose logs -f api worker dashboard
docker compose down          # keeps PostgreSQL named volume
# docker compose down -v     # intentionally deletes the volume
```

On a cold start the dashboard may briefly return **502** for `/api` while Spring Boot is still starting (`dashboard` depends on `api` with `service_started`; the API has no healthcheck). Retry after a few seconds.

## Architecture

```text
Browser
    → nginx React dashboard (:3000)
         → static UI
         → /api/*  →  Spring Boot API (:8080, role=api)
                        → Redis Streams
                             → Java worker (role=worker)
                                  → Python runner
                                       → C net_server / net_client
                        → PostgreSQL
```

- Dashboard and API are separate containers. The browser calls **same-origin** `/api/...` on `:3000`; nginx proxies to Compose DNS `api:8080`. No Spring CORS config is required for this path.
- API remains published on host `:8080` for curl/debugging.
- PostgreSQL and Redis use service names `postgres` / `redis` inside Compose — never `localhost` for cross-container traffic.
- Phase 10 semantics unchanged: Redis is at-least-once transport; PostgreSQL is authoritative state / execution ownership.

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
├── frontend/
│   ├── Dockerfile
│   ├── nginx.conf
│   ├── package.json
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
- Docker + Docker Compose (for the full stack)
- GCC, GNU Make, Python 3 (local C/automation)
- Java 21, Maven (local control plane)
- Node.js 24+ / npm (local dashboard dev)

## Build

```bash
make
```

This produces `net_server` and `net_client` (also built inside the worker image).

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

## Dashboard (Phase 12)

React + TypeScript UI under `frontend/` (Vite, React Router, native `fetch`). No Redux, no React Query, no chart library.

### Capabilities

- List and create tests (HTTP scenarios above; `clients` only for `concurrent_load`)
- Test detail with Run action
- Poll status while `QUEUED` / `RUNNING`
- Terminal PASS/FAIL presentation
- Run history (`GET .../runs`)
- Latest result, generic heterogeneous metrics renderer, stdout/stderr logs
- Section-specific errors; **409** treated as “artifact not ready yet”
- Stale result/metrics/logs hidden while a new run is active

### Local frontend development

API must listen on `:8080` (Compose `api` or local Spring Boot). Vite proxies `/api` → `http://localhost:8080`.

```bash
cd frontend
npm ci          # or: npm install
npm run dev     # http://127.0.0.1:5173
```

### Containerized dashboard

- Multi-stage `frontend/Dockerfile`: `node:24-alpine` build → `nginx:1.28-alpine` runtime (no Node in the final image)
- Compose service `dashboard` publishes **http://localhost:3000**
- nginx serves the static build and proxies `/api/` → `http://api:8080` (Compose DNS; path preserved)
- SPA deep links use `try_files … /index.html`
- Same-origin browser calls → **no Spring CORS** configuration required

### Current limitations

- Metrics are generic key/value (and nested) rendering — **no charts / time-series UI**
- Result, metrics, and logs endpoints expose **latest-run** artifacts only (no `GET /runs/{runId}` detail)
- No authentication
- No WebSocket / live log streaming
- AWS / deployment (CD) are **not** implemented

### Verified Compose + dashboard smoke

1. `docker compose up --build -d` — stack includes postgres, redis, api, worker, dashboard.
2. After API readiness: `http://localhost:3000/api/tests` and `http://localhost:8080/api/tests` both succeed.
3. Created test via dashboard (`basic_ping_pong`, count 5) → **PASSED**, `exitCode=0`, `passed=true`, real metrics, stdout present, stderr empty.
4. Direct deep link `/tests/<uuid>` → nginx **200** + `index.html`.
5. `docker compose down` preserves `netverify_netverify-postgres-data`.

Earlier Phase 11 verification (API/worker without dashboard) still stands: worker-1/worker-2 ownership, restart without duplicate terminal runs, PG volume retention.

## Docker notes (Phases 11–12)

Phase 11 introduced the app image (`netverify-app:local`) and Compose `postgres` / `redis` / `api` / `worker` (+ optional `worker-2`). Phase 12 adds `dashboard` (`netverify-dashboard:local` on `:3000`).

Worker-only: `NETVERIFY_REPO_ROOT=/opt/netverify` inside the worker container. Worker IDs are PG lease owners and Redis consumer names — use the `multiworker` profile for a second worker, not blind `compose scale`.

Phase 10 semantics inside containers are unchanged (Redis transport, PostgreSQL ownership, no transactional outbox, expired `RUNNING` → `FAILED`/`TIMEOUT`).

## Continuous Integration (Phase 13)

GitHub Actions workflow: [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

**Triggers:** push to `main`, and all pull requests.

**Runner:** `ubuntu-24.04`.

**Actions:** `actions/checkout@v7`, `actions/setup-java@v6` (Temurin 21), `actions/setup-node@v7` (Node 24).

| Job | Validates |
|-----|-----------|
| `c-python` | `make` + `python3 automation/runner.py --all` (safe suite; no `latency_injection` / sudo tc/netem) |
| `java` | `mvn -B test` in `control-plane` **without** DB/Redis env (env-gated ITs skip here) |
| `frontend` | `npm ci`, `npm run lint`, `npm run build` |
| `java-integration` | Postgres **18** + Redis **8** service containers (healthchecked); ephemeral CI DB credentials; `make` then `mvn -B test` with `NETVERIFY_*` + `NETVERIFY_REPO_ROOT` so PG/Redis/worker/real-runner ITs **run** |
| `docker` | `docker build` → `netverify-app:ci` and `netverify-dashboard:ci`; `docker compose config --quiet` via temporary `.env` from `.env.example` (cleaned with `trap`); image inspect; **no** registry push, **no** `compose up` |

Verified on GitHub Actions: full five-job green run (including `java-integration` **248** tests, **0** failures / errors / skipped).

**Not in CI:** privileged `latency_injection`; full Compose browser/runtime smoke (local Phase 11/12 Compose verification remains the E2E authority); deployment, AWS, image registry publishing, production secrets.

### Local equivalents

```bash
# c-python
make
python3 automation/runner.py --all

# java (DB/Redis-free baseline; ITs skip without env)
cd control-plane && mvn -B test && cd ..

# frontend
cd frontend && npm ci && npm run lint && npm run build && cd ..

# docker validation (no compose up)
docker build -t netverify-app:ci .
docker build -t netverify-dashboard:ci ./frontend
cp .env.example .env
docker compose config --quiet
rm -f .env
```

For local service-backed Maven ITs, point `NETVERIFY_DB_*`, `NETVERIFY_REDIS_*`, and `NETVERIFY_REPO_ROOT` at a running Postgres/Redis and a built repo root (same contract as the `java-integration` job).

## Clean

```bash
make clean
```

Removes `net_server` and `net_client`.

## Full Roadmap

See [PROJECT_SPEC.md](PROJECT_SPEC.md) for the complete development plan and future phases.
