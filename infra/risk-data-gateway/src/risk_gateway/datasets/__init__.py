from dataclasses import dataclass
from datetime import date, datetime
from typing import Protocol

from risk_gateway.models import GatewayResponse, HistoryMeta


class SourceClient(Protocol):
    def get(self, function: str, params: dict[str, object]) -> list[dict[str, object]]: ...


@dataclass
class DatasetContext:
    client: SourceClient
    fetched_at: datetime
    a_share_sessions: tuple[date, ...]
    source_version: str

    def __post_init__(self) -> None:
        if self.fetched_at.tzinfo is None or self.fetched_at.utcoffset() is None:
            raise ValueError("fetched_at must be timezone-aware")
        self.a_share_sessions = tuple(sorted(set(self.a_share_sessions)))


def response(
    context: DatasetContext,
    data: list[dict[str, object]],
    *,
    source: str,
    calculation_version: str,
    complete: bool,
    reason: str | None = None,
    earliest: date | None = None,
) -> GatewayResponse:
    meta = HistoryMeta(
        historyComplete=complete,
        insufficientHistory=not complete,
        earliestAvailableDate=earliest,
        historyGapReason=reason,
        nextCursor=None,
        source=source,
        sourceVersion=context.source_version,
        calculationVersion=calculation_version,
        fetchedAt=context.fetched_at,
    )
    return GatewayResponse(data=data, meta=meta)


def object_parts(object_key: str) -> tuple[str, str]:
    object_type, object_id = object_key.split(":", 1)
    return object_type, object_id


def requested_sessions(context: DatasetContext, start: date, end: date) -> tuple[date, ...]:
    return tuple(value for value in context.a_share_sessions if start <= value <= end)
