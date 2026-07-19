from datetime import date

import pytest
from fastapi.testclient import TestClient

from risk_gateway.app import create_app
from risk_gateway.config import Settings


pytestmark = pytest.mark.filterwarnings(
    "ignore:Using `httpx` with `starlette.testclient` is deprecated"
)


class HealthyDependencies:
    def aktools_status(self):
        return "up"

    def cache_status(self):
        return "up"


class FakeClient:
    def get(self, function, params):
        if function == "stock_zh_a_hist":
            return self._bars(100)
        if function == "stock_zh_index_daily" and params["symbol"] == "sh000300":
            return self._bars(4000)
        if function == "stock_zh_index_daily" and params["symbol"] == "sh000016":
            return self._bars(2800)
        raise AssertionError((function, params))

    @staticmethod
    def _bars(base):
        return [
            {"date": "2026-07-17", "open": base, "close": base + 1, "volume": 100, "amount": 1000},
            {"date": "2026-07-20", "open": base + 1, "close": base + 2, "volume": 110, "amount": 1100},
        ]


def client(tmp_path):
    settings = Settings(
        aktools_base_url="http://aktools:8090", cache_dir=tmp_path, page_size=1,
    )
    app = create_app(
        settings,
        health_probe=HealthyDependencies(),
        client=FakeClient(),
        calendar_provider=lambda _query: (
            date(2026, 7, 17), date(2026, 7, 20), date(2026, 7, 21),
        ),
    )
    return TestClient(app)


def test_api_pages_market_daily_with_stable_cursor(tmp_path):
    api = client(tmp_path)
    params = {
        "start_date": "20260717", "end_date": "20260720", "objects": "stock:600519.SH",
    }

    first = api.get("/api/risk/market-daily", params=params)
    assert first.status_code == 200
    assert len(first.json()["data"]) == 1
    assert first.json()["meta"]["nextCursor"]

    params["cursor"] = first.json()["meta"]["nextCursor"]
    second = api.get("/api/risk/market-daily", params=params)
    assert second.status_code == 200
    assert second.json()["data"][0]["tradeDate"] == "2026-07-20"
    assert second.json()["meta"]["nextCursor"] is None


def test_etf_api_returns_explicit_history_gap_without_querying_nav(tmp_path):
    api = client(tmp_path)

    result = api.get("/api/risk/etf-redemption", params={
        "start_date": "20260717", "end_date": "20260717", "objects": "market:CN-A",
    })

    assert result.status_code == 200
    assert result.json()["data"] == []
    assert result.json()["meta"]["historyComplete"] is False
    assert "redemption" in result.json()["meta"]["historyGapReason"]


def test_api_maps_invalid_query_to_400(tmp_path):
    api = client(tmp_path)

    response = api.get("/api/risk/market-daily", params={
        "start_date": "20260720", "end_date": "20260717", "objects": "market:CN-A",
    })

    assert response.status_code == 400
    assert response.json()["error"] == "invalid_query"
