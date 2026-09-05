"""Deterministic assertions for NetVerify automation scenarios."""

from __future__ import annotations

from typing import Any

from logparse import LogEvent


def _events_named(events: list[LogEvent], name: str) -> list[LogEvent]:
    return [e for e in events if e.event == name]


def _session_events(events: list[LogEvent], session: str) -> list[LogEvent]:
    return [e for e in events if e.session == session]


def _require_int(fields: dict[str, str], key: str, failures: list[str], prefix: str) -> int | None:
    if key not in fields:
        failures.append(f"{prefix}: missing field {key}")
        return None
    try:
        return int(fields[key])
    except ValueError:
        failures.append(f"{prefix}: field {key} is not an integer ({fields[key]!r})")
        return None


def _require_float(
    fields: dict[str, str], key: str, failures: list[str], prefix: str
) -> float | None:
    if key not in fields:
        failures.append(f"{prefix}: missing field {key}")
        return None
    try:
        return float(fields[key])
    except ValueError:
        failures.append(f"{prefix}: field {key} is not a float ({fields[key]!r})")
        return None


def _metric_int(
    metrics: dict[str, Any],
    metric_key: str,
    fields: dict[str, str],
    field_key: str,
    failures: list[str],
    prefix: str,
) -> int | None:
    """Convert field to int for metrics when present; report failure if malformed."""
    if field_key not in fields:
        return None
    value = _require_int(fields, field_key, failures, prefix)
    if value is not None:
        metrics[metric_key] = value
    return value


def _metric_float(
    metrics: dict[str, Any],
    metric_key: str,
    fields: dict[str, str],
    field_key: str,
    failures: list[str],
    prefix: str,
) -> float | None:
    """Convert field to float for metrics when present; report failure if malformed."""
    if field_key not in fields:
        return None
    value = _require_float(fields, field_key, failures, prefix)
    if value is not None:
        metrics[metric_key] = value
    return value


def assert_basic_ping_pong(
    count: int,
    client_exit_code: int | None,
    client_events: list[LogEvent],
    server_events: list[LogEvent],
) -> tuple[list[str], dict[str, Any]]:
    """Assert the happy-path basic_ping_pong scenario.

    Returns (failures, metrics). Does not print.
    Numeric metrics are stored as int/float for JSON-friendly TestResult output.
    """
    failures: list[str] = []
    metrics: dict[str, Any] = {"requested_count": count}

    if client_exit_code != 0:
        failures.append(f"client exit code: expected 0, got {client_exit_code!r}")

    client_attempts = _events_named(client_events, "CONNECT_ATTEMPT")
    client_oks = _events_named(client_events, "CONNECT_OK")
    client_fails = _events_named(client_events, "CONNECT_FAIL")
    client_ends = _events_named(client_events, "SESSION_END")

    if len(client_attempts) != 1:
        failures.append(
            f"client CONNECT_ATTEMPT: expected exactly 1, got {len(client_attempts)}"
        )
    if len(client_oks) != 1:
        failures.append(
            f"client CONNECT_OK: expected exactly 1, got {len(client_oks)}"
        )
    if len(client_fails) != 0:
        failures.append(
            f"client CONNECT_FAIL: expected 0, got {len(client_fails)}"
        )
    if len(client_ends) != 1:
        failures.append(
            f"client SESSION_END: expected exactly 1, got {len(client_ends)}"
        )
    else:
        c_end = client_ends[0]
        prefix = "client SESSION_END"
        reason = c_end.fields.get("reason")
        if reason != "bye":
            failures.append(f"client SESSION_END reason: expected bye, got {reason!r}")

        msg_sent = _require_int(c_end.fields, "messages_sent", failures, prefix)
        msg_recv = _require_int(c_end.fields, "messages_received", failures, prefix)
        malformed = _require_int(c_end.fields, "malformed_messages", failures, prefix)
        bytes_sent = _require_int(c_end.fields, "bytes_sent", failures, prefix)
        bytes_recv = _require_int(c_end.fields, "bytes_received", failures, prefix)

        if msg_sent is not None:
            metrics["client_messages_sent"] = msg_sent
            if msg_sent != count:
                failures.append(
                    f"client messages_sent: expected {count}, got {msg_sent}"
                )
        if msg_recv is not None:
            metrics["client_messages_received"] = msg_recv
            if msg_recv != count:
                failures.append(
                    f"client messages_received: expected {count}, got {msg_recv}"
                )
        if malformed is not None:
            metrics["client_malformed_messages"] = malformed
            if malformed != 0:
                failures.append(
                    f"client malformed_messages: expected 0, got {malformed}"
                )
        if bytes_sent is not None:
            metrics["client_bytes_sent"] = bytes_sent
            if bytes_sent <= 0:
                failures.append(f"client bytes_sent: expected > 0, got {bytes_sent}")
        if bytes_recv is not None:
            metrics["client_bytes_received"] = bytes_recv
            if bytes_recv <= 0:
                failures.append(
                    f"client bytes_received: expected > 0, got {bytes_recv}"
                )

        for key in ("rtt_min_us", "rtt_max_us", "rtt_avg_us", "connection_duration_us"):
            _metric_int(metrics, f"client_{key}", c_end.fields, key, failures, prefix)
        for key in ("throughput_msg_per_s", "throughput_bytes_per_s"):
            _metric_float(metrics, f"client_{key}", c_end.fields, key, failures, prefix)

    quit_ends = [
        e
        for e in _events_named(server_events, "SESSION_END")
        if e.fields.get("reason") == "quit"
    ]
    if len(quit_ends) != 1:
        failures.append(
            "server SESSION_END reason=quit: "
            f"expected exactly 1, got {len(quit_ends)}"
        )
        return failures, metrics

    s_end = quit_ends[0]
    sid = s_end.session
    metrics["server_session"] = sid
    sess = _session_events(server_events, sid)
    prefix = "server SESSION_END"

    connects = [e for e in sess if e.event == "CONNECT"]
    ends = [e for e in sess if e.event == "SESSION_END"]
    disconnects = [e for e in sess if e.event == "DISCONNECT"]
    errors = [e for e in sess if e.event == "ERROR"]

    if len(connects) != 1:
        failures.append(
            f"server session {sid} CONNECT: expected exactly 1, got {len(connects)}"
        )
    if len(ends) != 1:
        failures.append(
            f"server session {sid} SESSION_END: expected exactly 1, got {len(ends)}"
        )
    if len(disconnects) != 1:
        failures.append(
            f"server session {sid} DISCONNECT: expected exactly 1, got {len(disconnects)}"
        )
    if len(errors) != 0:
        failures.append(
            f"server session {sid} ERROR: expected 0, got {len(errors)}"
        )

    msg_sent = _require_int(s_end.fields, "messages_sent", failures, prefix)
    msg_recv = _require_int(s_end.fields, "messages_received", failures, prefix)
    malformed = _require_int(s_end.fields, "malformed_messages", failures, prefix)
    bytes_sent = _require_int(s_end.fields, "bytes_sent", failures, prefix)
    bytes_recv = _require_int(s_end.fields, "bytes_received", failures, prefix)

    if msg_sent is not None:
        metrics["server_messages_sent"] = msg_sent
        if msg_sent != count:
            failures.append(f"server messages_sent: expected {count}, got {msg_sent}")
    if msg_recv is not None:
        metrics["server_messages_received"] = msg_recv
        if msg_recv != count:
            failures.append(
                f"server messages_received: expected {count}, got {msg_recv}"
            )
    if malformed is not None:
        metrics["server_malformed_messages"] = malformed
        if malformed != 0:
            failures.append(f"server malformed_messages: expected 0, got {malformed}")
    if bytes_sent is not None:
        metrics["server_bytes_sent"] = bytes_sent
        if bytes_sent <= 0:
            failures.append(f"server bytes_sent: expected > 0, got {bytes_sent}")
    if bytes_recv is not None:
        metrics["server_bytes_received"] = bytes_recv
        if bytes_recv <= 0:
            failures.append(f"server bytes_received: expected > 0, got {bytes_recv}")

    _metric_int(
        metrics, "server_connection_duration_us", s_end.fields,
        "connection_duration_us", failures, prefix,
    )
    for key in ("throughput_msg_per_s", "throughput_bytes_per_s"):
        _metric_float(metrics, f"server_{key}", s_end.fields, key, failures, prefix)

    total_accepted = _metric_int(
        metrics, "server_total_connections_accepted", s_end.fields,
        "total_connections_accepted", failures, prefix,
    )
    if total_accepted is not None and total_accepted != 1:
        failures.append(
            f"server total_connections_accepted: expected 1, got {total_accepted}"
        )

    total_spawn = _metric_int(
        metrics, "server_total_spawn_failures", s_end.fields,
        "total_spawn_failures", failures, prefix,
    )
    if total_spawn is not None and total_spawn != 0:
        failures.append(
            f"server total_spawn_failures: expected 0, got {total_spawn}"
        )

    total_ok = _metric_int(
        metrics, "server_total_sessions_ok", s_end.fields,
        "total_sessions_ok", failures, prefix,
    )
    if total_ok is not None and total_ok != 1:
        failures.append(f"server total_sessions_ok: expected 1, got {total_ok}")

    total_failed = _metric_int(
        metrics, "server_total_sessions_failed", s_end.fields,
        "total_sessions_failed", failures, prefix,
    )
    if total_failed is not None and total_failed != 0:
        failures.append(
            f"server total_sessions_failed: expected 0, got {total_failed}"
        )

    if len(disconnects) == 1:
        active = _require_int(
            disconnects[0].fields,
            "active_connections",
            failures,
            "server DISCONNECT",
        )
        if active is not None:
            metrics["server_active_connections_at_disconnect"] = active
            if active != 0:
                failures.append(
                    f"server DISCONNECT active_connections: expected 0, got {active}"
                )

    return failures, metrics


def assert_connect_fail_no_server(
    port: int,
    client_exit_code: int | None,
    client_events: list[LogEvent],
) -> tuple[list[str], dict[str, Any]]:
    """Assert connect_fail_no_server: client fails when nothing is listening.

    Returns (failures, metrics). Does not print.
    """
    failures: list[str] = []
    metrics: dict[str, Any] = {"port": port}

    if client_exit_code is not None:
        metrics["client_exit_code"] = client_exit_code

    if client_exit_code is None or client_exit_code == 0:
        failures.append(
            f"client exit code: expected nonzero, got {client_exit_code!r}"
        )

    attempts = _events_named(client_events, "CONNECT_ATTEMPT")
    fails = _events_named(client_events, "CONNECT_FAIL")
    oks = _events_named(client_events, "CONNECT_OK")
    ends = _events_named(client_events, "SESSION_END")

    if len(attempts) != 1:
        failures.append(
            f"client CONNECT_ATTEMPT: expected exactly 1, got {len(attempts)}"
        )
    if len(fails) != 1:
        failures.append(
            f"client CONNECT_FAIL: expected exactly 1, got {len(fails)}"
        )
    if len(oks) != 0:
        failures.append(
            "client CONNECT_OK: expected 0 "
            f"(port may have been claimed by another process), got {len(oks)}"
        )
    if len(ends) != 0:
        failures.append(
            f"client SESSION_END: expected 0, got {len(ends)}"
        )

    if len(fails) == 1:
        fail = fails[0]
        prefix = "client CONNECT_FAIL"
        host = fail.fields.get("host")
        if host != "127.0.0.1":
            failures.append(
                f"{prefix} host: expected 127.0.0.1, got {host!r}"
            )

        fail_port = _require_int(fail.fields, "port", failures, prefix)
        if fail_port is not None and fail_port != port:
            failures.append(
                f"{prefix} port: expected {port}, got {fail_port}"
            )

        error_code = _require_int(fail.fields, "error_code", failures, prefix)
        if error_code is not None:
            metrics["error_code"] = error_code
            if error_code == 0:
                failures.append(f"{prefix} error_code: expected nonzero, got 0")

        if "error_text" not in fail.fields:
            failures.append(f"{prefix}: missing field error_text")
        else:
            error_text = fail.fields["error_text"]
            metrics["error_text"] = error_text
            if error_text == "":
                failures.append(f"{prefix} error_text: expected non-empty string")

    return failures, metrics


def assert_latency_injection(
    configured_delay_ms: int,
    injection_count: int,
    baseline_passed: bool,
    injected_passed: bool,
    baseline_failures: list[str],
    injected_failures: list[str],
    baseline_rtt_avg_us: int | None,
    injected_rtt_avg_us: int | None,
    cleanup_ok: bool,
) -> list[str]:
    """Assert latency_injection RTT delta and cleanup. Does not run tc or print."""
    failures: list[str] = []

    if not baseline_passed:
        failures.append("baseline workload failed")
        for item in baseline_failures:
            failures.append(f"baseline: {item}")
        return failures

    if baseline_rtt_avg_us is None:
        failures.append("baseline_rtt_avg_us missing")
        return failures

    if not injected_passed:
        failures.append("injected workload failed")
        for item in injected_failures:
            failures.append(f"injected: {item}")

    if injected_rtt_avg_us is None:
        failures.append("injected_rtt_avg_us missing")
    elif injected_passed:
        min_expected = baseline_rtt_avg_us + int(1.5 * configured_delay_ms * 1000)
        if injected_rtt_avg_us < min_expected:
            failures.append(
                "injected_rtt_avg_us "
                f"{injected_rtt_avg_us} < baseline {baseline_rtt_avg_us} "
                f"+ 1.5*{configured_delay_ms}ms "
                f"(need >= {min_expected})"
            )
        # Broad sanity upper bound: reject absurd runaway values.
        max_sane = baseline_rtt_avg_us + int(6.0 * configured_delay_ms * 1000)
        if injected_rtt_avg_us > max_sane:
            failures.append(
                "injected_rtt_avg_us "
                f"{injected_rtt_avg_us} exceeds sanity upper bound {max_sane} "
                f"(baseline {baseline_rtt_avg_us}, delay {configured_delay_ms}ms)"
            )

    if not cleanup_ok:
        failures.append("cleanup_ok is false: netem was not fully removed from lo")

    if injection_count < 1:
        failures.append(f"injection_count must be >= 1, got {injection_count}")

    return failures


def assert_server_crash_mid_session(
    crash_count: int,
    client_exit_code: int | None,
    server_exit_code: int | None,
    client_events: list[LogEvent],
    server_events: list[LogEvent],
    connect_observed: bool,
    client_stopped: bool,
    client_resumed: bool,
    cleanup_ok: bool,
    server_session: str | None,
) -> tuple[list[str], dict[str, Any]]:
    """Assert server_crash_mid_session outcomes. No process control or printing."""
    failures: list[str] = []
    metrics: dict[str, Any] = {
        "crash_count": crash_count,
        "connect_observed": connect_observed,
        "client_stopped": client_stopped,
        "client_resumed": client_resumed,
        "cleanup_ok": cleanup_ok,
    }

    if client_exit_code is not None:
        metrics["client_exit_code"] = client_exit_code
    if server_exit_code is not None:
        metrics["server_exit_code"] = server_exit_code
    if server_session is not None:
        metrics["server_session"] = server_session

    if not connect_observed:
        failures.append("server CONNECT was not observed before crash")
    if not client_stopped:
        failures.append("client was not confirmed stopped (SIGSTOP) before server kill")
    if not client_resumed:
        failures.append("client was not resumed (SIGCONT) after server kill")
    if not cleanup_ok:
        failures.append("cleanup_ok is false: processes were not fully reaped")

    if client_exit_code is None:
        failures.append("client exit code missing")
    elif client_exit_code == 0:
        failures.append(
            f"client exit code: expected nonzero after server crash, got {client_exit_code}"
        )

    attempts = _events_named(client_events, "CONNECT_ATTEMPT")
    oks = _events_named(client_events, "CONNECT_OK")
    fails = _events_named(client_events, "CONNECT_FAIL")
    ends = _events_named(client_events, "SESSION_END")

    if len(attempts) != 1:
        failures.append(
            f"client CONNECT_ATTEMPT: expected exactly 1, got {len(attempts)}"
        )
    if len(oks) != 1:
        failures.append(
            f"client CONNECT_OK: expected exactly 1, got {len(oks)}"
        )
    if len(fails) != 0:
        failures.append(
            f"client CONNECT_FAIL: expected 0, got {len(fails)}"
        )
    if len(ends) != 1:
        failures.append(
            f"client SESSION_END: expected exactly 1, got {len(ends)}"
        )
    else:
        end = ends[0]
        reason = end.fields.get("reason")
        metrics["client_session_reason"] = reason
        if reason == "bye":
            failures.append(
                "client SESSION_END reason: expected non-bye after crash, got bye"
            )

        msg_sent = _require_int(
            end.fields, "messages_sent", failures, "client SESSION_END"
        )
        msg_recv = _require_int(
            end.fields, "messages_received", failures, "client SESSION_END"
        )
        if msg_sent is not None:
            metrics["client_messages_sent"] = msg_sent
        if msg_recv is not None:
            metrics["client_messages_received"] = msg_recv
            if msg_recv >= crash_count:
                failures.append(
                    "client messages_received: expected < crash_count "
                    f"{crash_count}, got {msg_recv}"
                )

    # Server: CONNECT required; SESSION_END/DISCONNECT must NOT be required
    # because SIGKILL prevents worker cleanup.
    if server_session is not None:
        sess = _session_events(server_events, server_session)
        connects = [e for e in sess if e.event == "CONNECT"]
        if len(connects) != 1:
            failures.append(
                f"server session {server_session} CONNECT: "
                f"expected exactly 1, got {len(connects)}"
            )
        # Explicitly do not require SESSION_END / DISCONNECT after SIGKILL.
        ends_srv = [e for e in sess if e.event == "SESSION_END"]
        discs = [e for e in sess if e.event == "DISCONNECT"]
        metrics["server_session_end_count"] = len(ends_srv)
        metrics["server_disconnect_count"] = len(discs)
    elif connect_observed:
        failures.append("server_session missing despite CONNECT observation")

    return failures, metrics


def assert_concurrent_load(
    clients_requested: int,
    messages_per_client: int,
    client_exit_codes: list[int | None],
    client_events_list: list[list[LogEvent]],
    server_events: list[LogEvent],
    duration_s: float,
) -> tuple[list[str], dict[str, Any]]:
    """Assert concurrent_load correctness and build aggregate metrics.

    Does not launch processes, print, or sleep.
    Aggregate bytes are client-observed TCP payload (bytes_sent + bytes_received).
    rtt_avg_of_avgs_us is the mean of per-client rtt_avg_us values (not a global mean).
    """
    failures: list[str] = []
    n = clients_requested
    m = messages_per_client
    metrics: dict[str, Any] = {
        "clients_requested": n,
        "messages_per_client": m,
        "total_expected_messages": n * m,
        "duration_s": duration_s,
    }

    if len(client_exit_codes) != n:
        failures.append(
            f"clients launched: expected {n}, got {len(client_exit_codes)}"
        )
    if len(client_events_list) != n:
        failures.append(
            f"client event lists: expected {n}, got {len(client_events_list)}"
        )
    if len(client_exit_codes) != len(client_events_list):
        failures.append(
            "client exit codes and event lists length mismatch: "
            f"{len(client_exit_codes)} vs {len(client_events_list)}"
        )

    metrics["clients_launched"] = len(client_exit_codes)
    launched = min(len(client_exit_codes), len(client_events_list))

    succeeded = 0
    total_completed = 0
    aggregate_bytes = 0
    rtt_mins: list[int] = []
    rtt_maxs: list[int] = []
    rtt_avgs: list[int] = []
    failed_clients: list[dict[str, Any]] = []

    for i in range(launched):
        exit_code = client_exit_codes[i]
        events = client_events_list[i]
        prefix = f"client[{i}]"
        client_fail_before = len(failures)

        if exit_code != 0:
            failures.append(f"{prefix} exit code: expected 0, got {exit_code!r}")

        attempts = _events_named(events, "CONNECT_ATTEMPT")
        oks = _events_named(events, "CONNECT_OK")
        fails = _events_named(events, "CONNECT_FAIL")
        ends = _events_named(events, "SESSION_END")

        if len(attempts) != 1:
            failures.append(
                f"{prefix} CONNECT_ATTEMPT: expected exactly 1, got {len(attempts)}"
            )
        if len(oks) != 1:
            failures.append(
                f"{prefix} CONNECT_OK: expected exactly 1, got {len(oks)}"
            )
        if len(fails) != 0:
            failures.append(
                f"{prefix} CONNECT_FAIL: expected 0, got {len(fails)}"
            )
        if len(ends) != 1:
            failures.append(
                f"{prefix} SESSION_END: expected exactly 1, got {len(ends)}"
            )
        else:
            end = ends[0]
            end_prefix = f"{prefix} SESSION_END"
            reason = end.fields.get("reason")
            if reason != "bye":
                failures.append(
                    f"{end_prefix} reason: expected bye, got {reason!r}"
                )

            msg_sent = _require_int(
                end.fields, "messages_sent", failures, end_prefix
            )
            msg_recv = _require_int(
                end.fields, "messages_received", failures, end_prefix
            )
            malformed = _require_int(
                end.fields, "malformed_messages", failures, end_prefix
            )

            bytes_sent: int | None = None
            bytes_recv: int | None = None
            raw_bs = end.fields.get("bytes_sent")
            raw_br = end.fields.get("bytes_received")
            if raw_bs is not None:
                try:
                    bytes_sent = int(raw_bs)
                except ValueError:
                    pass
            if raw_br is not None:
                try:
                    bytes_recv = int(raw_br)
                except ValueError:
                    pass

            if msg_sent is not None and msg_sent != m:
                failures.append(
                    f"{end_prefix} messages_sent: expected {m}, got {msg_sent}"
                )
            if msg_recv is not None and msg_recv != m:
                failures.append(
                    f"{end_prefix} messages_received: expected {m}, got {msg_recv}"
                )
            if malformed is not None and malformed != 0:
                failures.append(
                    f"{end_prefix} malformed_messages: expected 0, got {malformed}"
                )

            client_ok = (
                exit_code == 0
                and len(attempts) == 1
                and len(oks) == 1
                and len(fails) == 0
                and reason == "bye"
                and msg_sent == m
                and msg_recv == m
                and malformed == 0
            )
            if client_ok:
                succeeded += 1
                if msg_sent is not None:
                    total_completed += msg_sent
                if bytes_sent is not None and bytes_recv is not None:
                    aggregate_bytes += bytes_sent + bytes_recv
                for key, bucket in (
                    ("rtt_min_us", rtt_mins),
                    ("rtt_max_us", rtt_maxs),
                    ("rtt_avg_us", rtt_avgs),
                ):
                    raw = end.fields.get(key)
                    if raw is None:
                        continue
                    try:
                        bucket.append(int(raw))
                    except ValueError:
                        pass

        if len(failures) > client_fail_before:
            reason_parts = [
                f
                for f in failures[client_fail_before:]
                if f.startswith(prefix)
            ]
            short = reason_parts[0] if reason_parts else "client failed"
            if short.startswith(prefix + " "):
                short = short[len(prefix) + 1 :]
            failed_clients.append(
                {
                    "index": i,
                    "exit_code": exit_code,
                    "reason": short,
                }
            )

    metrics["clients_succeeded"] = succeeded
    metrics["clients_failed"] = max(0, n - succeeded)
    metrics["total_completed_messages"] = total_completed
    metrics["aggregate_bytes"] = aggregate_bytes
    # Client-observed TCP payload bytes (sent + received), not double-counted
    # with server-side bytes.
    if duration_s > 0:
        metrics["aggregate_messages_per_s"] = total_completed / duration_s
        metrics["aggregate_bytes_per_s"] = aggregate_bytes / duration_s
    else:
        metrics["aggregate_messages_per_s"] = 0.0
        metrics["aggregate_bytes_per_s"] = 0.0

    if rtt_mins:
        metrics["rtt_min_us"] = min(rtt_mins)
    if rtt_maxs:
        metrics["rtt_max_us"] = max(rtt_maxs)
    if rtt_avgs:
        metrics["rtt_avg_of_avgs_us"] = int(sum(rtt_avgs) / len(rtt_avgs))

    if failed_clients:
        metrics["failed_clients"] = failed_clients

    connects = _events_named(server_events, "CONNECT")
    all_session_ends = _events_named(server_events, "SESSION_END")
    quit_ends = [
        e for e in all_session_ends if e.fields.get("reason") == "quit"
    ]
    disconnects = _events_named(server_events, "DISCONNECT")

    if len(connects) != n:
        failures.append(
            f"server CONNECT: expected exactly {n}, got {len(connects)}"
        )
    if len(all_session_ends) != n:
        failures.append(
            f"server SESSION_END: expected exactly {n}, got {len(all_session_ends)}"
        )
    if len(quit_ends) != n:
        failures.append(
            "server SESSION_END reason=quit: "
            f"expected exactly {n}, got {len(quit_ends)}"
        )
    if len(disconnects) != n:
        failures.append(
            f"server DISCONNECT: expected exactly {n}, got {len(disconnects)}"
        )

    session_ids = sorted({e.session for e in connects})
    for sid in session_ids:
        sess = _session_events(server_events, sid)
        s_connects = [e for e in sess if e.event == "CONNECT"]
        s_ends = [e for e in sess if e.event == "SESSION_END"]
        s_discs = [e for e in sess if e.event == "DISCONNECT"]
        sp = f"server session {sid}"

        if len(s_connects) != 1:
            failures.append(
                f"{sp} CONNECT: expected exactly 1, got {len(s_connects)}"
            )
        if len(s_ends) != 1:
            failures.append(
                f"{sp} SESSION_END: expected exactly 1, got {len(s_ends)}"
            )
        if len(s_discs) != 1:
            failures.append(
                f"{sp} DISCONNECT: expected exactly 1, got {len(s_discs)}"
            )

        if len(s_ends) == 1:
            s_end = s_ends[0]
            end_prefix = f"{sp} SESSION_END"
            if s_end.fields.get("reason") != "quit":
                failures.append(
                    f"{end_prefix} reason: expected quit, "
                    f"got {s_end.fields.get('reason')!r}"
                )
            msg_sent = _require_int(
                s_end.fields, "messages_sent", failures, end_prefix
            )
            msg_recv = _require_int(
                s_end.fields, "messages_received", failures, end_prefix
            )
            malformed = _require_int(
                s_end.fields, "malformed_messages", failures, end_prefix
            )
            if msg_sent is not None and msg_sent != m:
                failures.append(
                    f"{end_prefix} messages_sent: expected {m}, got {msg_sent}"
                )
            if msg_recv is not None and msg_recv != m:
                failures.append(
                    f"{end_prefix} messages_received: expected {m}, got {msg_recv}"
                )
            if malformed is not None and malformed != 0:
                failures.append(
                    f"{end_prefix} malformed_messages: expected 0, got {malformed}"
                )

    # Process totals: any SESSION_END snapshot may hold the final values;
    # require that at least one matches the expected N/0/N/0.
    totals_ok = False
    for end in _events_named(server_events, "SESSION_END"):
        try:
            accepted = int(end.fields["total_connections_accepted"])
            spawn_fail = int(end.fields["total_spawn_failures"])
            sessions_ok = int(end.fields["total_sessions_ok"])
            sessions_failed = int(end.fields["total_sessions_failed"])
        except (KeyError, ValueError):
            continue
        if (
            accepted == n
            and spawn_fail == 0
            and sessions_ok == n
            and sessions_failed == 0
        ):
            totals_ok = True
            metrics["server_total_connections_accepted"] = accepted
            metrics["server_total_spawn_failures"] = spawn_fail
            metrics["server_total_sessions_ok"] = sessions_ok
            metrics["server_total_sessions_failed"] = sessions_failed
            break
    if not totals_ok:
        failures.append(
            "server process totals: no SESSION_END snapshot with "
            f"total_connections_accepted={n}, total_spawn_failures=0, "
            f"total_sessions_ok={n}, total_sessions_failed=0"
        )

    active_values: list[int] = []
    for ev in connects + disconnects:
        raw = ev.fields.get("active_connections")
        if raw is None or raw == "unknown":
            continue
        try:
            active_values.append(int(raw))
        except ValueError:
            failures.append(
                f"server {ev.event}: active_connections not an integer ({raw!r})"
            )

    if active_values:
        peak = max(active_values)
        metrics["peak_active_connections"] = peak
        if peak < 1:
            failures.append(
                f"peak_active_connections: expected >= 1, got {peak}"
            )
        if 0 not in active_values:
            failures.append(
                "active_connections: expected at least one observed value of 0"
            )
    else:
        failures.append(
            "active_connections: no numeric CONNECT/DISCONNECT snapshots found"
        )
        metrics["peak_active_connections"] = 0

    return failures, metrics
