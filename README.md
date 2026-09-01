# NetVerify

NetVerify is a network verification and test automation platform that runs repeatable TCP/network tests, injects network failures, collects logs and performance metrics, and determines whether the tested system behaves correctly.

## Current Status

- **Phase 0** — Environment and Repository: complete
- **Phase 1** — Minimal TCP Client and Server in C: next

## Directory Layout

```text
netverify/
├── networking/
│   ├── client/          # C TCP client
│   └── server/          # C TCP server
├── docs/                # Architecture and design notes
├── runner/              # Python test automation (later)
├── backend/             # Java Spring Boot control plane (later)
├── frontend/            # React + TypeScript dashboard (later)
├── scripts/             # Utility scripts (later)
├── tests/               # Test suites (later)
├── docker/              # Container setup (later)
├── PROJECT_SPEC.md      # Master project specification
└── README.md
```

Not all directories exist yet. See [PROJECT_SPEC.md](PROJECT_SPEC.md) for the full roadmap.

## Prerequisites

Verified on this development machine:

- WSL2 / Ubuntu
- Git
- GCC 15.2.0
- GNU Make 4.4.1

## Build and Run

_To be added in Phase 1._

## Full Roadmap

See [PROJECT_SPEC.md](PROJECT_SPEC.md) for the complete development plan, technology stack, and phase-by-phase milestones.
