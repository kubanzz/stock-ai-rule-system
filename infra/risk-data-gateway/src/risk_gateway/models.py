from dataclasses import dataclass
from datetime import date, datetime
import hashlib
import json
import re
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class InvalidQuery(ValueError):
    pass


_OBJECT_PATTERNS = {
    "market": re.compile(r"^CN-A$"),
    "sector": re.compile(r"^SW1:\d{6}$"),
    "stock": re.compile(r"^\d{6}\.(?:SZ|SH|BJ)$"),
}


def _parse_date(raw: str, field_name: str) -> date:
    try:
        return datetime.strptime(raw, "%Y%m%d").date()
    except (TypeError, ValueError) as exception:
        raise InvalidQuery(f"{field_name} must use yyyyMMdd") from exception


@dataclass(frozen=True)
class RiskQuery:
    start_date: date
    end_date: date
    object_keys: tuple[str, ...]
    cursor: str | None = None

    @classmethod
    def from_raw(
        cls,
        start_date: str,
        end_date: str,
        objects: str,
        cursor: str | None = None,
    ) -> "RiskQuery":
        start = _parse_date(start_date, "start_date")
        end = _parse_date(end_date, "end_date")
        if start > end:
            raise InvalidQuery("start_date must not be after end_date")
        if (end - start).days > 4050:
            raise InvalidQuery("date range must not exceed eleven years plus 31 days")

        raw_objects = [value.strip() for value in (objects or "").split(",")]
        if not raw_objects or any(not value for value in raw_objects):
            raise InvalidQuery("objects must not be empty")
        if len(set(raw_objects)) != len(raw_objects):
            raise InvalidQuery("objects must not contain duplicates")

        normalized = tuple(sorted(_validate_object(value) for value in raw_objects))
        return cls(start, end, normalized, cursor.strip() if cursor else None)

    def request_hash(self, dataset: str) -> str:
        payload = {
            "dataset": dataset,
            "startDate": self.start_date.isoformat(),
            "endDate": self.end_date.isoformat(),
            "objects": self.object_keys,
        }
        canonical = json.dumps(payload, ensure_ascii=True, separators=(",", ":"))
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _validate_object(value: str) -> str:
    object_type, separator, object_id = value.partition(":")
    pattern = _OBJECT_PATTERNS.get(object_type)
    if not separator or pattern is None or not pattern.fullmatch(object_id):
        raise InvalidQuery(f"unsupported risk object: {value}")
    return f"{object_type}:{object_id}"


class HistoryMeta(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    history_complete: bool = Field(alias="historyComplete")
    insufficient_history: bool = Field(alias="insufficientHistory")
    earliest_available_date: date | None = Field(alias="earliestAvailableDate")
    history_gap_reason: str | None = Field(alias="historyGapReason")
    next_cursor: str | None = Field(alias="nextCursor")
    source: str
    source_version: str = Field(alias="sourceVersion")
    calculation_version: str = Field(alias="calculationVersion")
    fetched_at: datetime = Field(alias="fetchedAt")


class GatewayResponse(BaseModel):
    data: list[dict[str, Any]]
    meta: HistoryMeta

    def payload(self) -> dict[str, Any]:
        return self.model_dump(by_alias=True, mode="json")
