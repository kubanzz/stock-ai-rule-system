from datetime import date, datetime, timedelta
import hashlib
import json
from typing import Callable

import pandas as pd

from risk_gateway.cache import CachePartition, KeyedLockPool, ParquetCache
from risk_gateway.datasets import SourceClient


class CachedAkToolsClient:
    """Persist exact public-source responses so retries never refetch completed work."""

    def __init__(
        self,
        source: SourceClient,
        cache: ParquetCache,
        *,
        fetched_at: Callable[[], datetime],
        current_ttl: timedelta = timedelta(hours=24),
    ):
        if current_ttl.total_seconds() <= 0:
            raise ValueError("current raw cache TTL must be positive")
        self._source = source
        self._cache = cache
        self._fetched_at = fetched_at
        self._current_ttl = current_ttl
        self._locks = KeyedLockPool()

    def get(self, function: str, params: dict[str, object]) -> list[dict[str, object]]:
        fetched_at = self._fetched_at()
        canonical = json.dumps(
            {"function": function, "params": dict(sorted((params or {}).items()))},
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
            default=str,
        )
        digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        partition = CachePartition("aktools_raw", f"{function}-{digest}", fetched_at.year)
        historical = self._closed_historical_request(params, fetched_at.date())
        with self._locks.acquire(f"{partition.object_key}:{partition.year}"):
            cached = self._cache.get(partition)
            if self._valid(cached, canonical, historical, fetched_at):
                return cached.frame.where(pd.notna(cached.frame), None).to_dict("records")

            rows = self._source.get(function, params)
            policy = "historical-immutable" if historical else "refreshable"
            metadata = {"request": canonical, "cachePolicy": policy}
            if not historical:
                metadata["expiresAt"] = (fetched_at + self._current_ttl).isoformat()
            self._cache.put(
                partition,
                pd.DataFrame(rows),
                source=f"AKTools:{function}",
                fetched_at=fetched_at,
                metadata=metadata,
            )
            return rows

    def _valid(self, cached, canonical: str, historical: bool, now: datetime) -> bool:
        if cached is None:
            return False
        metadata = cached.manifest.get("metadata", {})
        if metadata.get("request") != canonical:
            return False
        if historical:
            return True
        try:
            expires_at = datetime.fromisoformat(str(metadata["expiresAt"]))
        except (KeyError, TypeError, ValueError):
            return False
        return expires_at.tzinfo is not None and now < expires_at

    @staticmethod
    def _closed_historical_request(params: dict[str, object], today: date) -> bool:
        for key in ("end_date", "endDate", "end", "to"):
            raw = params.get(key)
            if raw is None:
                continue
            text = str(raw).strip()
            for pattern in ("%Y%m%d", "%Y-%m-%d"):
                try:
                    return datetime.strptime(text, pattern).date() < today
                except ValueError:
                    continue
            return False
        return False
