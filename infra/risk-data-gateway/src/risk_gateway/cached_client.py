from datetime import datetime
import hashlib
import json
from typing import Callable

import pandas as pd

from risk_gateway.cache import CachePartition, ParquetCache
from risk_gateway.datasets import SourceClient


class CachedAkToolsClient:
    """Persist exact public-source responses so retries never refetch completed work."""

    def __init__(
        self,
        source: SourceClient,
        cache: ParquetCache,
        *,
        fetched_at: Callable[[], datetime],
    ):
        self._source = source
        self._cache = cache
        self._fetched_at = fetched_at

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
        cached = self._cache.get(partition)
        if cached is not None and cached.manifest.get("metadata", {}).get("request") == canonical:
            return cached.frame.where(pd.notna(cached.frame), None).to_dict("records")

        rows = self._source.get(function, params)
        self._cache.put(
            partition,
            pd.DataFrame(rows),
            source=f"AKTools:{function}",
            fetched_at=fetched_at,
            metadata={"request": canonical},
        )
        return rows
