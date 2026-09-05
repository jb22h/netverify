"""Safe loopback netem helpers for NetVerify latency injection.

Uses non-interactive ``sudo -n tc`` for privileged add/delete.
Read-only inspection uses ``tc`` without sudo when possible.

Only installs netem when the current root qdisc is an explicitly recognized
safe default (e.g. noqueue). Never replaces or deletes qdiscs this process
did not install.
"""

from __future__ import annotations

import shutil
import subprocess


INTERFACE_LO = "lo"

# Root qdisc lines we treat as safe to install over with ``tc qdisc add``.
_SAFE_ROOT_MARKERS = ("noqueue",)


def find_tc() -> str | None:
    return shutil.which("tc")


def _run_tc(args: list[str], *, privileged: bool) -> subprocess.CompletedProcess[str]:
    tc = find_tc()
    if tc is None:
        raise FileNotFoundError("tc not found on PATH")
    cmd = [tc, *args]
    if privileged:
        cmd = ["sudo", "-n", *cmd]
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        timeout=15.0,
    )


def show_qdisc(interface: str = INTERFACE_LO) -> tuple[int, str, str]:
    """Return (exit_code, stdout, stderr) for ``tc qdisc show dev <iface>``.

    Prefers an unprivileged read. Does not use sudo.
    """
    tc = find_tc()
    if tc is None:
        return 127, "", "tc not found on PATH"
    completed = subprocess.run(
        [tc, "qdisc", "show", "dev", interface],
        capture_output=True,
        text=True,
        timeout=15.0,
    )
    return completed.returncode, completed.stdout or "", completed.stderr or ""


def qdisc_has_netem(show_stdout: str) -> bool:
    return "netem" in show_stdout.lower()


def qdisc_safe_for_install(interface: str = INTERFACE_LO) -> tuple[bool, str]:
    """Return (ok, detail). ok is True only for a recognized safe default root.

    Recognized safe state: root qdisc is ``noqueue`` (typical for lo).
    Empty output is treated as ambiguous and refused.
    Any netem or other custom root is refused.
    """
    rc, out, err = show_qdisc(interface)
    if rc != 0:
        return False, (
            f"failed to inspect qdisc on {interface} "
            f"(exit {rc}): {err.strip() or out.strip() or 'no output'}"
        )

    text = out.strip()
    if not text:
        return False, (
            f"{interface} qdisc show returned empty output; refusing to modify"
        )

    lower = text.lower()
    if "netem" in lower:
        return False, (
            f"{interface} already has a netem qdisc; refusing to modify. "
            f"Current state:\n{text}"
        )

    # Require every non-empty line to look like a recognized safe root.
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    for line in lines:
        line_l = line.lower()
        if not any(marker in line_l for marker in _SAFE_ROOT_MARKERS):
            return False, (
                f"{interface} has a non-default or unrecognized qdisc; "
                f"refusing to modify. Current state:\n{text}"
            )
        # Root entry should mention root / noqueue.
        if "noqueue" in line_l and "root" not in line_l and "0:" not in line_l:
            # Still accept typical "qdisc noqueue 0: root ..." forms; if
            # noqueue appears we already matched. Keep strict on foreign types.
            pass

    return True, text


def add_delay(interface: str, delay_ms: int) -> tuple[bool, str]:
    """``sudo -n tc qdisc add dev <iface> root netem delay <N>ms``.

    Uses add (not replace) so an unexpected existing root causes failure.
    """
    try:
        completed = _run_tc(
            [
                "qdisc",
                "add",
                "dev",
                interface,
                "root",
                "netem",
                "delay",
                f"{delay_ms}ms",
            ],
            privileged=True,
        )
    except FileNotFoundError as exc:
        return False, str(exc)
    except subprocess.TimeoutExpired:
        return False, "timed out running privileged tc qdisc add"

    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout or "").strip()
        if "password" in detail.lower() or completed.returncode == 1:
            # sudo -n fails with status 1 when a password would be required.
            return False, (
                "privileged tc qdisc add failed "
                f"(exit {completed.returncode}): {detail or 'no output'}. "
                "Non-interactive sudo (sudo -n) is required for latency_injection."
            )
        return False, (
            f"tc qdisc add failed (exit {completed.returncode}): "
            f"{detail or 'no output'}"
        )
    return True, "ok"


def remove_delay(interface: str) -> tuple[bool, str]:
    """``sudo -n tc qdisc del dev <iface> root``."""
    try:
        completed = _run_tc(
            ["qdisc", "del", "dev", interface, "root"],
            privileged=True,
        )
    except FileNotFoundError as exc:
        return False, str(exc)
    except subprocess.TimeoutExpired:
        return False, "timed out running privileged tc qdisc del"

    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout or "").strip()
        return False, (
            f"tc qdisc del failed (exit {completed.returncode}): "
            f"{detail or 'no output'}"
        )
    return True, "ok"


def verify_clean(interface: str = INTERFACE_LO) -> tuple[bool, str]:
    """True only if netem is gone and the root qdisc is again a safe default."""
    rc, out, err = show_qdisc(interface)
    if rc != 0:
        return False, (
            f"post-cleanup qdisc inspection failed (exit {rc}): "
            f"{err.strip() or out.strip() or 'no output'}"
        )
    if qdisc_has_netem(out):
        return False, f"netem still present after cleanup:\n{out.strip()}"
    ok, detail = qdisc_safe_for_install(interface)
    if not ok:
        return False, f"interface not restored to safe default after cleanup: {detail}"
    return True, out.strip()
