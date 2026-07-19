from datetime import date, datetime, timedelta

from risk_gateway.datasets import DatasetContext
from risk_gateway.datasets.breadth import BreadthDataset
from risk_gateway.models import RiskQuery


def business_dates(count, end=date(2026, 7, 20)):
    result = []
    current = end
    while len(result) < count:
        if current.weekday() < 5:
            result.append(current)
        current -= timedelta(days=1)
    return tuple(reversed(result))


DATES = business_dates(61)


def history(symbol):
    baseline = [10.0] * 60
    if symbol == "000001":
        values = baseline + [11.0]
    elif symbol == "000002":
        values = [30.0] + [20.0] * 59 + [21.0]
    elif symbol == "000003":
        values = baseline + [9.0]
    elif symbol in {"000004", "000005"}:
        values = [5.0] + [10.0] * 59 + [9.0]
    else:
        values = baseline
    if symbol == "000006":
        values = values[:-1]
    return [
        {"日期": value.isoformat(), "开盘": close, "收盘": close, "成交量": 100, "成交额": 1000}
        for value, close in zip(DATES[:len(values)], values, strict=True)
    ]


class FakeClient:
    def get(self, function, params):
        if function == "stock_info_a_code_name":
            return [{"code": f"00000{index}", "name": str(index)} for index in range(1, 7)]
        if function == "stock_zh_a_daily":
            return history(params["symbol"][-6:])
        raise AssertionError(function)


def test_breadth_uses_only_observable_active_stocks():
    context = DatasetContext(
        client=FakeClient(),
        fetched_at=datetime.fromisoformat("2026-07-21T10:00:00+08:00"),
        a_share_sessions=DATES,
        source_version="AKShare-1.18.64",
    )
    query = RiskQuery.from_raw("20260720", "20260720", "market:CN-A")

    response = BreadthDataset(context, minimum_universe=5, max_concurrency=2).fetch(query)
    row = response.data[-1]

    assert row["advancingCount"] == 2
    assert row["decliningCount"] == 3
    assert row["newHighCount"] == 1
    assert row["newLowCount"] == 1
    assert row["aboveMovingAverageCount"] == 2
    assert row["totalCount"] == 5
    assert row["breadthDefinition"] == "advanceDecline-250dHighLow-20dMA-v1"
    assert row["proxy"] is True
    assert response.meta.history_complete is True


def test_breadth_is_incomplete_below_minimum_daily_universe():
    context = DatasetContext(
        client=FakeClient(),
        fetched_at=datetime.fromisoformat("2026-07-21T10:00:00+08:00"),
        a_share_sessions=DATES,
        source_version="AKShare-1.18.64",
    )
    query = RiskQuery.from_raw("20260720", "20260720", "market:CN-A")

    response = BreadthDataset(context, minimum_universe=1000).fetch(query)

    assert response.meta.history_complete is False
    assert response.meta.history_gap_reason == "daily observable universe is below 1000 stocks"
