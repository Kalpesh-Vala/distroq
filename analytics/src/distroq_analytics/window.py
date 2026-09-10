"""UTC half-open time windows.

Every analytics command is scoped by ``[start, end)`` in UTC. Two rules make this
reproducible rather than merely conventional:

* An offset is mandatory. A naive timestamp is rejected rather than assumed to be
  UTC or assumed to be local, because either assumption silently moves rows between
  days depending on which machine ran the export.
* The interval is half-open. ``end`` belongs to the next window, so consecutive
  windows tile the timeline exactly once with no row counted twice and none skipped.

The run ID is derived from the window, which is what makes a rerun of the same window
collide with its own previous output instead of quietly producing a second copy.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone

from .errors import InvalidWindowError

ISO_DISPLAY = "%Y-%m-%dT%H:%M:%S.%f%z"
RUN_ID_FORMAT = "%Y%m%dT%H%M%SZ"


def parse_instant(raw: str, label: str = "timestamp") -> datetime:
    """Parse an ISO-8601 instant that carries an explicit UTC offset."""
    if raw is None:
        raise InvalidWindowError(f"{label} is required and must be an ISO-8601 instant")
    if not isinstance(raw, str):
        raise InvalidWindowError(f"{label} must be a string, got {type(raw).__name__}")

    text = raw.strip()
    if not text:
        raise InvalidWindowError(f"{label} is required and must be an ISO-8601 instant")

    normalized = text
    if normalized.endswith(("Z", "z")):
        normalized = normalized[:-1] + "+00:00"

    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise InvalidWindowError(
            f"{label} {text!r} is not a valid ISO-8601 instant "
            f"(expected e.g. 2026-09-01T00:00:00Z): {exc}"
        ) from exc

    if parsed.tzinfo is None or parsed.tzinfo.utcoffset(parsed) is None:
        raise InvalidWindowError(
            f"{label} {text!r} has no UTC offset. Naive timestamps are rejected because "
            "the result would depend on the timezone of whichever machine ran the export. "
            "Append 'Z' or an explicit offset."
        )

    return parsed.astimezone(timezone.utc)


def format_instant(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


@dataclass(frozen=True)
class TimeWindow:
    """A half-open UTC interval ``[start, end)``."""

    start: datetime
    end: datetime

    def __post_init__(self) -> None:
        for name, value in (("start", self.start), ("end", self.end)):
            if not isinstance(value, datetime):
                raise InvalidWindowError(f"{name} must be a datetime")
            if value.tzinfo is None or value.tzinfo.utcoffset(value) is None:
                raise InvalidWindowError(f"{name} must be timezone-aware")
            if value.utcoffset().total_seconds() != 0:
                raise InvalidWindowError(f"{name} must already be normalized to UTC")
        if self.start >= self.end:
            raise InvalidWindowError(
                f"start must be strictly before end; got start={format_instant(self.start)} "
                f"end={format_instant(self.end)}. The interval is half-open, so an empty "
                "window can never select a row."
            )

    @classmethod
    def parse(cls, start_raw: str, end_raw: str) -> "TimeWindow":
        return cls(parse_instant(start_raw, "start"), parse_instant(end_raw, "end"))

    def contains(self, instant: datetime) -> bool:
        """Half-open membership: ``start <= instant < end``."""
        if instant.tzinfo is None or instant.tzinfo.utcoffset(instant) is None:
            raise InvalidWindowError("membership test requires a timezone-aware instant")
        moment = instant.astimezone(timezone.utc)
        return self.start <= moment < self.end

    @property
    def run_id(self) -> str:
        """Deterministic identity for this window.

        Deliberately not random. A rerun of the same window must land on the same
        directory so that it collides and has to be told, explicitly, to overwrite.
        """
        return (
            f"{self.start.strftime(RUN_ID_FORMAT)}__{self.end.strftime(RUN_ID_FORMAT)}"
        )

    @property
    def start_sql(self) -> str:
        return self._sql(self.start)

    @property
    def end_sql(self) -> str:
        return self._sql(self.end)

    @staticmethod
    def _sql(value: datetime) -> str:
        # Rendered from a parsed datetime, never from raw user text, so no operator
        # input reaches the SQL string.
        return value.strftime("%Y-%m-%d %H:%M:%S.%f+00")

    def as_dict(self) -> dict[str, str]:
        return {
            "start": format_instant(self.start),
            "end": format_instant(self.end),
            "interval": "[start, end)",
            "timezone": "UTC",
        }

    def __str__(self) -> str:
        return f"[{format_instant(self.start)}, {format_instant(self.end)})"
