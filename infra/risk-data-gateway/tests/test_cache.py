from datetime import datetime, timezone

import pandas as pd
import pytest

from risk_gateway.cache import CachePartition, InvalidCachePartition, ParquetCache


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
