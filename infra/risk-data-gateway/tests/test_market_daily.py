from datetime import date, datetime

from risk_gateway.datasets import DatasetContext
from risk_gateway.datasets.market_daily import MarketDailyDataset
from risk_gateway.models import RiskQuery


class FakeClient:
    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def get(self, function, params):
        self.calls.append((function, params))
        key = (function, params.get("symbol"))
        return self.responses[key]


def bars(*dates, base=10):
    return [
        {"日期": value, "开盘": base + index, "收盘": base + index + 0.5, "成交量": 100 + index, "成交额": 1000 + index}
        for index, value in enumerate(dates)
    ]


def context(target_dates=("2026-07-17", "2026-07-20")):
    client = FakeClient(
        {
            ("stock_zh_a_daily", "sh600519"): bars(*target_dates, base=100),
            ("stock_zh_index_daily", "sh000300"): bars("2026-07-17", "2026-07-20", base=4000),
            ("stock_zh_index_daily", "sh000016"): bars("2026-07-17", "2026-07-20", base=2800),
        }
    )
    return DatasetContext(
        client=client,
        fetched_at=datetime.fromisoformat("2026-07-21T10:00:00+08:00"),
        a_share_sessions=(date(2026, 7, 17), date(2026, 7, 20), date(2026, 7, 21)),
        source_version="AKShare-1.18.64",
    )


def query():
    return RiskQuery.from_raw("20260717", "20260720", "stock:600519.SH")


def test_market_daily_emits_java_contract():
    response = MarketDailyDataset(context()).fetch(query())
    row = response.data[-1]

    assert {
        "objectType", "objectId", "tradeDate", "open", "close", "volume",
        "benchmarkClose", "leaderClose", "observedAt", "availableAt",
    } <= row.keys()
    assert row["benchmarkDefinition"] == "CSI300"
    assert row["leaderDefinition"] == "SSE50"
    assert row["proxy"] is True
    assert response.meta.history_complete is True


def test_market_daily_marks_entire_batch_incomplete_when_target_has_gap():
    response = MarketDailyDataset(context(("2026-07-17",))).fetch(query())

    assert response.meta.history_complete is False
    assert response.meta.insufficient_history is True
    assert response.meta.history_gap_reason == "target, benchmark or leader daily history is incomplete"
