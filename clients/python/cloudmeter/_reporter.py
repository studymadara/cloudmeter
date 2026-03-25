"""
In-process metric buffer.

Replaces the old sidecar HTTP reporter. Metrics are stored in memory
and consumed by the native cost projector. All calls are synchronous
and never throw — must never crash the host application.
"""

import time

WARMUP_SECONDS = 30  # first 30 s after start_recording() are tagged as warmup

_buffer: list = []
_recording: bool = False
_recording_start: float = 0.0


def report(
    route: str,
    method: str,
    status: int,
    duration_ms: int,
    egress_bytes: int = 0,
) -> None:
    try:
        if _recording:
            now = time.time()
            _buffer.append(
                {
                    "route": route,
                    "method": method.upper(),
                    "status": status,
                    "duration_ms": duration_ms,
                    "egress_bytes": egress_bytes,
                    "ts": now,
                    "warmup": now < _recording_start + WARMUP_SECONDS,
                }
            )
    except Exception:
        pass  # always silent


def start_recording() -> None:
    global _recording, _recording_start
    _buffer.clear()
    _recording = True
    _recording_start = time.time()


def stop_recording() -> None:
    global _recording
    _recording = False


def get_metrics() -> list:
    return list(_buffer)


def clear() -> None:
    global _recording
    _buffer.clear()
    _recording = False
