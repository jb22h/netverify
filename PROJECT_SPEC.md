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

Initial endpoints:
```text
POST /api/tests
GET  /api/tests
GET  /api/tests/{id}
POST /api/tests/{id}/run
GET  /api/tests/{id}/results
GET  /api/tests/{id}/logs
GET  /api/tests/{id}/metrics
```

Example request:
```json
{
  "name": "TCP packet loss test",
  "clients": 100,
  "durationSeconds": 30,
  "packetLossPercent": 10
}
```

Backend responsibilities:
- validate input
- create test configuration
- create test run
- trigger runner
- track test status
- return result

Initial statuses:
- CREATED
- QUEUED
- RUNNING
- PASSED
- FAILED
- CANCELLED

Definition of Done:
- A test can be created and started through an HTTP API.

---

## Phase 9 — PostgreSQL
Goal: Persist tests and results.

Tasks:
1. Add database schema.
2. Use migrations.
3. Persist TestScenario.
4. Persist TestRun.
5. Persist metrics.
6. Persist failure information.
7. Query historical test runs.

Topics:
- primary keys
- foreign keys
- indexes
- transactions
- normalization
- JSON vs relational columns

Definition of Done:
- Restarting the backend does not lose test history.

---

## Phase 10 — Queue and Workers
Goal: Make test execution asynchronous and scalable.

Architecture:
```text
Java API
    |
    v
Job Queue
    |
    +------+
    |      |
    v      v
Worker 1 Worker 2
    |      |
    v      v
Python test runners
```

Possible technology:
- Redis-based queue initially

Concepts:
- producer
- consumer
- retries
- timeout
- idempotency
- duplicate jobs
- worker crash
- job ownership
- dead-letter behavior
- at-least-once delivery

Interview questions to be able to answer:
- What happens if a worker crashes in the middle?
- How do you prevent the same test from running twice?
- How do you retry safely?
- How do you know a worker is alive?

Definition of Done:
- Backend does not block while tests execute.
- A worker can independently consume and run a test job.

---

## Phase 11 — Docker
Goal: Make test environments reproducible.

Containerize:
- client
- server
- Java backend
- PostgreSQL
- Redis

Potential Docker Compose services:
- backend
- postgres
- redis
- worker
- test-server
- test-client

Definition of Done:
- A new machine can start the system using documented Docker commands.

---

## Phase 12 — React + TypeScript Dashboard
Goal: Visualize the system.

Pages:

### Test Runs
- ID
- name
- state
- start time
- duration
- pass/fail

### Create Test
Inputs:
- protocol
- number of clients
- duration
- packet loss
- latency

### Test Details
Show:
- configuration
- logs
- metrics
- result
- failure timeline

### Metrics
Charts:
- latency over time
- throughput
- errors
- active connections

Definition of Done:
- A user can create a test and understand its result without using the terminal.

---

## Phase 13 — CI/CD
Goal: Treat the project like production software.

GitHub Actions pipeline:
1. Compile C code.
2. Run C tests.
3. Run Python tests.
4. Build Java backend.
5. Run Java unit/integration tests.
6. Build frontend.
7. Optional Docker image build.

Rules:
- Broken build fails CI.
- Warnings stay visible.
- Tests remain repeatable.

---

## Phase 14 — Cloud / AWS
Only after the local system is stable.

Possible components:
- backend deployment
- remote database
- remote Redis
- remote worker
- logs/monitoring
- object storage for large reports

Do NOT move to AWS too early.

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

> NetVerify is a network verification platform I built to automate testing of connection-oriented network behavior. The low-level client/server components are written in C using Linux sockets. Python orchestrates test scenarios, launches processes, injects failures such as packet loss and latency, parses logs, and produces PASS/FAIL results. A Java Spring Boot control plane manages test jobs, persistent results, and later distributed workers. The project is designed to explore networking, Linux, concurrency, debugging, automation, distributed systems, and operational reliability.

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

NetVerify is a network verification and test automation platform. C is used for low-level TCP client/server components, Python for test automation and failure injection, Java/Spring Boot for the control plane, PostgreSQL for persistence, Redis later for distributed jobs, and React/TypeScript later for the dashboard.

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
`Phase 3 — Multiple Clients and Concurrency` (next)

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

Next:
- Begin Phase 3 — Multiple Clients and Concurrency

Last updated:
2026-09-03

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
