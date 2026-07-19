from datetime import date, datetime, timedelta
import math

import numpy as np

from risk_gateway.datasets import DatasetContext
from risk_gateway.datasets.cross_market import CrossMarketDataset
from risk_gateway.aktools import AkToolsUnavailable
from risk_gateway.models import RiskQuery


def business_dates(count, end=date(2026, 7, 20)):
    result = []
    current = end
    while len(result) < count:
        if current.weekday() < 5:
            result.append(current)
        current -= timedelta(days=1)
    return tuple(reversed(result))


DATES = business_dates(62)


def correlated_returns():
    y = np.array([0.005 * math.sin(index / 4) for index in range(60)], dtype=float)
    y[-1] = -0.004
    y -= y.mean()
    z = np.array([math.cos(index / 5) for index in range(60)], dtype=float)
    z -= z.mean()
    z -= y * np.dot(z, y) / np.dot(y, y)
    y /= y.std(ddof=1)
    z /= z.std(ddof=1)
    target = 0.625 * y + math.sqrt(1 - 0.625**2) * z
    return target * 0.003, y * 0.003


TARGET_RETURNS, LEADING_RETURNS = correlated_returns()


def closes(returns):
    values = [100.0]
    for change in returns:
        values.append(values[-1] * (1 + float(change)))
    return values


def bars(values):
    return [
        {"date": day.isoformat(), "open": value, "close": value, "volume": 100, "amount": 1000}
        for day, value in zip(DATES[-len(values):], values, strict=True)
    ]


class FakeClient:
    def get(self, function, params):
        if function == "stock_zh_index_daily":
            return bars(closes(np.concatenate(([0.0], TARGET_RETURNS))))
        changes = LEADING_RETURNS.copy()
        symbol = params["symbol"]
        if symbol in {".INX", ".IXIC", "HSI", "NKY"}:
            if symbol == "NKY":
                changes[-1] = 0.006
            else:
                changes[-1] = -0.006
            return bars(closes(np.concatenate((changes, [0.0]))))
        raise AssertionError((function, params))


def test_cross_market_uses_only_prior_available_global_closes():
    context = DatasetContext(
        client=FakeClient(),
        fetched_at=datetime.fromisoformat("2026-07-21T10:00:00+08:00"),
        a_share_sessions=DATES,
        source_version="AKShare-1.18.64",
    )
    query = RiskQuery.from_raw(
        DATES[-1].strftime("%Y%m%d"), DATES[-1].strftime("%Y%m%d"), "market:CN-A",
    )

    response = CrossMarketDataset(context).fetch(query)
    row = response.data[-1]

    assert row["observedMarketCount"] == 4
    assert row["confirmedDownMarketCount"] == 3
    assert abs(row["dynamicCorrelation"] - 0.625) < 0.02
    assert row["basketDefinition"] == "SP500,NASDAQ,HSI,NIKKEI225"
    assert row["availableAt"].endswith("09:00:00+08:00")
    assert response.meta.history_complete is True


def test_cross_market_is_incomplete_with_fewer_than_three_assets():
    context = DatasetContext(
        client=FakeClient(),
        fetched_at=datetime.fromisoformat("2026-07-21T10:00:00+08:00"),
        a_share_sessions=DATES,
        source_version="AKShare-1.18.64",
    )
    original = context.client.get
    context.client.get = lambda function, params: (
        (_ for _ in ()).throw(AkToolsUnavailable("unavailable"))
        if params.get("symbol") in {"HSI", "NKY"}
        else original(function, params)
    )
    query = RiskQuery.from_raw(
        DATES[-1].strftime("%Y%m%d"), DATES[-1].strftime("%Y%m%d"), "market:CN-A",
    )

    response = CrossMarketDataset(context).fetch(query)

    assert response.data == []
    assert response.meta.history_complete is False
    assert response.meta.history_gap_reason == "fewer than three global markets are available"
