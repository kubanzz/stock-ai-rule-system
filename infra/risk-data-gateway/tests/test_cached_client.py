from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from threading import Barrier, Lock

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


def test_current_unbounded_request_refreshes_after_ttl(tmp_path):
    source = CountingClient()
    now = [datetime(2026, 7, 19, tzinfo=timezone.utc)]
    client = CachedAkToolsClient(
        source,
        ParquetCache(tmp_path),
        fetched_at=lambda: now[0],
        current_ttl=timedelta(hours=24),
    )

    client.get("stock_zh_index_daily", {"symbol": "sh000300"})
    now[0] += timedelta(hours=25)
    client.get("stock_zh_index_daily", {"symbol": "sh000300"})

    assert source.calls == 2


def test_closed_historical_request_remains_immutable_after_current_ttl(tmp_path):
    source = CountingClient()
    now = [datetime(2026, 7, 19, tzinfo=timezone.utc)]
    client = CachedAkToolsClient(
        source,
        ParquetCache(tmp_path),
        fetched_at=lambda: now[0],
        current_ttl=timedelta(hours=1),
    )
    params = {"symbol": "sh600519", "end_date": "20260717"}

    client.get("stock_zh_a_daily", params)
    now[0] += timedelta(days=2)
    client.get("stock_zh_a_daily", params)

    assert source.calls == 1


def test_identical_concurrent_raw_misses_are_fetched_once(tmp_path):
    class ConcurrentCountingClient(CountingClient):
        def __init__(self):
            super().__init__()
            self.guard = Lock()

        def get(self, function, params):
            with self.guard:
                self.calls += 1
            return [{"date": "2026-07-17", "close": 100.0}]

    source = ConcurrentCountingClient()
    client = CachedAkToolsClient(
        source,
        ParquetCache(tmp_path),
        fetched_at=lambda: datetime(2026, 7, 19, tzinfo=timezone.utc),
    )
    start = Barrier(4)

    def request(_value):
        start.wait()
        return client.get("stock_zh_index_daily", {"symbol": "sh000300"})

    with ThreadPoolExecutor(max_workers=4) as executor:
        results = list(executor.map(request, range(4)))

    assert results == [results[0]] * 4
    assert source.calls == 1
