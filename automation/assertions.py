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
