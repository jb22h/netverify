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

**Current phase:** Phase 9 — PostgreSQL Persistence

Phase 8 adds a Java 21 / Maven / Spring Boot control plane under `control-plane/`. Tests are created and started over HTTP; a bounded Java executor launches `automation/runner.py --json` via `ProcessBuilder`, which drives the existing C `net_server` / `net_client`. Persistence is in-memory only (latest state/result per test; no durable history). Redis, PostgreSQL, Docker, React, AWS, and distributed workers are not part of Phase 8.

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

## Control Plane (Phase 8)

Build the C binaries first, then point the control plane at the repo root and start Spring Boot:

```bash
make
export NETVERIFY_REPO_ROOT="$PWD"
cd control-plane
mvn spring-boot:run
```

Optional config (defaults shown): `netverify.runner-timeout-seconds=120`, `netverify.python-command=python3`. `NETVERIFY_REPO_ROOT` overrides `netverify.repo-root`.

Endpoints: `POST/GET /api/tests`, `GET /api/tests/{id}`, `POST /api/tests/{id}/run`, `GET /api/tests/{id}/results|logs|metrics`.

Minimal curl flow (server on port 8080):

```bash
# Create
curl -s -X POST http://localhost:8080/api/tests \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo","scenario":"basic_ping_pong","count":10}'
# → note the returned "id"

# Queue a run
curl -s -X POST http://localhost:8080/api/tests/<id>/run

# Poll status, then read terminal output
curl -s http://localhost:8080/api/tests/<id>
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
