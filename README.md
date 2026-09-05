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

Phase 6 adds deterministic failure injection: opt-in `latency_injection` via `tc`/`netem` on loopback (100 ms delay, verified RTT increase, finally-protected and verified qdisc cleanup) and unprivileged `server_crash_mid_session` (CONNECT-synchronized SIGSTOP/SIGKILL/SIGCONT with full process reap). Ordinary `--all` stays non-privileged; packet loss remains deferred because the blocking client can wait indefinitely without socket timeouts.

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

## Clean

```bash
make clean
```

Removes `net_server` and `net_client`.

## Full Roadmap

See [PROJECT_SPEC.md](PROJECT_SPEC.md) for the complete development plan and future phases.
