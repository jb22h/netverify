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

**Current phase:** Phase 8 — Java Spring Boot Control Plane

Phase 7 adds automated `concurrent_load`: one `net_server` and N concurrent real `net_client` processes (`subprocess.Popen`, stdlib only), with a shared monotonic load deadline, full process reap, and server logs via `TemporaryFile` (avoids stderr PIPE backpressure). Correctness under load is order-independent (exact session/message/process totals; `peak_active_connections` measured but not required to equal N). Aggregate throughput/bytes and RTT min/max/average-of-per-client-averages are reported metrics, not machine-specific PASS gates; `aggregate_bytes` is client-observed `bytes_sent + bytes_received` only. Observational `/proc` metrics (`server_vm_rss_kb`, `server_vm_hwm_kb`, `server_cpu_time_s`) are informational. The C server was measured as-is (`listen` backlog 10, detached pthread per client, byte-at-a-time `recv`) with no pre-measurement optimization.

Ordinary `--all` is non-privileged and runs `basic_ping_pong`, `connect_fail_no_server`, `server_crash_mid_session`, and a fixed `concurrent_load` smoke of **10 × 100** (ignores global `--count`/`--clients` for that entry). `latency_injection` remains explicit opt-in (sudo). Larger loads use `--test concurrent_load --clients N --count M` (`--clients` 1..1000). Verified workloads include 100 × 1000 as a reproducible benchmark and 500/1000-client stretches; low peaks on short high-N runs are expected because sessions can finish before all clients overlap. Deferred: P95, absolute throughput SLOs, epoll/buffered-recv optimization, Java/Redis/Docker/DB/React/AWS.

## Directory Layout

```text
netverify/
├── automation/
│   ├── runner.py
│   ├── logparse.py
│   ├── assertions.py
│   └── netem.py
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

## Build

```bash
make
```

This produces `net_server` and `net_client`.

## Run

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

## Clean

```bash
make clean
```

Removes `net_server` and `net_client`.

## Full Roadmap

See [PROJECT_SPEC.md](PROJECT_SPEC.md) for the complete development plan and future phases.
