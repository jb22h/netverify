# NetVerify — Master Project Specification

## 1. Project Name
**NetVerify**

## 2. One-Sentence Description
NetVerify is a network verification and test automation platform that runs repeatable TCP/network tests, injects network failures, collects logs and performance metrics, and determines whether the tested system behaves correctly.

---

## 3. Why This Project Exists
The goal is to build a serious Software Engineering portfolio project that demonstrates:

- C programming
- Python scripting and automation
- Java backend development
- Linux development
- Networking and TCP/IP
- Operating-system concepts
- Testing and verification
- Debugging
- Logs, metrics, and tracing concepts
- Concurrency
- Queues and workers
- Databases
- Distributed systems
- Docker
- CI/CD
- Cloud deployment
- Optional AI-assisted debugging

Target interview relevance:
- Software Development Engineer (SDE)
- Backend Software Engineer
- Networking Software Engineer
- Verification / Test Automation Engineer
- Infrastructure / Systems roles

---

## 4. Core Idea
A user creates or selects a network test scenario.

Example:
- Protocol: TCP
- Number of clients: 100
- Duration: 30 seconds
- Packet loss: 10%
- Added latency: 50 ms
- Reconnect enabled: yes

NetVerify then:
1. Starts the server.
2. Starts one or more clients.
3. Generates network traffic.
4. Optionally injects failures such as packet loss or latency.
5. Collects logs and counters.
6. Measures latency, throughput, disconnects, reconnects, errors, etc.
7. Analyzes the result.
8. Marks the test as PASS or FAIL.
9. Stores the result.
10. Later displays it in a dashboard.

High-level flow:

```text
User / API
    |
    v
Java Control Plane
    |
    v
Job Queue
    |
    v
Python Test Runner
    |
    +--> C Network Client(s)
    |
    +--> C Network Server
    |
    v
Linux Network / Failure Injection
    |
    v
Logs + Metrics
    |
    v
Analysis
    |
    v
PASS / FAIL
```

---

## 5. Technology Stack

### C
Purpose:
- Low-level networking
- TCP server
- TCP client
- Socket API
- Performance-sensitive traffic generation

Concepts to learn:
- socket()
- bind()
- listen()
- accept()
- connect()
- send()
- recv()
- close()
- file descriptors
- buffers
- errno
- blocking I/O
- non-blocking I/O later
- select/poll/epoll later
- threads later
- synchronization later

### Python
Purpose:
- Test automation
- Scripting
- Process orchestration
- Failure injection
- Log parsing
- Test validation
- Report generation
- Load generation

Typical scripts:
- run_scenario.py
- generate_load.py
- analyze_logs.py
- generate_report.py
- cleanup_environment.py

Python must have a real automation role in the project.

### Java + Spring Boot
Purpose:
- Main backend / control plane
- REST API
- Test job management
- State management
- Queue integration
- Database access
- Authentication later
- Distributed worker coordination later

Concepts:
- REST
- Controllers
- Services
- Repositories
- Dependency Injection
- DTOs
- Validation
- Exception handling
- Transactions
- Concurrency
- Retry logic
- Idempotency
- Background jobs

### PostgreSQL
Purpose:
Store:
- Test scenarios
- Test runs
- Test configuration
- Results
- Metrics
- Failures
- Timestamps
- Users/projects later

Possible entities:

```text
TestScenario
- id
- name
- protocol
- clientCount
- durationSeconds
- packetLossPercent
- latencyMs

TestRun
- id
- scenarioId
- status
- startedAt
- finishedAt

Metric
- id
- testRunId
- name
- value
- timestamp

Failure
- id
- testRunId
- type
- message
- timestamp
```

### Redis
Purpose:
- Job queue
- Temporary state
- Caching
- Distributed worker coordination

Important: Redis is NOT part of the first version.

### React + TypeScript
Purpose:
- Dashboard
- Test creation UI
- Test history
- Metrics visualization
- Failure timeline
- Logs view

Important: Frontend comes relatively late.

### Docker
Purpose:
- Reproducible environments
- Isolated client/server environments
- Repeatable tests
- Multi-container networking
- Easier CI/CD

### AWS / Cloud
Purpose later:
- Deployment
- Managed database
- Remote workers
- Monitoring
- Production-style architecture

Cloud should NOT be introduced before the local system works.

---

## 6. Initial Repository Structure

```text
netverify/
|
+-- networking/
|   +-- client/
|   |   +-- client.c
|   |
|   +-- server/
|       +-- server.c
|
+-- runner/
|   +-- Python automation code
|
+-- backend/
|   +-- Java Spring Boot application
|
+-- frontend/
|   +-- React + TypeScript application
|
+-- scripts/
|   +-- utility scripts
|
+-- docs/
|   +-- architecture.md
|   +-- decisions.md
|
+-- tests/
|
+-- docker/
|
+-- PROJECT_SPEC.md
+-- README.md
```

Not all directories must exist on day one.

---

# DEVELOPMENT ROADMAP

## Phase 0 — Environment and Repository
Goal: Create a clean development environment.

Tasks:
1. Use Linux through WSL2 / Ubuntu.
2. Create the `netverify` Git repository.
3. Confirm GCC is installed.
4. Confirm Python is installed.
5. Confirm Java is installed.
6. Confirm Git works.
7. Add README.md.
8. Add PROJECT_SPEC.md.

Definition of Done:
- Repository exists.
- `git status` works.
- C compiler works.
- Project can be opened in Cursor.
- First Git commit exists.

---

## Phase 1 — Minimal TCP Client and Server in C
Goal: Understand and implement the TCP lifecycle manually.

Server lifecycle:
```text
socket() -> bind() -> listen() -> accept() -> recv() -> send() -> close()
```

Client lifecycle:
```text
socket() -> connect() -> send("PING") -> recv("PONG") -> close()
```

Tasks:
1. Implement a TCP server in C.
2. Server listens on localhost and a configurable port.
3. Implement a TCP client in C.
4. Client connects to the server.
5. Client sends `PING`.
6. Server responds `PONG`.
7. Add proper return-value checking.
8. Print useful error messages.
9. Close sockets correctly.
10. Add a simple Makefile.

Expected demo:
```text
Terminal 1:
./net_server --port 9000

Terminal 2:
./net_client --host 127.0.0.1 --port 9000

Output:
Client -> PING
Server -> PONG
```

Definition of Done:
- Client and server compile with warnings enabled.
- PING/PONG works.
- Errors are handled.
- No obvious resource leaks.
- Code is explained and understood by the developer.

Topics to understand before moving on:
- TCP connection
- IP address
- port
- socket
- file descriptor
- bind
- listen
- accept
- connect
- send/recv
- blocking calls
- errno

---

## Phase 2 — Protocol and Multiple Messages
Goal: Move from a one-message demo to a small test protocol.

Possible message fields:
- sequence number
- timestamp
- message type
- payload length

Tasks:
1. Send many messages per connection.
2. Add sequence numbers.
3. Detect missing/unexpected sequence numbers.
4. Record send/receive timestamps.
5. Add clean connection shutdown.
6. Define a simple text or binary protocol.

Metrics:
- messages sent
- messages received
- malformed messages
- connection duration

Definition of Done:
- One client can exchange hundreds/thousands of messages reliably.
- Results are measurable and logged.

---

## Phase 3 — Multiple Clients and Concurrency
Goal: Test multiple clients at the same time.

Tasks:
1. Allow server to serve multiple clients.
2. Start with a thread-per-client approach if helpful.
3. Add synchronization where needed.
4. Test with 10 clients, 100 clients, and more later.
5. Track active connections.

Concepts:
- pthreads
- race conditions
- mutex
- shared state
- thread safety
- resource ownership

Later improvement:
- select()
- poll()
- epoll()

Definition of Done:
- Multiple clients work concurrently.
- Server does not crash under normal load.
- Shared counters are correct.

---

## Phase 4 — Structured Logging and Metrics
Goal: Make failures debuggable.

Example logs:
```text
2026-09-01T14:23:01 CLIENT_17 CONNECTED
2026-09-01T14:23:04 CLIENT_17 SEND seq=184
2026-09-01T14:23:05 CLIENT_17 TIMEOUT seq=184
2026-09-01T14:23:05 CLIENT_17 RETRY
2026-09-01T14:23:06 CLIENT_17 RECONNECTED
```

Metrics:
- connection attempts
- successful connections
- failed connections
- messages sent
- messages received
- bytes sent
- bytes received
- average latency
- min latency
- max latency
- P95 latency later
- throughput
- disconnect count
- reconnect count
- timeout count

Definition of Done:
- A failed run has enough information to investigate what happened.

---

## Phase 5 — Python Test Automation
Goal: Stop running tests manually.

Python runner responsibilities:
1. Launch server process.
2. Wait until server is ready.
3. Launch N clients.
4. Capture stdout/stderr.
5. Wait for test duration.
6. Stop/cleanup processes.
7. Parse result files/logs.
8. Decide PASS/FAIL.
9. Generate a test report.

Possible structure:
```text
runner/
  run_scenario.py
  process_manager.py
  result_parser.py
  assertions.py
  models.py
```

Example tests:
- test_basic_connection
- test_10_clients
- test_100_clients
- test_server_restart
- test_client_reconnect
- test_timeout
- test_invalid_message

Definition of Done:
One command can run a complete test and produce a report.

---

## Phase 6 — Failure Injection
Goal: Test the system under bad network conditions.

Linux tools:
- `tc`
- `netem`

Possible failure scenarios:
- Packet loss
- Latency
- Jitter
- Reordering
- Duplication
- Disconnect / server crash
- Network recovery

Important:
Failure-injection commands may require elevated permissions. Do not execute destructive/system-wide commands automatically without understanding them.

Definition of Done:
- Python can configure at least two network failure types and verify expected behavior.

---

## Phase 7 — Stress and Performance Testing
Goal: Measure behavior under load.

Scenarios:
- 1 client
- 10 clients
- 100 clients
- 500 clients
- 1000 clients if machine permits

Measure:
- throughput
- latency
- CPU
- memory
- errors
- connection rate
- recovery time

Definition of Done:
- There is at least one reproducible benchmark.

---

## Phase 8 — Java Spring Boot Control Plane
Goal: Turn the test framework into a software platform.

**Status: COMPLETE**

What existed under `control-plane/` at Phase 8 completion (Java 21 + Maven + Spring Boot):
- In-memory persistence only at that time (latest state/result per test)
- REST API that creates tests and starts runs asynchronously
- Bounded Java executor + `ProcessBuilder` launching `automation/runner.py --json`
- Existing C `net_server` / `net_client` driven by the Python runner
- No Redis, Docker, React, AWS, or distributed workers yet

HTTP endpoints (Phase 8 base; Phase 9 adds history — see below):
```text
POST /api/tests
GET  /api/tests
GET  /api/tests/{id}
POST /api/tests/{id}/run
GET  /api/tests/{id}/results
GET  /api/tests/{id}/logs
GET  /api/tests/{id}/metrics
```

Example create request:
```json
{
  "name": "demo",
  "scenario": "basic_ping_pong",
  "count": 10
}
```

HTTP-allowed scenarios (non-privileged):
- `basic_ping_pong`
- `connect_fail_no_server`
- `server_crash_mid_session`
- `concurrent_load`

`latency_injection` is intentionally **not** exposed through HTTP because it
requires privileged `tc`/`netem` operations (still available via the Python CLI
with sudo).

Execution flow:
```text
HTTP request
  -> Spring Boot control plane
  -> bounded Java executor
  -> ProcessBuilder
  -> automation/runner.py --json
  -> existing C server/client
  -> parsed PASS/FAIL result
```

Lifecycle statuses:
- CREATED
- QUEUED
- RUNNING
- PASSED
- FAILED
- CANCELLED

Failure categories:
- VERIFICATION
- SETUP
- LAUNCH
- TIMEOUT
- PARSE

Configuration:
- `NETVERIFY_REPO_ROOT` (overrides `netverify.repo-root`)
- `netverify.runner-timeout-seconds` (default 120)
- `netverify.python-command` (default `python3`)

Backend responsibilities implemented:
- validate input (whitelist scenarios; reject privileged ones)
- create test configuration in memory
- queue and track async runs
- launch the Python runner safely (timeout + process-tree cleanup)
- return status, results, logs, and metrics

Definition of Done:
- ✅ A test can be created and started through an HTTP API.

---

## Phase 9 — PostgreSQL Persistence
Goal: Persist tests and results.

**Status: COMPLETE**

### Architecture

```text
TestService
  → TestRepository          (immutable definitions)
  → RunLifecycleStore       (run lifecycle + history)
```

| Profile | Definitions | Runs |
|---------|-------------|------|
| default (DB-free) | `InMemoryTestRepository` | `InMemoryRunLifecycleStore` |
| `postgres` | `JdbcTestRepository` | `JdbcRunLifecycleStore` → `TestRunRepository` |

The old `InMemoryTestStore` lifecycle was retired. Stack: Spring JDBC / `JdbcTemplate`, Flyway **V1** (`tests` + `test_runs`), PostgreSQL JSONB for heterogeneous `runner_result`, relational `failure_category`. No JPA. No V2 migration.

PostgreSQL profile env (never commit real values):
- `NETVERIFY_DB_URL`
- `NETVERIFY_DB_USER`
- `NETVERIFY_DB_PASSWORD`

### Milestones

**M1 — Foundation**
- JDBC / Flyway / postgres profile wiring
- Schema: `tests`, `test_runs`, indexes (including partial unique one-active-run)
- Repository interfaces + JDBC implementations

**M2 — Durable definitions**
- `TestRepository` as immutable definition source of truth
- Create/list/get compose definition + latest run (CREATED when no runs)
- List ordering: `created_at ASC`, then canonical UUID text ASC

**M3 — Persisted run lifecycle**
- Each execution has its own run UUID
- `QUEUED → RUNNING → PASSED | FAILED` via conditional SQL
- Executor rejection: `QUEUED → FAILED` (`LAUNCH`); releases active slot
- At most one active (`QUEUED`/`RUNNING`) run per test (partial unique index)
- Startup recovery: orphan `QUEUED`/`RUNNING` → `CANCELLED` (single-instance assumption; no multi-instance leases/coordination)
- Latest results/logs/metrics survive restart under `postgres`

**M4 — History + closure**
- `GET /api/tests/{id}/runs` compact newest-first history
- Ordering: `queued_at DESC`, run UUID canonical text `DESC`
- In-memory path rewritten as real multi-run `InMemoryRunLifecycleStore`
- POST `/run` remains backward-compatible (`id` = test id); run UUID via history only
- No `GET /runs/{runId}`; no history pagination yet (scale limitation)

### History endpoint

```text
GET /api/tests/{id}/runs
```

- unknown test → 404
- known, zero runs → 200 `[]`
- otherwise compact newest-first entries: `id` (run UUID), `testId`, `status`,
  `queuedAt`, `startedAt`, `finishedAt`, `exitCode`, `failureCategory`
- omits `stdout` / `stderr` / `runnerResult` (latest-only detail endpoints keep those)

Existing `GET /api/tests/{id}`, `/results`, `/logs`, `/metrics` remain latest-run oriented.

### Restart semantics

Under `postgres`: definitions, full run history, and latest results/logs/metrics survive backend restart; a persisted test can be run again. Default in-memory profile is process-local and does not survive restart.

### Verification

- Full suite: **114** tests
- DB-free: PostgreSQL integration tests skipped as expected (opt-in via `NETVERIFY_DB_*`)
- PostgreSQL + real runner (`NETVERIFY_DB_*` + `NETVERIFY_REPO_ROOT`): **114** tests, 0 failures, 0 errors, 0 skipped
- Real HTTP restart verification: two distinct persisted run IDs, history ordering, identical history/results/logs/metrics after JVM restart

Definition of Done:
- ✅ Restarting the backend does not lose test history (postgres profile).

---

## Phase 10 — Queue and Distributed Workers
Goal: Make test execution asynchronous and scalable across separate API and worker processes.

**Status: COMPLETE**

### Design rationale

- **Redis Streams** delivers run messages (lightweight, consumer groups, PEL, XAUTOCLAIM).
- **PostgreSQL** remains authoritative for definitions, lifecycle, results, and **execution ownership** (`worker_id` + lease fencing).
- Separating transport from ownership avoids treating queue delivery as permission to run Python.
- Crash semantics are explicit and deterministic; Phase 10 does **not** claim generic exactly-once messaging.

### Distributed architecture

```text
HTTP client
    → Spring API process (same JAR, role=api)
    → PostgreSQL (authoritative state)
    → Redis Stream (transport: runId + testId)
    → Spring worker process (same JAR, role=worker, web=none)
    → Python runner (automation/runner.py)
    → C net_server / net_client
```

| Process | Profiles / flags | Responsibilities |
|---------|------------------|------------------|
| API | `postgres,redis`, `netverify.role=api`, web enabled | HTTP API; write PG; publish Redis; **never** execute Python |
| Worker | `postgres,redis`, `netverify.role=worker`, `spring.main.web-application-type=none`, nonblank `NETVERIFY_WORKER_ID` | Consume Redis; PG claim/fence; execute Python/C; no HTTP API |

Multiple workers share one Redis consumer group and must use **distinct** worker IDs.

### Queue message contract

Redis Stream fields (exactly):
- `runId`
- `testId`

PostgreSQL stores authoritative test/run state and full terminal result (stdout/stderr/metrics/`failure_category`).

### Delivery / ownership semantics

**Redis (transport):**
- At-least-once delivery via consumer group / PEL / XAUTOCLAIM
- Redis consumer ownership does **not** authorize execution

**PostgreSQL (authority):**
- Successful PG claim authorizes execution
- `worker_id` + lease fencing (`renew` / `complete` require correct owner, `RUNNING`, non-null unexpired lease)
- Terminal PG state wins over duplicate or redelivered Redis messages

Accurate guarantee: duplicate Redis delivery is possible; PostgreSQL fencing prevents a second effective execution of the same active run in the verified scenarios.

### Lease model (defaults)

| Parameter | Value | Notes |
|-----------|-------|-------|
| Initial / renew PG lease horizon | 120 seconds | Claim and heartbeat renewals |
| Heartbeat interval | 30 seconds | Shared scheduler per worker JVM |
| Redis XAUTOCLAIM min-idle | 150 seconds | Intentionally **greater than** 120s |

Expired `RUNNING` recovery:
- Does **not** re-run Python
- Becomes `FAILED` with `failureCategory=TIMEOUT`
- Redis message is ACKed after successful reclaim

`RUNNING_WITHOUT_LEASE`: never stolen automatically; no ACK; known unsupported recovery state (not auto-repaired in Phase 10).

### Crash / recovery matrix

| Window | What happens | Outcome |
|--------|--------------|---------|
| **A** Redis delivered, die before PG claim | Redis pending; PG still `QUEUED`; later XAUTOCLAIM | New worker PG-claims and executes once |
| **B** Die after PG claim / during Python | PG stays `RUNNING` while lease valid; peers cannot execute | After expiry + redelivery → reclaim `FAILED`/`TIMEOUT`; no automatic rerun |
| **C** PG terminal committed, die before Redis ACK | Message stays pending | Redelivery → `ALREADY_TERMINAL` → ACK only; no duplicate execution |
| **D** Multiple healthy workers | Shared group; distinct worker IDs | Different jobs on different workers; PG fencing blocks concurrent duplicate execution of the same run |
| **E** Worker restart | Same configured `NETVERIFY_WORKER_ID` may be reused | Restart does **not** bypass a still-valid PG lease; after expiry, reclaim/`TIMEOUT` or follow terminal/redelivery rules |

Verified opt-in ITs: `WorkerCrashRecoveryIntegrationTest` (A/B/C), `WorkerMultiWorkerIntegrationTest` (D1/D2/E), plus `WorkerRunLifecycleIntegrationTest` and `RedisRunQueueIntegrationTest`. Real M3 smoke: API queued while worker offline; later worker consumed; result persisted; restart did not duplicate a terminal run.

### Milestone summary (M1–M4)

- **M1** — Redis Streams queue foundation (`RunQueue`, publish/read/ACK, consumer group)
- **M2** — Worker PG lease/fencing boundary (`claim` / `renew` / `complete` / `reclaimExpiredRunning`)
- **M3** — Distributed API + worker runtime (`role=api|worker`, `RedisRunWorker`, `WorkerMessageProcessor`)
- **M4** — Crash recovery + multi-worker verification (ITs + this documentation)

### Accepted Phase 10 limitations (deliberate scope boundaries)

- DB → Redis is a **dual write**, not transactional
- Crash after PG `QUEUED` commit but before Redis publish can leave an orphan `QUEUED` run
- No transactional outbox
- No stale-`QUEUED` republisher
- No DB-wide lease sweeper
- `RUNNING_WITHOUT_LEASE` is not automatically repaired
- Externally deleted/lost Redis messages are not reconstructed
- Expired `RUNNING` is terminal `TIMEOUT`, not automatic retry
- Worker read/claim batch size remains **1**
- Postgres-only startup cancel-all recovery remains a **single-instance** path (`postgres & !redis`)
- Docker / React / AWS remain later phases

### Operational launch (placeholders only)

Worker only: `NETVERIFY_REPO_ROOT` must point to the NetVerify repository root containing `automation/runner.py`, `net_server`, and `net_client`.

API:
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

Worker:
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

Interview questions answered by this phase:
- What happens if a worker crashes in the middle? → Matrix A–E above
- How do you prevent the same test from running twice? → PG claim/lease fencing (not Redis alone)
- How do you retry safely? → No automatic TIMEOUT retry in Phase 10; terminal/redelivery follow PG
- How do you know a worker is alive? → Lease heartbeat renewals (30s / 120s horizon)

Definition of Done:
- ✅ Backend API does not block on Python execution in distributed mode (`role=api`).
- ✅ A worker can independently consume and run a test job (`role=worker`).
- ✅ Crash windows A–C and multi-worker/restart D–E verified with opt-in PG+Redis ITs.
- ✅ Limitations and operational launch commands documented.

---

## Phase 11 — Docker
Goal: Make the Phase 10 distributed stack reproducible with Docker while preserving Phase 10 behavior.

**Status: COMPLETE**

### Design choices

- One **application image** (multi-stage `Dockerfile`) for both API and worker roles; Compose supplies different command/env.
- Official **`postgres:18`** and **`redis:8`** services; C binaries are **not** separate Compose services — they live inside the worker image and are spawned by `automation/runner.py`.
- Phase 10 semantics unchanged: Redis = at-least-once transport; PostgreSQL = authoritative state / execution ownership; no generic exactly-once; no transactional outbox; no stale-`QUEUED` republisher; expired `RUNNING` → `FAILED`/`TIMEOUT`.

### Docker architecture

```text
HTTP client
    → api container          (netverify-app image, role=api)
    → PostgreSQL + Redis     (postgres / redis service DNS)
    → worker container       (same image, role=worker)
    → Python runner
    → C net_server / net_client
```

| Service | Image | Notes |
|---------|-------|-------|
| `postgres` | `postgres:18` | Named volume `netverify-postgres-data`; `pg_isready` healthcheck |
| `redis` | `redis:8` | `redis-cli ping` healthcheck; no persistence required in Phase 11 |
| `api` | `netverify-app:local` (build `.`) | `postgres,redis`, `role=api`, host `:8080`; no `NETVERIFY_REPO_ROOT` |
| `worker` | same app image | `role=worker`, web=none, `NETVERIFY_WORKER_ID=worker-1`, `NETVERIFY_REPO_ROOT=/opt/netverify` |
| `worker-2` | same app image | Optional; Compose profile `multiworker`; distinct `NETVERIFY_WORKER_ID=worker-2` |

In-image worker layout: `/opt/netverify/{automation/,net_server,net_client,app.jar}`. Cross-container deps use hostnames `postgres` / `redis` — never `localhost`.

### Milestone summary (M1–M3)

- **M1** — Application image: multi-stage build (C via `Makefile`, Maven fat JAR, Jammy-aligned glibc); JRE 21 + `python3`; non-root user; exec-form Java `ENTRYPOINT`
- **M2** — Compose stack: postgres/redis/api/worker; healthchecks + `depends_on: service_healthy`; `.env.example`; `.env` gitignored
- **M3** — Optional `worker-2` behind profile `multiworker` + real Docker verification (below)

### Developer workflow

```bash
cp .env.example .env          # never commit real .env / passwords
docker compose up --build -d
docker compose ps
docker compose logs -f api worker
docker compose down           # keeps PostgreSQL named volume
# docker compose down -v      # intentionally deletes the volume
```

Optional second worker (explicit service — not blind `scale`; worker IDs are PG lease owners and Redis consumer names):

```bash
docker compose --profile multiworker up -d worker-2
```

### Verified Docker smoke

1. Default stack: healthy postgres/redis; api on `:8080`; headless `worker-1`
2. HTTP `basic_ping_pong`: create → `QUEUED` → `PASSED`, `exitCode=0`, runner metrics; PG `worker_id=worker-1`, `lease_expires_at` cleared
3. Restart `worker-1`: still one `test_run` (same UUID), still `PASSED` — no duplicate
4. Multi-worker: stop `worker-1`, start `worker-2` via profile; new run `PASSED` with `worker_id=worker-2`
5. `docker compose down` preserves volume `netverify_netverify-postgres-data`

Definition of Done:
- ✅ A new machine can start the system using documented Docker commands.
- ✅ Same application image runs API and worker with role-specific Compose config.
- ✅ Real Docker smoke (create/run/terminal, worker restart, optional worker-2) verified.
- ✅ Phase 10 queue/lease semantics preserved; React/AWS not started.

---

## Phase 12 — React + TypeScript Dashboard
Goal: Visualize the system through a lightweight dashboard over the existing REST API.

**Status: COMPLETE**

### What shipped (aligned with the real API)

Stack: React + TypeScript + Vite under `frontend/`; React Router; native `fetch`; no Redux / React Query / chart library.

Dashboard capabilities:
- List tests; create tests (`name`, `scenario`, `count`, optional `clients` for `concurrent_load`)
- HTTP scenarios only: `basic_ping_pong`, `connect_fail_no_server`, `server_crash_mid_session`, `concurrent_load` (not `latency_injection`)
- Test detail; trigger run; poll `QUEUED` / `RUNNING` until terminal
- Run history from `GET /api/tests/{id}/runs`
- Latest result / metrics / logs; generic heterogeneous metrics renderer
- Section-specific errors; **409** as artifact-not-ready; stale artifacts hidden during a new active run

### Milestone summary (M1–M4)

- **M1** — Vite foundation, API client, test list, Vite `/api` → `:8080` proxy
- **M2** — Create form, detail page, Run, ~2s status polling with AbortController cleanup
- **M3** — History + result + metrics + logs panels
- **M4** — `frontend/Dockerfile` (`node:24-alpine` → `nginx:1.28-alpine`), Compose `dashboard` on `:3000`, nginx `/api/` → `http://api:8080`, docs + smoke

### Docker dashboard architecture

```text
Browser (:3000)
    → nginx (static React + SPA try_files)
         → /api/* → api:8080 (Compose DNS)
              → Redis → worker → Python → C
              → PostgreSQL
```

Same-origin `/api` via nginx → **no Spring CORS** change. API still on host `:8080` for debugging. Brief **502** possible until API is up (`depends_on: service_started`).

### Verified smoke

Containerized dashboard create/run `basic_ping_pong` → PASSED; metrics/logs present; deep link `/tests/<uuid>` returns `index.html`; `compose down` keeps PG volume; direct API `:8080` still works.

### Deferred (explicit non-goals of Phase 12)

- Charts / time-series visualization
- Authentication
- WebSocket / live log streaming
- Run-specific artifact APIs (`GET /runs/{runId}`)
- AWS / deployment CD (Phase 14); charts/auth/live logs remain deferred

Definition of Done:
- ✅ A user can create a test and understand its result without using the terminal.
- ✅ Dashboard runs locally (Vite) and in Compose (nginx `:3000`).
- ✅ Backend semantics unchanged; no CORS required for Compose dashboard.

---

## Phase 13 — CI/CD
Goal: Treat the project like production software.

**Status: COMPLETE (CI portion)**

Deployment / CD is **intentionally deferred**. Cloud deployment belongs to **Phase 14 — Cloud / AWS**.

### What shipped

Workflow: `.github/workflows/ci.yml`

Triggers: push to `main`, pull requests

Runner: `ubuntu-24.04`

Actions: `actions/checkout@v7`, `actions/setup-java@v6` (Temurin 21 + Maven cache), `actions/setup-node@v7` (Node 24 + npm cache)

| Job | Purpose |
|-----|---------|
| `c-python` | `make` + `python3 automation/runner.py --all` |
| `java` | DB/Redis-free `mvn -B test` (gated ITs skip) |
| `frontend` | `npm ci` / lint / build |
| `java-integration` | Postgres 18 + Redis 8 service containers; `make` + env-gated `mvn -B test` |
| `docker` | Build `netverify-app:ci` + `netverify-dashboard:ci`; `compose config` with ephemeral `.env` from `.env.example` |

### Milestone summary (M1–M4)

- **M1** — Baseline CI (`c-python`, `java`, `frontend`)
- **M2** — `java-integration` with Postgres/Redis services + `NETVERIFY_*` / `NETVERIFY_REPO_ROOT`
- **M3** — Docker image builds + Compose config validation (no `compose up`)
- **M4** — Documentation / Phase 13 closure

### Verified results

- Full five-job GitHub Actions run green
- `java-integration`: **248** tests, **0** failures, **0** errors, **0** skipped (includes real Python runner, PG persistence, Redis queue, multi-worker, crash-recovery)

### Explicit exclusions

- `latency_injection` / sudo `tc`/`netem`
- Full Compose browser/runtime smoke (local Phase 11/12 remains authoritative E2E)
- Deployment / CD
- AWS
- Registry image publishing
- Production secrets (CI DB password is ephemeral and non-secret)

Definition of Done:
- ✅ Push/PR automatically validate C build, safe Python `--all`, Java, and frontend
- ✅ Real PostgreSQL/Redis integrations execute in CI
- ✅ Docker images build; Compose config validates
- ✅ Privileged/manual tests remain excluded
- ✅ No deployment / AWS implemented yet

---

## Phase 14 — Cloud / AWS
Only after the local system is stable.

**Status: IN PROGRESS / DEPLOYMENT DEFERRED**

Phase 14 is **not** fully COMPLETE. Offline Terraform under `infra/terraform/` defines the portfolio AWS architecture through M4d. There is **no** AWS account in use, **no** `terraform plan`/`apply` has been run, and **no** cloud resources exist.

### Architecture (designed, not deployed)

```
Internet
  -> public ALB (HTTP :80)
  -> dashboard Fargate (public subnet + public IP)
       -> API via ECS Service Connect (api:8080)
API + worker Fargate (public subnets + public IPs)
  -> private RDS PostgreSQL
  -> private ElastiCache Redis
```

| Plane | Placement |
|-------|-----------|
| ECS tasks (dashboard, API, worker) | Public subnets, `assign_public_ip = true`, **no NAT Gateway** |
| RDS + ElastiCache | Private subnets only |
| Public ingress | ALB TCP/80 only |
| Worker | No ingress; not on ALB |
| API | Service Connect only (not attached to public ALB) |
| RDS / Redis | Ingress from API + worker security groups only |

IAM: ECS **task role** has no application AWS permissions. ECS **execution role** has the managed task-execution policy plus scoped `ssm:GetParameters` on a future DB-password parameter ARN.

### Milestone summary (M1–M4d completed offline)

- **M1** — Terraform foundation (versions, provider, variables, local state)
- **M2a** — ECR repositories (`${project}-app`, `${project}-dashboard`; IMMUTABLE tags; scan on push; AES256)
- **M3a** — VPC networking (2 AZs; 2 public + 2 private subnets; IGW; public + private route tables; no NAT)
- **M3b** — Security groups + RDS/ElastiCache subnet groups (private subnets)
- **M3c** — RDS PostgreSQL + ElastiCache Redis OSS definitions
- **M4a** — ECS cluster, execution + task IAM roles, CloudWatch log groups (7-day retention), Service Connect HTTP namespace
- **M4b** — Dashboard / API / worker Fargate task definitions (Compose runtime contract; DB password via ECS `secrets`)
- **M4c** — Public ALB + ECS services + Service Connect (`api:8080` for nginx)
- **M4d** — Offline deployment-readiness audit + documentation checkpoint

Offline verification recorded: `terraform fmt -check`, `terraform validate`, `git diff --check`.

### Secrets contract

- No real secret is committed (`*.tfvars` gitignored; example uses placeholders).
- RDS currently takes `var.db_password` (sensitive Terraform variable).
- ECS will take `NETVERIFY_DB_PASSWORD` from an **external** SSM SecureString (`var.db_password_parameter_arn`).
- Those values **must be synchronized** before real deployment.
- After a future apply, sensitive Terraform values may exist in Terraform state (currently **local** state only; remote backend deferred).

### Cost-aware portfolio choices

AWS is **not** free. Exact pricing is unverified. Design choices that limit spend:

- no NAT Gateway
- single-AZ RDS; single-node Redis (no failover / Multi-AZ)
- one worker (`desired_count = 1`, fixed `NETVERIFY_WORKER_ID`)
- no autoscaling
- short CloudWatch retention (7 days)
- HTTP-only baseline (no ACM / HTTPS yet)
- teardown-friendly settings (e.g. `deletion_protection = false`, `skip_final_snapshot = true`, ECR `force_delete`)

### Deferred / blockers (before real deployment)

- no AWS account / credentials
- no real ECR image push
- external SSM SecureString not created
- regional engine/class compatibility not API-verified
- no `terraform plan` / `apply`
- no ALB / Service Connect runtime verification
- no end-to-end cloud smoke
- no GitHub OIDC CD
- no HTTPS / ACM / Route53
- local Terraform state only

### Resume Phase 14 checklist

1. Create/secure AWS account.
2. Verify region/service compatibility.
3. Establish secret/bootstrap strategy (same password for RDS var + SSM SecureString).
4. Build/push amd64 images with immutable tags.
5. Run first `terraform plan` and review cost/security.
6. Controlled `apply`.
7. Run distributed `basic_ping_pong` smoke via the ALB.
8. Add GitHub OIDC/CD only after manual deployment works.

Possible later components (still deferred): object storage for large reports; HTTPS/domain; remote state/locking; worker scale-out with distinct IDs.

Do NOT treat offline Terraform as a live cloud system.

---

## Phase 15 — Optional AI Failure Analysis
AI is a feature, not the project.

Input:
- test configuration
- logs
- counters
- metrics

Output:
- probable root cause
- interesting anomalies
- suggested investigation steps

Rules:
- AI must not decide ground-truth PASS/FAIL alone.
- Deterministic assertions remain the source of truth.
- AI assists debugging and explanation.

---

# 23. Testing Strategy

## Unit Tests
Test individual functions/classes.

## Integration Tests
Examples:
- client ↔ server
- Java ↔ database
- backend ↔ queue

## Functional Tests
Does the system behave according to requirements?

## Regression Tests
A fixed bug gets a test so it cannot silently return.

## Stress Tests
Large number of connections/messages.

## Performance Tests
Measure latency, throughput, CPU, memory.

## Negative Tests
Invalid messages, wrong ports, disconnected server, malformed configuration.

---

# 24. Coding and Engineering Rules

1. Do not allow AI to build the entire project in one prompt.
2. Implement one small milestone at a time.
3. Understand every important piece of code before committing it.
4. Every new dependency must have a reason.
5. Every major architectural decision should be documented.
6. Use meaningful Git commits.
7. Write tests for important behavior.
8. Do not add Redis, Docker, React, or AWS before they solve an actual problem.
9. Prefer simple architecture first.
10. Refactor only after functionality is understood and tested.
11. Compile C code with warnings enabled.
12. Check every important system-call return value.
13. Clean up resources correctly.
14. Never hide errors just to make tests pass.
15. Keep README and PROJECT_SPEC up to date.

---

# 25. Git Strategy

Main branch:
`main`

Feature branch examples:
- feature/tcp-server
- feature/python-runner
- feature/failure-injection
- feature/spring-api

Commit examples:
- `feat: add basic TCP server`
- `feat: implement PING/PONG client`
- `test: add basic connection test`
- `feat: automate test execution with Python`
- `fix: close client socket on connection failure`
- `docs: document TCP test architecture`

Avoid vague commits such as:
- update
- stuff
- final
- fix things

---

# 26. Documentation to Maintain

`docs/architecture.md`
- high-level architecture
- component responsibilities

`docs/decisions.md`
- important decisions and why

`README.md`
- what the project is
- how to build
- how to run
- demo
- screenshots later

`PROJECT_SPEC.md`
- master plan
- should stay stable but can evolve

---

# 27. What the Developer Should Be Able to Explain in an Interview

## C / Linux
- TCP socket lifecycle
- blocking vs non-blocking I/O
- file descriptors
- error handling
- memory ownership
- threads
- synchronization

## Networking
- TCP vs UDP
- handshake
- reliability
- retransmission
- packet loss
- latency
- ports
- connection lifecycle

## Python
- how the test runner launches processes
- how logs are captured
- how tests determine PASS/FAIL
- how failures are injected
- how automation reduces manual verification work

## Java
- controller/service/repository separation
- dependency injection
- REST API design
- validation
- transactions
- exception handling
- concurrency

## Distributed Systems
- queues
- workers
- retries
- timeouts
- idempotency
- duplicate work
- worker failure
- horizontal scaling

## Database
- schema
- indexes
- transactions
- historical results

## DevOps
- Docker
- CI/CD
- reproducible environments
- deployment

---

# 28. Project Elevator Pitch

Short version:

> I built NetVerify, a network verification and test automation platform. It uses C for low-level TCP traffic components, Python for automated functional, stress, and failure-injection testing, and Java/Spring Boot for test orchestration and result management.

Longer version:

> NetVerify is a network verification platform I built to automate testing of connection-oriented network behavior. The low-level client/server components are written in C using Linux sockets. Python orchestrates test scenarios, launches processes, injects failures such as packet loss and latency, parses logs, and produces PASS/FAIL results. A Java Spring Boot control plane manages test jobs with optional PostgreSQL durability for definitions, run history, and results. Optional Redis Streams deliver work to separate worker processes; PostgreSQL remains authoritative for execution ownership and lease fencing. Docker Compose reproduces the API/worker stack with official PostgreSQL and Redis, plus an nginx-served React dashboard that proxies same-origin `/api` to the API. The project is designed to explore networking, Linux, concurrency, debugging, automation, distributed systems, and operational reliability.

---

# 29. Instructions for Cursor / OpenCode / AI Assistants

When an AI assistant works on this project, it MUST follow these rules:

1. Read this entire file first.
2. Ask what the CURRENT PHASE is if it is not provided.
3. Do not jump to later phases.
4. Do not build the entire project automatically.
5. Before changing code, explain:
   - what will change
   - why
   - which files will change
6. Prefer small, reviewable changes.
7. Never introduce a dependency without explaining why it is needed.
8. When working on C:
   - check system-call return values
   - explain memory/resource ownership
   - avoid unsafe string handling
   - compile with warnings
9. When working on Python:
   - keep automation modular
   - separate process management, parsing, and assertions
10. When working on Java:
   - use clean controller/service/repository separation
   - use DTO validation
   - avoid unnecessary abstractions
11. Do not use AI-generated code that the developer cannot explain.
12. After each implementation:
   - explain the code
   - show how to test it
   - mention likely bugs/corner cases
13. Ask for permission before destructive system/network commands.
14. Preserve the educational goal of the project.

---

# 30. Prompt to Start a New AI Conversation

Copy/paste this when starting a new ChatGPT, Cursor, Claude, or OpenCode session:

```text
I am building a portfolio project called NetVerify.

Read PROJECT_SPEC.md before doing anything.

NetVerify is a network verification and test automation platform. C is used for low-level TCP client/server components, Python for test automation and failure injection, and Java/Spring Boot for the control plane (optional PostgreSQL persistence for definitions and run history; optional Redis Streams + distributed API/worker roles; Docker Compose for the reproducible stack including an nginx React dashboard). GitHub Actions CI validates the stack; deployment/CD and AWS come later.

This is an educational Software Engineering project. Do not build the whole project for me. Work one milestone at a time, explain architectural decisions, and make sure I can understand and explain the code in an interview.

Before changing files, tell me:
1. What phase we are currently in.
2. What small goal we are implementing.
3. Which files will change.
4. How we will test the change.

Current phase: [WRITE CURRENT PHASE HERE]
Current status: [WRITE WHAT ALREADY WORKS HERE]
Next goal: [WRITE THE NEXT SMALL GOAL HERE]
```

---

# 31. Progress Tracker

Update this section as the project advances.

Current phase:
`Phase 14 — Cloud / AWS (IN PROGRESS / DEPLOYMENT DEFERRED)`

Next:
`Resume Phase 14 — AWS account bootstrap, first plan/apply, cloud smoke`

Completed:

**Phase 0 — Environment and Repository**
- WSL2 installed
- Ubuntu installed
- Git installed
- Development environment prepared
- Cursor available
- OpenCode available
- NetVerify repository created
- PROJECT_SPEC.md added
- GCC verified

**Phase 1 — Minimal TCP Client and Server in C**
- Minimal TCP server in C
- Minimal TCP client in C
- bind/listen/accept
- PING/PONG send and receive
- Basic PING/PONG validation
- Makefile build and clean targets

**Phase 2 — Protocol and Multiple Messages**
- Newline-delimited TCP message framing
- PING <seq> / PONG <seq> protocol
- Strict sequence validation
- Multiple messages on one TCP connection
- Configurable client --count
- Explicit QUIT / BYE shutdown
- Robust send_all() (partial writes and EINTR)
- Client RTT measurement with CLOCK_MONOTONIC
- RTT min/max/avg summary
- Session metrics: messages_sent, messages_received,
  malformed_messages, connection_duration_us
- Successful 1000-message workload
- Malformed / unexpected sequence detection verified

**Phase 3 — Multiple Clients and Concurrency**
- Server accept loop for continuous client handling
- Detached pthread worker per client
- Safe client_fd ownership transfer to each worker
- Per-client protocol/session state remains isolated
- Multiple clients operate concurrently
- active_connections shared counter
- pthread mutex protects active_connections
- Centralized worker cleanup prevents missed/double decrement
- Verified 3 concurrent clients
- Verified 10 concurrent clients with peak active_connections = 10
- Verified 100 clients with zero client failures
- Server remains alive and accepts new clients after load

**Phase 4 — Structured Logging and Metrics**
- Structured UTC ISO8601 logs with millisecond precision
- Component field: CLIENT / SERVER
- Server-local session IDs C1, C2, ...; client-local C0
- Structured key=value events: CONNECT_ATTEMPT, CONNECT_OK,
  CONNECT_FAIL, CONNECT, ERROR, SESSION_END, DISCONNECT, SPAWN_FAIL
- Server log_mutex prevents concurrent log-line corruption
- server_state_mutex protects shared server state
- Centralized worker cleanup; exactly one SESSION_END and
  DISCONNECT per started worker
- Session metrics: messages_sent, messages_received,
  malformed_messages, connection_duration_us
- Client RTT min/max/avg
- bytes_sent, bytes_received
- throughput_msg_per_s, throughput_bytes_per_s
- active_connections tracking preserved
- Process-lifetime counters: total_connections_accepted,
  total_spawn_failures, total_sessions_ok, total_sessions_failed
- send_all() partial-send accounting, EINTR retry, MSG_NOSIGNAL
- Verified exact byte accounting (count=1)
- Verified malformed sequence byte and process-counter behavior
- Verified 10 concurrent clients with exact lifecycle counts
- Verified 100 clients × 1000 messages: zero failures,
  totals accepted/ok=100, spawn_failures/failed=0,
  active_connections returned to 0
- Deferred (behavior not implemented yet): timeout, reconnect,
  retry, and P95 latency metrics

**Phase 5 — Python Automation Layer**
- automation/runner.py, logparse.py, assertions.py (stdlib only)
- Runner starts net_server via subprocess.Popen
- Binaries resolved relative to project root
- Free localhost port selection
- Server readiness via /proc/net/tcp LISTEN polling
  (no TCP probe; does not pollute server metrics)
- Runs net_client; captures stdout/stderr
- Always terminates and reaps server (terminate → wait → kill)
- No orphaned net_server after automation runs
- Quote-aware Phase 4 log parsing with shlex
  (e.g. error_text="Connection refused")
- Non-NetVerify lines skipped without crashing the parser
- basic_ping_pong: client/server lifecycle, message counts,
  malformed_messages == 0, bytes, process counters,
  active_connections == 0; no C0↔C<n> correlation
- connect_fail_no_server: no server started; CONNECT_ATTEMPT,
  CONNECT_FAIL, no CONNECT_OK/SESSION_END; numeric error_code;
  non-empty parsed error_text
- CLI: --count, --test, --all, --json
- Exit 0 pass / 1 verification fail / 2 setup error
- TestResult with typed metrics; JSON numbers not strings
- Verified: count 1/10/100, JSON, quoted parser, --count 0 → exit 2,
  connect_fail_no_server, --all --count 50 (+ JSON)
- Intentionally excluded at Phase 5 close: pytest, parallel tests,
  malformed socket scenario, tc/netem, timeout/reconnect,
  Redis, Java, Docker, DB

**Phase 6 — Failure Injection**
- Two distinct injected failure/network-condition types

  latency_injection (opt-in; not in ordinary --all):
  - automation/netem.py (stdlib) controls tc/netem on lo
  - configured delay 100 ms; tc qdisc add (never replace)
  - refuses unsafe/custom existing qdiscs
  - privileged ops via non-interactive sudo -n
  - baseline then injected run; injection_count capped at 10
  - deterministic RTT-delta assertion
  - finally deletes qdisc only if this run installed it
  - cleanup verified by re-reading qdisc; cleanup_ok required
  - Verified: count=3 and count=10 JSON; RTT delta ~200 ms;
    lo returned to noqueue; missing sudo -n → exit 2, no change

  server_crash_mid_session (unprivileged; in --all):
  - CONNECT-log synchronization (no arbitrary sleep)
  - SIGSTOP client; verify stopped via /proc/<pid>/status
  - SIGKILL server while client active; SIGCONT client
  - client exits nonzero with non-bye SESSION_END
  - server SESSION_END/DISCONNECT not required after SIGKILL
  - all processes reaped; cleanup_ok verified
  - Verified: human + JSON PASS; no orphan net_server/net_client

- Runner tests: basic_ping_pong, connect_fail_no_server,
  latency_injection, server_crash_mid_session
- --all (as of Phase 6 close) was non-privileged:
  basic_ping_pong, connect_fail_no_server, server_crash_mid_session
- JSON and exit semantics preserved; Phase 5 tests still pass
- Deferred: packet loss (blocking client can wait forever without
  socket timeouts), jitter/reorder/duplication, timeout/reconnect

**Phase 7 — Stress and Performance Testing**
- Automated concurrent_load scenario (Python stdlib only)
- One net_server; N concurrent real net_client processes via
  subprocess.Popen (no shell=True, no shell background jobs)
- CLI: --clients (1..1000), --count = messages per client
- One shared monotonic load deadline (bounded; concurrency-aware;
  not proportional to clients × count as wall-clock timeout)
- Every client and server process reaped (terminate → wait → kill)
- Server stderr/stdout via TemporaryFile to avoid PIPE backpressure
  under large structured logs
- Order-independent deterministic correctness assertions:
  exact N client sessions, M/M/0 messages, N CONNECT /
  N SESSION_END / N DISCONNECT, process totals via any
  SESSION_END snapshot (not last-line assumption)
- peak_active_connections from CONNECT/DISCONNECT snapshots;
  peak >= 1 and active_connections returns to 0 required;
  peak == N is NOT required (sessions may complete before
  all clients overlap)
- Aggregate throughput and bytes measured and reported;
  not used as machine-specific PASS gates
- aggregate_bytes = sum of client-observed bytes_sent +
  bytes_received only (no double-counting with server)
- RTT: rtt_min_us, rtt_max_us, rtt_avg_of_avgs_us
  (average of per-client averages; not a global mean or P95)
- Observational Linux /proc metrics (informational only):
  server_vm_rss_kb, server_vm_hwm_kb, server_cpu_time_s
  (process CPU time from utime+stime / CLK_TCK; not % utilization)
- Server measured as-is (no pre-measurement optimization):
  listen backlog 10, detached pthread per client,
  recv one byte at a time
- Ordinary --all is non-privileged and includes:
  basic_ping_pong, connect_fail_no_server,
  server_crash_mid_session, concurrent_load smoke 10 × 100
  (fixed; ignores global --count/--clients for that entry)
- latency_injection remains explicit opt-in (sudo)
- JSON and exit semantics preserved; Phase 5/6 tests still pass

  Verified workloads (this environment; not universal guarantees):
  - 3 × 10: PASS, 3/3 clients, 30/30 messages, peak=3
  - 10 × 100: PASS, 10/10 clients, 1000 messages; regression
    observed peak=8
  - 10 × 1000: PASS, 10/10, 10000/10000 messages;
    duration ≈ 0.0565 s; ≈ 176978 msg/s; peak=10;
    server_cpu_time_s=0.12; server_vm_hwm_kb=2240
  - 100 × 1000 reproducible benchmark: PASS, 100/100,
    100000/100000 messages, zero client/server spawn/session
    failures; one JSON run: duration_s=0.356930847,
    aggregate_messages_per_s=280166.315,
    aggregate_bytes_per_s=4985559.57, peak=90,
    server_cpu_time_s=1.15, server_vm_hwm_kb=2876;
    later regression also PASS with peak=97
  - 500 × 100: PASS, 500/500, 50000/50000, peak=12, no failures
  - 1000 × 10: PASS, 1000/1000, 10000/10000, peak=4,
    no client/server failures, no orphan processes
  Low peaks on short high-N loads are expected: sessions finish
  before all requested clients are simultaneously active.
  Environment checked on the verification machine (open files
  10240, process/user limit 63315); not claimed portable.

- Deferred: P95 without raw RTT samples; absolute throughput SLOs;
  server optimization / epoll; buffered recv refactor;
  Redis / Docker / DB / React / AWS

**Phase 8 — Java Spring Boot Control Plane**
- Java 21 + Maven + Spring Boot module under control-plane/
- REST endpoints: POST/GET /api/tests, GET /api/tests/{id},
  POST /api/tests/{id}/run, GET /api/tests/{id}/results|logs|metrics
- HTTP-allowed scenarios: basic_ping_pong, connect_fail_no_server,
  server_crash_mid_session, concurrent_load
- latency_injection intentionally excluded from HTTP (privileged
  tc/netem); still runnable via Python CLI with sudo
- Async execution: HTTP → bounded ThreadPoolExecutor → ProcessBuilder
  → automation/runner.py --json → C net_server/net_client →
  parsed PASS/FAIL (RunnerResultDto mirrors runner JSON)
- Lifecycle: CREATED, QUEUED, RUNNING, PASSED, FAILED, CANCELLED
- Failure categories: VERIFICATION, SETUP, LAUNCH, TIMEOUT, PARSE
- Config: NETVERIFY_REPO_ROOT / netverify.repo-root,
  netverify.runner-timeout-seconds (default 120),
  netverify.python-command (default python3)
- Process safety: stdout/stderr capture to temp files, wall-clock
  timeout, descendant ProcessHandle cleanup (graceful then forcible)
- Validation + consistent JSON errors (400/404/409/503)
- Unit and Spring WebMvc integration tests; optional real-runner
  integration test when NETVERIFY_REPO_ROOT is set
- Definition of Done met: a test can be created and started through
  an HTTP API

**Phase 9 — PostgreSQL Persistence**
- COMPLETE (M1–M4)
- Flyway V1: `tests` + `test_runs`; Spring JDBC / JdbcTemplate; no JPA
- Profiles: default DB-free (`InMemoryTestRepository` +
  `InMemoryRunLifecycleStore`); `postgres` via NETVERIFY_DB_URL /
  NETVERIFY_DB_USER / NETVERIFY_DB_PASSWORD
- Definitions immutable in `tests`; every execution in `test_runs`
  with its own run UUID
- Lifecycle: QUEUED → RUNNING → PASSED/FAILED; rejection → FAILED/LAUNCH;
  startup orphan QUEUED/RUNNING → CANCELLED (single-instance;
  disabled when redis profile is active)
- One active run per test (partial unique index); conditional SQL
- GET /api/tests/{id}/runs compact newest-first history
  (queued_at DESC, run UUID text DESC); omits logs/result payloads
- POST /run still returns test id; latest results/logs/metrics unchanged
- Restart durability under postgres verified (history + latest views)
- Final verification: 114 tests; PG + real runner 114/0/0/0 skipped
- Deferred at Phase 9 close: Redis/distributed workers (now Phase 10),
  Docker, React, AWS; history pagination; GET /runs/{runId}

**Phase 10 — Queue and Distributed Workers**
- COMPLETE (M1–M4)
- M1: Redis Streams queue foundation (publish / consumer group /
  XREADGROUP / XAUTOCLAIM / ACK); message contract = `runId` + `testId`
- M2: PostgreSQL claim / renew / complete / reclaimExpiredRunning;
  worker_id + lease fencing; renew/complete require owner + RUNNING +
  unexpired lease; expired RUNNING → FAILED/TIMEOUT (no Python rerun)
- M3: Same JAR as API (`role=api`, web on) or worker (`role=worker`,
  web-application-type=none, nonblank NETVERIFY_WORKER_ID);
  API writes PG + publishes Redis, never runs Python; worker consumes,
  claims, executes Python/C; batch size 1
- M4: Crash windows A/B/C (`WorkerCrashRecoveryIntegrationTest`);
  multi-worker D + restart E (`WorkerMultiWorkerIntegrationTest`);
  docs + Definition of Done
- Semantics: Redis = at-least-once transport; PG = execution authority;
  not generic exactly-once messaging
- Lease defaults: PG 120s, heartbeat 30s, XAUTOCLAIM min-idle 150s
- Accepted boundaries: dual-write DB→Redis (no outbox); orphan QUEUED
  possible; no stale-QUEUED republisher; no DB-wide sweeper;
  RUNNING_WITHOUT_LEASE not auto-repaired; lost Redis messages not
  reconstructed; React/AWS remain later (Docker is Phase 11)
- Verified real M3 smoke: queue while worker offline; later consume;
  terminal result; restart did not duplicate terminal run

**Phase 11 — Docker**
- COMPLETE (M1–M3)
- M1: multi-stage application image (Makefile C build + Maven JAR +
  Jammy JRE/python3); `/opt/netverify` layout; non-root; exec-form
  Java ENTRYPOINT; same image for api and worker roles
- M2: Compose `postgres:18`, `redis:8`, `api`, `worker`; healthchecks +
  `depends_on: service_healthy`; named volume; `.env.example`;
  `.env` gitignored; service DNS `postgres`/`redis`
- M3: optional `worker-2` behind profile `multiworker` (distinct
  worker IDs; no blind scale); real Docker smoke verified
- Verified: default stack healthy; HTTP basic_ping_pong → PASSED on
  worker-1; worker-1 restart no duplicate run; worker-2 PASSED with
  ownership worker-2; `compose down` keeps PG volume
- Phase 10 lease/queue semantics preserved inside containers

**Phase 12 — React + TypeScript Dashboard**
- COMPLETE (M1–M4)
- M1: Vite React-TS foundation, API client, test list, Vite `/api` proxy
- M2: create/detail/run + QUEUED/RUNNING polling (AbortController)
- M3: run history + latest result/metrics/logs; generic metrics renderer;
  409 soft-pending; stale artifacts gated during active run
- M4: `frontend/Dockerfile` (node:24-alpine → nginx:1.28-alpine);
  Compose `dashboard` on `:3000`; nginx `/api/` → `api:8080`; same-origin
  (no Spring CORS); SPA `try_files`
- Verified: compose up; dashboard create/run basic_ping_pong → PASSED;
  metrics/logs; deep link `/tests/<uuid>`; PG volume survives `compose down`
- Deferred: charts, auth, WebSocket logs, run-specific artifact APIs, AWS

**Phase 13 — CI/CD**
- COMPLETE — **CI only** (CD/deployment deferred to Phase 14)
- M1: baseline jobs `c-python`, `java`, `frontend` on `ubuntu-24.04`
- M2: `java-integration` with Postgres 18 + Redis 8 services;
  ephemeral CI credentials; `make` + `NETVERIFY_REPO_ROOT`;
  verified 248/0/0/0 skipped
- M3: `docker` job builds `netverify-app:ci` + `netverify-dashboard:ci`;
  `compose config` via temporary `.env` from `.env.example` (trap cleanup);
  no registry push; no `compose up`
- M4: docs / closure
- Exclusions: latency_injection/netem, full Compose browser smoke,
  deployment, AWS, registry publishing, production secrets

**Phase 14 — Cloud / AWS**
- **IN PROGRESS / DEPLOYMENT DEFERRED** (not fully COMPLETE)
- Offline Terraform under `infra/terraform/` through M4d; no AWS account;
  no plan/apply; no cloud resources
- M1: Terraform foundation (local state)
- M2a: ECR definitions (IMMUTABLE tags)
- M3a: VPC (2 public + 2 private; IGW; no NAT)
- M3b: security groups + managed-data subnet groups
- M3c: RDS PostgreSQL + ElastiCache Redis definitions
- M4a: ECS cluster + IAM + CloudWatch logs + Service Connect namespace
- M4b: dashboard/API/worker Fargate task definitions
- M4c: public ALB + ECS services + Service Connect (`api:8080`)
- M4d: offline deployment-readiness audit + docs
- Architecture: Internet → ALB → dashboard Fargate → API via Service Connect;
  API+worker → private RDS/Redis; ECS public subnets + public IPs
- Secrets: `var.db_password` (RDS) vs external SSM ARN (ECS) must stay in sync;
  no real secret committed; state remains local until remote backend
- Deferred blockers: AWS account, ECR push, SSM SecureString, regional
  engine/class API check, plan/apply, runtime ALB/Service Connect verify,
  cloud smoke, OIDC CD, HTTPS/ACM/Route53, remote state
- Cost-aware: no NAT; single-AZ RDS; single-node Redis; one worker; no
  autoscaling; 7-day logs; HTTP only; teardown-friendly settings
- Resume checklist: account → region check → secrets bootstrap → amd64
  immutable image push → plan → controlled apply → basic_ping_pong smoke →
  OIDC/CD last

Next:
- Resume Phase 14 with a real AWS account (bootstrap + first plan/apply + smoke)

Last updated:
2026-10-14

---

# 32. Definition of Project Success

The project is portfolio-ready when:

- C client/server networking works.
- Automated Python scenarios run reliably.
- Failure injection is demonstrated.
- Stress/performance results are measurable.
- Logs allow debugging a failed test.
- Java API can create and manage test runs.
- Results persist in PostgreSQL.
- Test jobs execute asynchronously.
- Docker setup is reproducible.
- CI runs automatically.
- README contains architecture and demo instructions.
- The developer can explain all major design decisions.
- At least one meaningful failure/bug is documented with root-cause analysis.

The dashboard, cloud deployment, and AI analysis are valuable additions, but the core project should already be strong before those are added.
