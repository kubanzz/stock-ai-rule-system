from datetime import datetime, timedelta, timezone

import pandas as pd
import pytest

from risk_gateway.cache import (
    CachePartition,
    DerivedResponseCache,
    InvalidCachePartition,
    KeyedLockPool,
    ParquetCache,
)
from risk_gateway.models import GatewayResponse


def frame():
    return pd.DataFrame(
        [
            {"tradeDate": "2026-07-16", "close": 1500.0},
            {"tradeDate": "2026-07-17", "close": 1510.0},
        ]
    )


def test_cache_round_trip_preserves_manifest_and_rows(tmp_path):
    cache = ParquetCache(tmp_path)
    partition = CachePartition("market_daily", "600519.SH", 2026)

    manifest = cache.put(
        partition,
        frame(),
        source="aktools/akshare",
        fetched_at=datetime(2026, 7, 19, 10, 0, tzinfo=timezone.utc),
    )
    entry = cache.get(partition)

    assert manifest["rowCount"] == 2
    assert manifest["source"] == "aktools/akshare"
    assert entry is not None
    assert entry.frame.to_dict("records") == frame().to_dict("records")
    assert entry.manifest["sha256"] == manifest["sha256"]


def test_cache_quarantines_hash_mismatch(tmp_path):
    cache = ParquetCache(tmp_path)
    partition = CachePartition("market_daily", "600519.SH", 2026)
    cache.put(
        partition,
        frame(),
        source="aktools/akshare",
        fetched_at=datetime.now(timezone.utc),
    )
    data_path, _ = cache.paths(partition)
    data_path.write_bytes(data_path.read_bytes() + b"corrupt")

    assert cache.get(partition) is None
    assert len(list((tmp_path / "quarantine").iterdir())) == 2


@pytest.mark.parametrize(
    "partition",
    (
        ("../market", "600519.SH", 2026),
        ("market_daily", "../600519.SH", 2026),
        ("market_daily", "600519.SH", 1999),
    ),
)
def test_cache_rejects_unsafe_partition(partition):
    with pytest.raises(InvalidCachePartition):
        CachePartition(*partition)


def test_derived_response_cache_reuses_payload_until_ttl_expires(tmp_path):
    now = datetime(2026, 7, 19, 10, 0, tzinfo=timezone.utc)
    cache = DerivedResponseCache(tmp_path)
    request_hash = "a" * 64
    result = GatewayResponse.model_validate({
        "data": [{"tradeDate": "2026-07-17", "totalCount": 5000}],
        "meta": {
            "historyComplete": False,
            "insufficientHistory": True,
            "earliestAvailableDate": "2026-07-17",
            "historyGapReason": "one stock is unavailable",
            "nextCursor": None,
            "source": "test",
            "sourceVersion": "test-v1",
            "calculationVersion": "breadth-v1",
            "fetchedAt": now.isoformat(),
        },
    })

    cache.put("breadth", request_hash, result, stored_at=now, ttl=timedelta(hours=1))

    cached = cache.get("breadth", request_hash, now=now + timedelta(minutes=59))
    expired = cache.get("breadth", request_hash, now=now + timedelta(hours=1))

    assert cached is not None
    assert cached.payload() == result.payload()
    assert expired is None


def test_derived_response_cache_rejects_unsafe_key(tmp_path):
    cache = DerivedResponseCache(tmp_path)

    with pytest.raises(InvalidCachePartition):
        cache.get("../breadth", "not-a-hash", now=datetime.now(timezone.utc))


def test_keyed_lock_pool_releases_entry_after_exception():
    locks = KeyedLockPool()

    with pytest.raises(RuntimeError, match="failed"):
        with locks.acquire("request"):
            assert locks.entry_count == 1
            raise RuntimeError("failed")

    assert locks.entry_count == 0
