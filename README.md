# NetVerify

NetVerify is a network verification and test automation platform that runs repeatable TCP/network tests, collects logs and performance metrics, and determines whether the tested system behaves correctly.

## Current Status

- **Phase 0** — Environment and Repository: complete
- **Phase 1** — Minimal TCP Client and Server in C: complete

Phase 1 delivers a minimal TCP client and server in C. The server listens on `127.0.0.1:9000`, validates an incoming `PING` request, and responds with `PONG`. The client connects, sends `PING`, receives the response, and validates `PONG`.

## Directory Layout

```text
netverify/
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
./net_server
```

**Terminal 2:**

```bash
./net_client
```

**Expected output:**

```text
Server: PING
Client: PONG
```

## Clean

```bash
make clean
```

Removes `net_server` and `net_client`.

## Full Roadmap

See [PROJECT_SPEC.md](PROJECT_SPEC.md) for the complete development plan and future phases.
