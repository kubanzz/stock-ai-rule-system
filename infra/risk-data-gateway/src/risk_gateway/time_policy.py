from dataclasses import dataclass
from bisect import bisect_left, bisect_right
from datetime import date, datetime, time
from functools import lru_cache
from zoneinfo import ZoneInfo


TIME_POLICY_VERSION = "cn-a-pit-v1"
SHANGHAI = ZoneInfo("Asia/Shanghai")


@dataclass(frozen=True)
class AvailabilityPoint:
    observed_at: datetime
    available_at: datetime
    policy_version: str = TIME_POLICY_VERSION

    def __post_init__(self) -> None:
        _require_aware(self.observed_at)
        _require_aware(self.available_at)
        if self.observed_at > self.available_at:
            raise ValueError("observed_at must not be after available_at")


@dataclass(frozen=True)
class AlignedAvailability(AvailabilityPoint):
    trade_date: date = date.min


def market_times(trade_date: date) -> AvailabilityPoint:
    """Return conservative point-in-time timestamps for an A-share daily bar."""
    return AvailabilityPoint(
        observed_at=datetime.combine(trade_date, time(15, 0), SHANGHAI),
        available_at=datetime.combine(trade_date, time(15, 30), SHANGHAI),
    )


def dated_value_times(
    observed_date: date,
    a_share_sessions: tuple[date, ...] | list[date],
) -> AvailabilityPoint:
    """Treat date-only valuation, financing and announcement data as next-open data."""
    next_session = _next_session(observed_date, a_share_sessions)
    return AvailabilityPoint(
        observed_at=datetime.combine(observed_date, time(23, 59, 59), SHANGHAI),
        available_at=datetime.combine(next_session, time(9, 0), SHANGHAI),
    )


def exact_event_times(event_at: datetime) -> AvailabilityPoint:
    """Preserve an exact source publication timestamp without inventing a delay."""
    _require_aware(event_at)
    return AvailabilityPoint(observed_at=event_at, available_at=event_at)


def align_global_to_a_share(
    market_close_at: datetime,
    a_share_sessions: tuple[date, ...] | list[date],
) -> AlignedAvailability:
    """Map a known global close to the first A-share session not yet open."""
    _require_aware(market_close_at)
    observed_at = market_close_at.astimezone(SHANGHAI)
    sessions = _sorted_sessions(tuple(a_share_sessions))
    start_index = bisect_left(sessions, observed_at.date())
    for trade_date in sessions[start_index:start_index + 2]:
        available_at = datetime.combine(trade_date, time(9, 0), SHANGHAI)
        if available_at > observed_at:
            return AlignedAvailability(
                observed_at=observed_at,
                available_at=available_at,
                trade_date=trade_date,
            )
    raise ValueError("no later A-share session is available for alignment")


def _next_session(
    current_date: date,
    sessions: tuple[date, ...] | list[date],
) -> date:
    ordered = _sorted_sessions(tuple(sessions))
    index = bisect_right(ordered, current_date)
    if index < len(ordered):
        return ordered[index]
    raise ValueError("no later A-share session is available")


@lru_cache(maxsize=32)
def _sorted_sessions(sessions: tuple[date, ...]) -> tuple[date, ...]:
    return tuple(sorted(set(sessions)))


def _require_aware(value: datetime) -> None:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
