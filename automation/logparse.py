"""Parse Phase 4 NetVerify structured log lines."""

from __future__ import annotations

import shlex
from dataclasses import dataclass, field


@dataclass
class LogEvent:
    timestamp: str
    component: str
    session: str
    event: str
    fields: dict[str, str] = field(default_factory=dict)


def parse_log_text(text: str) -> list[LogEvent]:
    """Parse NetVerify structured log text into LogEvent objects.

    Expected line format:
        <TIMESTAMP> <COMPONENT> session=<ID> event=<EVENT> [key=value ...]

    Uses shlex.split so quoted values such as error_text="Connection refused"
    are treated as a single token. Non-matching lines are skipped.
    """
    events: list[LogEvent] = []
    if not text:
        return events

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue

        try:
            tokens = shlex.split(line)
        except ValueError:
            continue

        if len(tokens) < 4:
            continue

        timestamp = tokens[0]
        component = tokens[1]

        session = None
        event_name = None
        fields: dict[str, str] = {}

        for token in tokens[2:]:
            if "=" not in token:
                continue
            key, value = token.split("=", 1)
            if key == "session" and session is None:
                session = value
            elif key == "event" and event_name is None:
                event_name = value
            else:
                fields[key] = value

        if session is None or event_name is None:
            continue

        events.append(
            LogEvent(
                timestamp=timestamp,
                component=component,
                session=session,
                event=event_name,
                fields=fields,
            )
        )

    return events
