#!/usr/bin/env python3
"""NetVerify Phase 5 automation runner — Milestone 1 (basic_ping_pong)."""

from __future__ import annotations

import argparse
import json
import socket
import subprocess
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from assertions import assert_basic_ping_pong
from logparse import parse_log_text


@dataclass
class TestResult:
    name: str
    passed: bool
    failures: list[str] = field(default_factory=list)
    client_exit_code: int | None = None
    duration_s: float = 0.0
    metrics: dict[str, Any] = field(default_factory=dict)


def project_root() -> Path:
    return Path(__file__).resolve().parent.parent


def resolve_binaries(root: Path) -> tuple[Path, Path] | str:
    server = root / "net_server"
    client = root / "net_client"
    missing = [p.name for p in (server, client) if not p.is_file()]
    if missing:
        return (
            f"missing binary: {', '.join(missing)} "
            f"(expected under {root}; run `make` first)"
        )
    return server, client


def pick_free_port() -> int:
    """Return an ephemeral free TCP port on 127.0.0.1.

    NOTE: There is a small TOCTOU race between closing this socket and the
    server binding the same port (another process may claim it first).
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def is_tcp_port_listening(port: int) -> bool:
    """Return True if /proc/net/tcp shows the port in LISTEN (state 0A).

    Avoids opening a TCP connection, which would create a NetVerify session
    and pollute connection/session counters.
    """
    port_hex = f"{port:04X}"
    try:
        with open("/proc/net/tcp", "r", encoding="utf-8") as proc_tcp:
            next(proc_tcp, None)  # header
            for line in proc_tcp:
                parts = line.split()
                if len(parts) < 4:
                    continue
                local_address = parts[1]
                state = parts[3]
                if state != "0A":
                    continue
                if ":" not in local_address:
                    continue
                _, local_port = local_address.split(":", 1)
                if local_port.upper() == port_hex:
                    return True
    except OSError:
        return False
    return False


def wait_for_server_ready(
    server_proc: subprocess.Popen[str],
    port: int,
    timeout_s: float = 5.0,
    poll_s: float = 0.05,
) -> str | None:
    """Wait until the server listens on port, or return an error message."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if server_proc.poll() is not None:
            stderr = ""
            if server_proc.stderr is not None:
                try:
                    stderr = server_proc.stderr.read()
                except OSError:
                    stderr = ""
            return (
                f"server exited during startup with code {server_proc.returncode}"
                + (f"; stderr:\n{stderr}" if stderr else "")
            )
        if is_tcp_port_listening(port):
            return None
        time.sleep(poll_s)
    return f"server did not enter LISTEN on 127.0.0.1:{port} within {timeout_s}s"


def stop_server(server_proc: subprocess.Popen[str]) -> tuple[str, str]:
    """Terminate/kill the server and return (stdout, stderr)."""
    if server_proc.poll() is None:
        server_proc.terminate()
        try:
            server_proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            server_proc.kill()
            server_proc.wait()

    stdout = ""
    stderr = ""
    try:
        out, err = server_proc.communicate(timeout=2)
        stdout = out or ""
        stderr = err or ""
    except (subprocess.TimeoutExpired, ValueError, OSError):
        # Pipes may already be partially consumed; best-effort drain.
        if server_proc.stdout is not None:
            try:
                stdout = server_proc.stdout.read() or ""
            except OSError:
                pass
        if server_proc.stderr is not None:
            try:
                stderr = server_proc.stderr.read() or ""
            except OSError:
                pass
    return stdout, stderr


def print_human(result: TestResult) -> None:
    status = "PASS" if result.passed else "FAIL"
    print(f"{result.name}: {status}")
    print(f"client_exit_code: {result.client_exit_code}")
    if "requested_count" in result.metrics:
        print(f"messages: {result.metrics['requested_count']}")
    if "client_rtt_avg_us" in result.metrics:
        print(f"client_rtt_avg_us: {result.metrics['client_rtt_avg_us']}")
    print(f"duration_s: {result.duration_s:.3f}")
    for failure in result.failures:
        print(f"- {failure}")


def print_json(result: TestResult) -> None:
    print(json.dumps(asdict(result), indent=2, sort_keys=True))


def run_basic_ping_pong(root: Path, count: int) -> TestResult:
    name = "basic_ping_pong"
    started = time.monotonic()

    binaries = resolve_binaries(root)
    if isinstance(binaries, str):
        return TestResult(
            name=name,
            passed=False,
            failures=[binaries],
            client_exit_code=None,
            duration_s=time.monotonic() - started,
            metrics={"setup_error": True},
        )

    server_bin, client_bin = binaries
    port = pick_free_port()
    server_proc: subprocess.Popen[str] | None = None
    server_stderr = ""
    client_stderr = ""
    client_exit: int | None = None

    try:
        server_proc = subprocess.Popen(
            [str(server_bin), "--port", str(port)],
            cwd=str(root),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

        ready_err = wait_for_server_ready(server_proc, port)
        if ready_err is not None:
            return TestResult(
                name=name,
                passed=False,
                failures=[ready_err],
                client_exit_code=None,
                duration_s=time.monotonic() - started,
                metrics={"port": port, "setup_error": True},
            )

        client_timeout = max(30.0, float(count) * 0.1)
        try:
            completed = subprocess.run(
                [
                    str(client_bin),
                    "--host",
                    "127.0.0.1",
                    "--port",
                    str(port),
                    "--count",
                    str(count),
                ],
                cwd=str(root),
                capture_output=True,
                text=True,
                timeout=client_timeout,
            )
            client_exit = completed.returncode
            client_stderr = completed.stderr or ""
        except subprocess.TimeoutExpired as exc:
            client_stderr = ""
            if exc.stderr:
                client_stderr = (
                    exc.stderr.decode() if isinstance(exc.stderr, bytes) else exc.stderr
                )
            return TestResult(
                name=name,
                passed=False,
                failures=[f"client timed out after {client_timeout}s"],
                client_exit_code=None,
                duration_s=time.monotonic() - started,
                metrics={"port": port},
            )

    except OSError as exc:
        return TestResult(
            name=name,
            passed=False,
            failures=[f"failed to start process: {exc}"],
            client_exit_code=client_exit,
            duration_s=time.monotonic() - started,
            metrics={"port": port, "setup_error": True},
        )
    finally:
        if server_proc is not None:
            _, server_stderr = stop_server(server_proc)

    client_events = parse_log_text(client_stderr)
    server_events = parse_log_text(server_stderr)
    failures, metrics = assert_basic_ping_pong(
        count, client_exit, client_events, server_events
    )
    metrics["port"] = port

    return TestResult(
        name=name,
        passed=len(failures) == 0,
        failures=failures,
        client_exit_code=client_exit,
        duration_s=time.monotonic() - started,
        metrics=metrics,
    )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="NetVerify Phase 5 automation runner (basic_ping_pong)"
    )
    parser.add_argument(
        "--count",
        type=int,
        default=100,
        help="number of PING/PONG exchanges (default: 100)",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="print a single JSON TestResult object on stdout",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.count < 1:
        print("error: --count must be >= 1", file=sys.stderr)
        return 2

    root = project_root()
    binaries = resolve_binaries(root)
    if isinstance(binaries, str):
        print(f"error: {binaries}", file=sys.stderr)
        return 2

    try:
        result = run_basic_ping_pong(root, args.count)
    except Exception as exc:  # noqa: BLE001 — convert unexpected errors to FAIL
        result = TestResult(
            name="basic_ping_pong",
            passed=False,
            failures=[f"unexpected runner error: {exc}"],
            client_exit_code=None,
            duration_s=0.0,
            metrics={},
        )

    if args.json:
        print_json(result)
    else:
        print_human(result)

    if result.metrics.get("setup_error"):
        return 2 if not result.passed else 0
    return 0 if result.passed else 1


if __name__ == "__main__":
    sys.exit(main())
