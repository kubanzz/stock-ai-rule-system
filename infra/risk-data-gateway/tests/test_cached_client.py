from datetime import datetime, timezone

from risk_gateway.cache import ParquetCache
from risk_gateway.cached_client import CachedAkToolsClient


class CountingClient:
    def __init__(self):
        self.calls = 0

    def get(self, function, params):
        self.calls += 1
        return [{"date": "2026-07-17", "close": 100.0}]


def test_cached_client_reuses_identical_raw_request(tmp_path):
    source = CountingClient()
    client = CachedAkToolsClient(
        source,
        ParquetCache(tmp_path),
        fetched_at=lambda: datetime(2026, 7, 19, tzinfo=timezone.utc),
    )

    first = client.get("stock_zh_index_daily", {"symbol": "sh000300"})
    second = client.get("stock_zh_index_daily", {"symbol": "sh000300"})

    assert first == second
    assert source.calls == 1


def test_cached_client_keeps_different_parameter_requests_separate(tmp_path):
    source = CountingClient()
    client = CachedAkToolsClient(
        source,
        ParquetCache(tmp_path),
        fetched_at=lambda: datetime(2026, 7, 19, tzinfo=timezone.utc),
    )

    client.get("stock_zh_index_daily", {"symbol": "sh000300"})
    client.get("stock_zh_index_daily", {"symbol": "sh000016"})

    assert source.calls == 2
