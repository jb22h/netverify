#!/usr/bin/env python3
"""NetVerify automation runner (Phases 5–7)."""

from __future__ import annotations

import argparse
import json
import os
import select
import signal
import socket
import subprocess
import sys
import tempfile
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

import netem
from assertions import (
    assert_basic_ping_pong,
    assert_concurrent_load,
    assert_connect_fail_no_server,
    assert_latency_injection,
    assert_server_crash_mid_session,
)
from logparse import parse_log_text

VALID_TESTS = (
    "basic_ping_pong",
    "connect_fail_no_server",
    "latency_injection",
    "server_crash_mid_session",
    "concurrent_load",
)
# Ordinary --all stays non-privileged (no latency_injection / sudo).
# concurrent_load smoke in --all uses fixed 10×100 (not CLI --count/--clients).
ALL_TESTS = (
    "basic_ping_pong",
    "connect_fail_no_server",
    "server_crash_mid_session",
    "concurrent_load",
)
ALL_CONCURRENT_CLIENTS = 10
ALL_CONCURRENT_COUNT = 100

LATENCY_DELAY_MS = 100
CLIENT_COUNT_MAX = 1000000
CONCURRENT_CLIENTS_MAX = 1000


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


def resolve_client_binary(root: Path) -> Path | str:
    client = root / "net_client"
    if not client.is_file():
        return (
            f"missing binary: net_client "
            f"(expected under {root}; run `make` first)"
        )
    return client


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


def _drain_pipe_text(stream: Any) -> str:
    if stream is None:
        return ""
    try:
        data = stream.read()
    except (OSError, ValueError):
        return ""
    if data is None:
        return ""
    if isinstance(data, bytes):
        return data.decode("utf-8", errors="replace")
    return data


def wait_for_server_ready(
    server_proc: subprocess.Popen[Any],
    port: int,
    timeout_s: float = 5.0,
    poll_s: float = 0.05,
) -> str | None:
    """Wait until the server listens on port, or return an error message."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if server_proc.poll() is not None:
            stderr = _drain_pipe_text(server_proc.stderr)
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
    if result.name == "concurrent_load":
        m = result.metrics
        launched = m.get("clients_launched", m.get("clients_requested"))
        succeeded = m.get("clients_succeeded")
        if launched is not None and succeeded is not None:
            print(f"clients: {succeeded}/{launched}")
        if "messages_per_client" in m:
            print(f"messages_per_client: {m['messages_per_client']}")
        if "total_completed_messages" in m:
            print(f"total_messages: {m['total_completed_messages']}")
        for key in (
            "peak_active_connections",
            "aggregate_messages_per_s",
            "aggregate_bytes_per_s",
        ):
            if key in m:
                val = m[key]
                if isinstance(val, float):
                    print(f"{key}: {val:.3f}")
                else:
                    print(f"{key}: {val}")
        print(f"duration_s: {result.duration_s:.3f}")
        for failure in result.failures:
            print(f"- {failure}")
        return

    print(f"client_exit_code: {result.client_exit_code}")
    if "requested_count" in result.metrics:
        print(f"messages: {result.metrics['requested_count']}")
    if "client_rtt_avg_us" in result.metrics:
        print(f"client_rtt_avg_us: {result.metrics['client_rtt_avg_us']}")
    if result.name == "latency_injection":
        for key in (
            "configured_delay_ms",
            "injection_count",
            "baseline_rtt_avg_us",
            "injected_rtt_avg_us",
            "rtt_delta_us",
            "cleanup_ok",
        ):
            if key in result.metrics:
                print(f"{key}: {result.metrics[key]}")
    if result.name == "server_crash_mid_session":
        for key in (
            "crash_count",
            "server_session",
            "client_session_reason",
            "client_messages_received",
            "cleanup_ok",
        ):
            if key in result.metrics:
                print(f"{key}: {result.metrics[key]}")
    if "error_text" in result.metrics:
        print(f"error_text: {result.metrics['error_text']}")
    if "port" in result.metrics and result.name == "connect_fail_no_server":
        print(f"port: {result.metrics['port']}")
    print(f"duration_s: {result.duration_s:.3f}")
    for failure in result.failures:
        print(f"- {failure}")


def print_human_many(results: list[TestResult]) -> None:
    for i, result in enumerate(results):
        if i > 0:
            print()
        print_human(result)
    print()
    overall = "PASS" if all(r.passed for r in results) else "FAIL"
    print(f"overall: {overall}")


def print_json_single(result: TestResult) -> None:
    print(json.dumps(asdict(result), indent=2, sort_keys=True))


def print_json_many(results: list[TestResult]) -> None:
    payload = {
        "passed": all(r.passed for r in results),
        "results": [asdict(r) for r in results],
    }
    print(json.dumps(payload, indent=2, sort_keys=True))


def run_basic_ping_pong(
    root: Path,
    count: int,
    *,
    client_timeout_s: float | None = None,
) -> TestResult:
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

        if client_timeout_s is None:
            client_timeout = max(30.0, float(count) * 0.1)
        else:
            client_timeout = client_timeout_s
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


def run_connect_fail_no_server(root: Path) -> TestResult:
    name = "connect_fail_no_server"
    started = time.monotonic()

    client_bin = resolve_client_binary(root)
    if isinstance(client_bin, str):
        return TestResult(
            name=name,
            passed=False,
            failures=[client_bin],
            client_exit_code=None,
            duration_s=time.monotonic() - started,
            metrics={"setup_error": True},
        )

    port = pick_free_port()
    client_exit: int | None = None
    client_stderr = ""

    try:
        completed = subprocess.run(
            [
                str(client_bin),
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
                "--count",
                "1",
            ],
            cwd=str(root),
            capture_output=True,
            text=True,
            timeout=30.0,
        )
        client_exit = completed.returncode
        client_stderr = completed.stderr or ""
    except subprocess.TimeoutExpired as exc:
        if exc.stderr:
            client_stderr = (
                exc.stderr.decode() if isinstance(exc.stderr, bytes) else exc.stderr
            )
        return TestResult(
            name=name,
            passed=False,
            failures=["client timed out after 30.0s"],
            client_exit_code=None,
            duration_s=time.monotonic() - started,
            metrics={"port": port},
        )
    except OSError as exc:
        return TestResult(
            name=name,
            passed=False,
            failures=[f"failed to start client: {exc}"],
            client_exit_code=None,
            duration_s=time.monotonic() - started,
            metrics={"port": port, "setup_error": True},
        )

    client_events = parse_log_text(client_stderr)
    failures, metrics = assert_connect_fail_no_server(
        port, client_exit, client_events
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


def run_latency_injection(root: Path, requested_count: int) -> TestResult:
    name = "latency_injection"
    started = time.monotonic()
    delay_ms = LATENCY_DELAY_MS
    injection_count = max(1, min(requested_count, 10))
    metrics: dict[str, Any] = {
        "configured_delay_ms": delay_ms,
        "injection_count": injection_count,
        "cleanup_ok": False,
    }

    if netem.find_tc() is None:
        return TestResult(
            name=name,
            passed=False,
            failures=["tc not found on PATH; install iproute2"],
            client_exit_code=None,
            duration_s=time.monotonic() - started,
            metrics={**metrics, "setup_error": True},
        )

    safe, detail = netem.qdisc_safe_for_install(netem.INTERFACE_LO)
    if not safe:
        return TestResult(
            name=name,
            passed=False,
            failures=[detail],
            client_exit_code=None,
            duration_s=time.monotonic() - started,
            metrics={**metrics, "setup_error": True},
        )

    # Baseline without netem.
    # Per-exchange budget: ~2*delay for injected run; baseline is fast.
    baseline_timeout = max(30.0, float(injection_count) * 2.0)
    baseline = run_basic_ping_pong(
        root, injection_count, client_timeout_s=baseline_timeout
    )
    metrics["baseline_client_exit_code"] = baseline.client_exit_code
    metrics["baseline_port"] = baseline.metrics.get("port")
    baseline_rtt = baseline.metrics.get("client_rtt_avg_us")
    if isinstance(baseline_rtt, int):
        metrics["baseline_rtt_avg_us"] = baseline_rtt

    if baseline.metrics.get("setup_error"):
        return TestResult(
            name=name,
            passed=False,
            failures=[f"baseline setup error: {'; '.join(baseline.failures)}"],
            client_exit_code=baseline.client_exit_code,
            duration_s=time.monotonic() - started,
            metrics={**metrics, "setup_error": True},
        )

    if not baseline.passed:
        failures = assert_latency_injection(
            delay_ms,
            injection_count,
            False,
            False,
            baseline.failures,
            [],
            metrics.get("baseline_rtt_avg_us"),
            None,
            True,  # no netem installed yet
        )
        metrics["cleanup_ok"] = True
        return TestResult(
            name=name,
            passed=False,
            failures=failures,
            client_exit_code=baseline.client_exit_code,
            duration_s=time.monotonic() - started,
            metrics=metrics,
        )

    installed = False
    injected: TestResult | None = None
    cleanup_ok = False

    try:
        ok, add_detail = netem.add_delay(netem.INTERFACE_LO, delay_ms)
        if not ok:
            return TestResult(
                name=name,
                passed=False,
                failures=[add_detail],
                client_exit_code=baseline.client_exit_code,
                duration_s=time.monotonic() - started,
                metrics={**metrics, "setup_error": True, "cleanup_ok": True},
            )
        installed = True

        # ~2 * delay_ms RTT per exchange + slack; keep bounded.
        per_exchange_s = (2.0 * delay_ms / 1000.0) * 3.0
        injected_timeout = max(30.0, float(injection_count) * per_exchange_s + 10.0)
        injected = run_basic_ping_pong(
            root, injection_count, client_timeout_s=injected_timeout
        )
    finally:
        if installed:
            del_ok, del_detail = netem.remove_delay(netem.INTERFACE_LO)
            clean_ok, clean_detail = netem.verify_clean(netem.INTERFACE_LO)
            cleanup_ok = bool(del_ok and clean_ok)
            metrics["cleanup_ok"] = cleanup_ok
            if not del_ok:
                metrics["cleanup_delete_error"] = del_detail
            if not clean_ok:
                metrics["cleanup_verify_error"] = clean_detail
        else:
            metrics["cleanup_ok"] = True
            cleanup_ok = True

    if injected is None:
        return TestResult(
            name=name,
            passed=False,
            failures=["injected workload did not run"],
            client_exit_code=baseline.client_exit_code,
            duration_s=time.monotonic() - started,
            metrics=metrics,
        )

    metrics["injected_client_exit_code"] = injected.client_exit_code
    metrics["injected_port"] = injected.metrics.get("port")
    injected_rtt = injected.metrics.get("client_rtt_avg_us")
    if isinstance(injected_rtt, int):
        metrics["injected_rtt_avg_us"] = injected_rtt

    if (
        isinstance(metrics.get("baseline_rtt_avg_us"), int)
        and isinstance(metrics.get("injected_rtt_avg_us"), int)
    ):
        metrics["rtt_delta_us"] = (
            metrics["injected_rtt_avg_us"] - metrics["baseline_rtt_avg_us"]
        )

    if injected.metrics.get("setup_error"):
        return TestResult(
            name=name,
            passed=False,
            failures=[f"injected setup error: {'; '.join(injected.failures)}"],
            client_exit_code=injected.client_exit_code,
            duration_s=time.monotonic() - started,
            metrics={**metrics, "setup_error": True},
        )

    failures = assert_latency_injection(
        delay_ms,
        injection_count,
        baseline.passed,
        injected.passed,
        baseline.failures,
        injected.failures,
        metrics.get("baseline_rtt_avg_us"),
        metrics.get("injected_rtt_avg_us"),
        cleanup_ok,
    )

    return TestResult(
        name=name,
        passed=len(failures) == 0,
        failures=failures,
        client_exit_code=injected.client_exit_code,
        duration_s=time.monotonic() - started,
        metrics=metrics,
    )


def _proc_state(pid: int) -> str | None:
    try:
        with open(f"/proc/{pid}/status", "r", encoding="utf-8") as status:
            for line in status:
                if line.startswith("State:"):
                    parts = line.split()
                    if len(parts) >= 2:
                        return parts[1]
    except OSError:
        return None
    return None


def _wait_proc_stopped(pid: int, timeout_s: float = 2.0) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        state = _proc_state(pid)
        if state is None:
            return False
        if state in ("T", "t"):
            return True
        time.sleep(0.01)
    state = _proc_state(pid)
    return state in ("T", "t")


def _wait_for_server_connect(
    server_proc: subprocess.Popen[Any],
    timeout_s: float = 10.0,
) -> tuple[list[str], str | None, str | None]:
    """Read server stderr until event=CONNECT.

    Returns (lines_so_far, server_session_or_none, error_or_none).
    """
    lines: list[str] = []
    buf = ""
    deadline = time.monotonic() + timeout_s
    stderr = server_proc.stderr
    if stderr is None:
        return lines, None, "server stderr is not available"

    while time.monotonic() < deadline:
        if server_proc.poll() is not None:
            try:
                rest = stderr.read() or b""
            except OSError:
                rest = b""
            if isinstance(rest, str):
                rest_s = rest
            else:
                rest_s = rest.decode("utf-8", errors="replace")
            if rest_s:
                buf += rest_s
            if buf:
                for piece in buf.splitlines():
                    lines.append(piece)
            return lines, None, (
                f"server exited before CONNECT (code {server_proc.returncode})"
            )

        ready, _, _ = select.select([stderr], [], [], 0.05)
        if not ready:
            continue
        try:
            chunk = os.read(stderr.fileno(), 4096)
        except OSError:
            continue
        if not chunk:
            continue
        buf += chunk.decode("utf-8", errors="replace")
        while "\n" in buf:
            line, buf = buf.split("\n", 1)
            line = line.rstrip("\r")
            lines.append(line)
            events = parse_log_text(line + "\n")
            for ev in events:
                if ev.event == "CONNECT":
                    return lines, ev.session, None

    if buf.strip():
        lines.append(buf.rstrip("\r\n"))
    return lines, None, f"timed out waiting for server CONNECT after {timeout_s}s"


def _reap_process(proc: subprocess.Popen[Any] | None, *, force: bool = False) -> int | None:
    if proc is None:
        return None
    if proc.poll() is None:
        if force:
            try:
                proc.kill()
            except OSError:
                pass
        else:
            try:
                proc.terminate()
            except OSError:
                pass
            try:
                proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                try:
                    proc.kill()
                except OSError:
                    pass
                try:
                    proc.wait(timeout=2)
                except (subprocess.TimeoutExpired, OSError):
                    pass
    try:
        if proc.poll() is None:
            proc.wait(timeout=1)
    except (subprocess.TimeoutExpired, OSError):
        pass
    return proc.poll()


def run_server_crash_mid_session(root: Path, requested_count: int) -> TestResult:
    name = "server_crash_mid_session"
    started = time.monotonic()
    crash_count = min(CLIENT_COUNT_MAX, max(requested_count, 100000))
    metrics: dict[str, Any] = {
        "requested_count": requested_count,
        "crash_count": crash_count,
        "connect_observed": False,
        "client_stopped": False,
        "client_resumed": False,
        "cleanup_ok": False,
    }

    binaries = resolve_binaries(root)
    if isinstance(binaries, str):
        return TestResult(
            name=name,
            passed=False,
            failures=[binaries],
            client_exit_code=None,
            duration_s=time.monotonic() - started,
            metrics={**metrics, "setup_error": True},
        )

    server_bin, client_bin = binaries
    port = pick_free_port()
    metrics["port"] = port

    server_proc: subprocess.Popen[str] | None = None
    client_proc: subprocess.Popen[str] | None = None
    client_was_stopped = False
    server_lines: list[str] = []
    server_session: str | None = None
    client_stderr = ""
    client_exit: int | None = None
    server_exit: int | None = None
    sync_error: str | None = None
    early_failures: list[str] = []

    try:
        server_proc = subprocess.Popen(
            [str(server_bin), "--port", str(port)],
            cwd=str(root),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

        ready_err = wait_for_server_ready(server_proc, port)
        if ready_err is not None:
            return TestResult(
                name=name,
                passed=False,
                failures=[ready_err],
                client_exit_code=None,
                duration_s=time.monotonic() - started,
                metrics={**metrics, "setup_error": True},
            )

        client_proc = subprocess.Popen(
            [
                str(client_bin),
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
                "--count",
                str(crash_count),
            ],
            cwd=str(root),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

        server_lines, server_session, sync_error = _wait_for_server_connect(
            server_proc, timeout_s=10.0
        )
        if sync_error is not None:
            early_failures.append(sync_error)
        else:
            metrics["connect_observed"] = True
            metrics["server_session"] = server_session

        if client_proc.poll() is not None:
            early_failures.append(
                f"client exited before SIGSTOP (code {client_proc.returncode})"
            )
        elif sync_error is None:
            try:
                os.kill(client_proc.pid, signal.SIGSTOP)
            except OSError as exc:
                early_failures.append(f"SIGSTOP failed: {exc}")
            else:
                if _wait_proc_stopped(client_proc.pid):
                    client_was_stopped = True
                    metrics["client_stopped"] = True
                else:
                    early_failures.append(
                        "client did not enter stopped state after SIGSTOP"
                    )

        if client_was_stopped and server_proc.poll() is None:
            server_proc.kill()
            try:
                server_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                early_failures.append("server did not exit after SIGKILL")
            server_exit = server_proc.poll()
            metrics["server_exit_code"] = server_exit
        elif server_proc is not None and server_proc.poll() is None:
            # Crash path not reached; still stop server in cleanup.
            pass

        # Drain any remaining server stderr after death / before resume.
        if server_proc is not None:
            rest = _drain_pipe_text(server_proc.stderr)
            if rest:
                for piece in rest.splitlines():
                    server_lines.append(piece.rstrip("\r"))

        if client_was_stopped and client_proc.poll() is None:
            try:
                os.kill(client_proc.pid, signal.SIGCONT)
                metrics["client_resumed"] = True
            except OSError as exc:
                early_failures.append(f"SIGCONT failed: {exc}")
                metrics["client_resumed"] = False
            else:
                client_was_stopped = False
                try:
                    client_exit = client_proc.wait(timeout=30.0)
                except subprocess.TimeoutExpired:
                    early_failures.append("client did not exit within 30s after resume")
                    try:
                        client_proc.kill()
                    except OSError:
                        pass
                    try:
                        client_exit = client_proc.wait(timeout=5)
                    except (subprocess.TimeoutExpired, OSError):
                        client_exit = client_proc.poll()
                client_stderr = _drain_pipe_text(client_proc.stderr)
                _drain_pipe_text(client_proc.stdout)
        elif client_proc is not None:
            # Client never stopped successfully; still collect output.
            if client_proc.poll() is None:
                try:
                    client_exit = client_proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    pass
            client_exit = client_proc.poll() if client_exit is None else client_exit
            client_stderr = _drain_pipe_text(client_proc.stderr)
            _drain_pipe_text(client_proc.stdout)

    finally:
        cleanup_ok = True

        # Ensure client is not left stopped.
        if client_proc is not None and client_proc.poll() is None:
            state = _proc_state(client_proc.pid)
            if state in ("T", "t") or client_was_stopped:
                try:
                    os.kill(client_proc.pid, signal.SIGCONT)
                    metrics["client_resumed"] = True
                except OSError:
                    cleanup_ok = False
                client_was_stopped = False

        if client_proc is not None and client_proc.poll() is None:
            code = _reap_process(client_proc, force=False)
            if client_proc.poll() is None:
                code = _reap_process(client_proc, force=True)
            if client_exit is None:
                client_exit = code
            if client_proc.poll() is not None and not client_stderr:
                client_stderr = _drain_pipe_text(client_proc.stderr)
                _drain_pipe_text(client_proc.stdout)

        if server_proc is not None and server_proc.poll() is None:
            code = _reap_process(server_proc, force=True)
            if server_exit is None:
                server_exit = code
            rest = _drain_pipe_text(server_proc.stderr)
            if rest:
                for piece in rest.splitlines():
                    server_lines.append(piece.rstrip("\r"))

        if client_proc is not None and client_proc.poll() is None:
            cleanup_ok = False
        if server_proc is not None and server_proc.poll() is None:
            cleanup_ok = False
        if client_proc is not None and client_proc.poll() is None:
            state = _proc_state(client_proc.pid)
            if state in ("T", "t"):
                cleanup_ok = False

        metrics["cleanup_ok"] = cleanup_ok
        if client_exit is not None:
            metrics["client_exit_code"] = client_exit
        if server_exit is not None:
            metrics["server_exit_code"] = server_exit

    server_log = "\n".join(server_lines)
    if server_lines:
        server_log += "\n"
    client_events = parse_log_text(client_stderr)
    server_events = parse_log_text(server_log)

    failures, assert_metrics = assert_server_crash_mid_session(
        crash_count,
        client_exit,
        server_exit,
        client_events,
        server_events,
        bool(metrics.get("connect_observed")),
        bool(metrics.get("client_stopped")),
        bool(metrics.get("client_resumed")),
        bool(metrics.get("cleanup_ok")),
        server_session,
    )
    metrics.update(assert_metrics)
    failures = early_failures + failures

    return TestResult(
        name=name,
        passed=len(failures) == 0,
        failures=failures,
        client_exit_code=client_exit,
        duration_s=time.monotonic() - started,
        metrics=metrics,
    )


def concurrent_load_timeout_s(clients: int, count: int) -> float:
    """Bounded overall client deadline (concurrency-aware, not N*M)."""
    return min(
        300.0,
        max(30.0, 20.0 + float(count) * 0.02 + float(clients) * 0.10),
    )


def _collect_linux_proc_metrics(pid: int) -> dict[str, Any]:
    """Observational /proc CPU and memory metrics for a live process."""
    out: dict[str, Any] = {}
    try:
        with open(f"/proc/{pid}/status", "r", encoding="utf-8") as status:
            for line in status:
                if line.startswith("VmRSS:"):
                    parts = line.split()
                    if len(parts) >= 2:
                        out["server_vm_rss_kb"] = int(parts[1])
                elif line.startswith("VmHWM:"):
                    parts = line.split()
                    if len(parts) >= 2:
                        out["server_vm_hwm_kb"] = int(parts[1])
    except (OSError, ValueError):
        pass

    try:
        with open(f"/proc/{pid}/stat", "r", encoding="utf-8") as stat_f:
            content = stat_f.read()
        close_paren = content.rfind(")")
        if close_paren != -1:
            rest = content[close_paren + 2 :].split()
            # After comm: state(0) ... utime(11) stime(12)
            if len(rest) > 12:
                utime = int(rest[11])
                stime = int(rest[12])
                ticks = os.sysconf("SC_CLK_TCK")
                if ticks and ticks > 0:
                    out["server_cpu_time_s"] = (utime + stime) / float(ticks)
    except (OSError, ValueError):
        pass

    return out


def _read_temp_text(file_obj: Any) -> str:
    if file_obj is None:
        return ""
    try:
        file_obj.seek(0)
        data = file_obj.read()
    except (OSError, ValueError):
        return ""
    if data is None:
        return ""
    if isinstance(data, bytes):
        return data.decode("utf-8", errors="replace")
    return data


def run_concurrent_load(root: Path, clients: int, count: int) -> TestResult:
    name = "concurrent_load"
    wall_started = time.monotonic()

    binaries = resolve_binaries(root)
    if isinstance(binaries, str):
        return TestResult(
            name=name,
            passed=False,
            failures=[binaries],
            client_exit_code=None,
            duration_s=time.monotonic() - wall_started,
            metrics={"setup_error": True, "clients_requested": clients},
        )

    server_bin, client_bin = binaries
    port = pick_free_port()
    load_timeout = concurrent_load_timeout_s(clients, count)

    server_proc: subprocess.Popen[Any] | None = None
    server_out_file: Any = None
    server_err_file: Any = None
    client_procs: list[subprocess.Popen[str]] = []
    client_exit_codes: list[int | None] = []
    client_events_list: list[list[Any]] = []
    load_duration_s = 0.0
    load_start: float | None = None
    early_failures: list[str] = []
    proc_metrics: dict[str, Any] = {}
    server_stderr = ""
    timed_out_indexes: set[int] = set()
    setup_error: str | None = None

    try:
        server_out_file = tempfile.TemporaryFile()
        server_err_file = tempfile.TemporaryFile()
        server_proc = subprocess.Popen(
            [str(server_bin), "--port", str(port)],
            cwd=str(root),
            stdout=server_out_file,
            stderr=server_err_file,
        )

        ready_err = wait_for_server_ready(server_proc, port)
        if ready_err is not None:
            setup_error = ready_err
        else:
            load_start = time.monotonic()
            deadline = load_start + load_timeout

            for _ in range(clients):
                try:
                    proc = subprocess.Popen(
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
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        text=True,
                    )
                except OSError as exc:
                    early_failures.append(f"failed to start client: {exc}")
                    break
                client_procs.append(proc)

            while time.monotonic() < deadline:
                if all(p.poll() is not None for p in client_procs):
                    break
                time.sleep(0.01)

            for i, proc in enumerate(client_procs):
                if proc.poll() is None:
                    timed_out_indexes.add(i)

    except OSError as exc:
        if setup_error is None:
            early_failures.append(f"failed to start process: {exc}")
    finally:
        # Reap every client, then collect exit codes and stderr exactly once.
        for i, proc in enumerate(client_procs):
            if proc.poll() is None:
                _reap_process(proc, force=False)
                if proc.poll() is None:
                    _reap_process(proc, force=True)
            exit_code = proc.poll()
            stderr = _drain_pipe_text(proc.stderr)
            _drain_pipe_text(proc.stdout)
            if i in timed_out_indexes:
                early_failures.append(
                    f"client[{i}] timed out after {load_timeout:.1f}s"
                    + (f" (exit {exit_code})" if exit_code is not None else "")
                )
            client_exit_codes.append(exit_code)
            client_events_list.append(parse_log_text(stderr))

        if load_start is not None:
            load_duration_s = time.monotonic() - load_start

        # Observational /proc metrics while server is still alive.
        if server_proc is not None and server_proc.poll() is None:
            try:
                proc_metrics = _collect_linux_proc_metrics(server_proc.pid)
            except (OSError, AttributeError):
                proc_metrics = {}

        if server_proc is not None and server_proc.poll() is None:
            _reap_process(server_proc, force=False)
            if server_proc.poll() is None:
                _reap_process(server_proc, force=True)

        server_stderr = _read_temp_text(server_err_file)
        _read_temp_text(server_out_file)

        for file_obj in (server_out_file, server_err_file):
            if file_obj is not None:
                try:
                    file_obj.close()
                except OSError:
                    pass

        if server_proc is not None and server_proc.poll() is None:
            early_failures.append("server process still running after cleanup")
        for i, proc in enumerate(client_procs):
            if proc.poll() is None:
                early_failures.append(f"client[{i}] still running after cleanup")

    if setup_error is not None:
        return TestResult(
            name=name,
            passed=False,
            failures=[setup_error],
            client_exit_code=None,
            duration_s=time.monotonic() - wall_started,
            metrics={
                "port": port,
                "setup_error": True,
                "clients_requested": clients,
            },
        )

    if not client_exit_codes and clients > 0:
        early_failures.append("no clients were launched")

    server_events = parse_log_text(server_stderr)
    failures, metrics = assert_concurrent_load(
        clients,
        count,
        client_exit_codes,
        client_events_list,
        server_events,
        load_duration_s,
    )
    failures = early_failures + failures
    metrics["port"] = port
    metrics["load_timeout_s"] = load_timeout
    metrics.update(proc_metrics)

    client_exit: int | None = None
    if client_exit_codes:
        if all(c == 0 for c in client_exit_codes):
            client_exit = 0
        else:
            for c in client_exit_codes:
                if c != 0:
                    client_exit = c
                    break

    return TestResult(
        name=name,
        passed=len(failures) == 0,
        failures=failures,
        client_exit_code=client_exit,
        duration_s=load_duration_s,
        metrics=metrics,
    )


def run_named_test(
    root: Path, name: str, count: int, clients: int = 10
) -> TestResult:
    if name == "basic_ping_pong":
        return run_basic_ping_pong(root, count)
    if name == "connect_fail_no_server":
        return run_connect_fail_no_server(root)
    if name == "latency_injection":
        return run_latency_injection(root, count)
    if name == "server_crash_mid_session":
        return run_server_crash_mid_session(root, count)
    if name == "concurrent_load":
        return run_concurrent_load(root, clients, count)
    return TestResult(
        name=name,
        passed=False,
        failures=[f"unknown test: {name}"],
        client_exit_code=None,
        duration_s=0.0,
        metrics={"setup_error": True},
    )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="NetVerify automation runner"
    )
    parser.add_argument(
        "--count",
        type=int,
        default=100,
        help="PING/PONG count for basic_ping_pong / latency baseline (default: 100)",
    )
    parser.add_argument(
        "--clients",
        type=int,
        default=10,
        help="concurrent client count for concurrent_load (default: 10)",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="print JSON result(s) on stdout",
    )
    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "--test",
        choices=VALID_TESTS,
        help="run a single named test",
    )
    group.add_argument(
        "--all",
        action="store_true",
        help=(
            "run non-privileged tests (excludes latency_injection; "
            "concurrent_load smoke is fixed 10 clients x 100 messages)"
        ),
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.count < 1:
        print("error: --count must be >= 1", file=sys.stderr)
        return 2
    if args.clients < 1 or args.clients > CONCURRENT_CLIENTS_MAX:
        print(
            f"error: --clients must be between 1 and {CONCURRENT_CLIENTS_MAX}",
            file=sys.stderr,
        )
        return 2

    root = project_root()

    if args.all:
        selected = list(ALL_TESTS)
    elif args.test is not None:
        selected = [args.test]
    else:
        selected = ["basic_ping_pong"]

    needs_server = any(
        t
        in (
            "basic_ping_pong",
            "latency_injection",
            "server_crash_mid_session",
            "concurrent_load",
        )
        for t in selected
    )
    if needs_server:
        binaries = resolve_binaries(root)
        if isinstance(binaries, str):
            print(f"error: {binaries}", file=sys.stderr)
            return 2
    else:
        client = resolve_client_binary(root)
        if isinstance(client, str):
            print(f"error: {client}", file=sys.stderr)
            return 2

    results: list[TestResult] = []
    for name in selected:
        try:
            if args.all and name == "concurrent_load":
                result = run_named_test(
                    root,
                    name,
                    ALL_CONCURRENT_COUNT,
                    clients=ALL_CONCURRENT_CLIENTS,
                )
            else:
                result = run_named_test(
                    root, name, args.count, clients=args.clients
                )
        except Exception as exc:  # noqa: BLE001
            result = TestResult(
                name=name,
                passed=False,
                failures=[f"unexpected runner error: {exc}"],
                client_exit_code=None,
                duration_s=0.0,
                metrics={},
            )
        results.append(result)

    if args.json:
        if len(results) == 1 and not args.all:
            print_json_single(results[0])
        else:
            print_json_many(results)
    else:
        if len(results) == 1 and not args.all:
            print_human(results[0])
        else:
            print_human_many(results)

    if any(r.metrics.get("setup_error") for r in results):
        return 2
    return 0 if all(r.passed for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
