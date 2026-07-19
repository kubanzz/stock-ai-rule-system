from datetime import date, datetime

from risk_gateway.datasets import DatasetContext
from risk_gateway.datasets.valuation import ValuationDataset
from risk_gateway.models import RiskQuery


class FakeClient:
    def __init__(self, bond_rows):
        self.bond_rows = bond_rows

    def get(self, function, params):
        if function == "stock_zh_valuation_baidu":
            return [
                {"date": "2026-07-17", "value": 20},
                {"date": "2026-07-20", "value": 25},
            ]
        if function == "bond_zh_us_rate":
            return self.bond_rows
        raise AssertionError(function)


def context(bond_rows):
    return DatasetContext(
        client=FakeClient(bond_rows),
        fetched_at=datetime.fromisoformat("2026-07-22T10:00:00+08:00"),
        a_share_sessions=(date(2026, 7, 17), date(2026, 7, 20), date(2026, 7, 21)),
        source_version="AKShare-1.18.64",
    )


def query():
    return RiskQuery.from_raw("20260717", "20260720", "stock:600519.SH")


def test_valuation_emits_yields_with_conservative_availability():
    response = ValuationDataset(context([
        {"日期": "2026-07-16", "中国国债收益率10年": 1.75},
    ])).fetch(query())

    assert response.meta.history_complete is True
    assert response.data[0]["peTtm"] == 20.0
    assert response.data[0]["earningsYield"] == 0.05
    assert response.data[0]["riskFreeYield"] == 0.0175
    assert response.data[0]["availableAt"] == "2026-07-20T09:00:00+08:00"
    assert response.data[0]["availabilityPolicyVersion"] == "cn-a-pit-v1"


def test_valuation_requires_pe_and_risk_free_yield():
    response = ValuationDataset(context([])).fetch(query())

    assert response.meta.history_complete is False
    assert response.meta.insufficient_history is True
    assert response.meta.history_gap_reason == "risk-free yield history is incomplete"


def test_nonpositive_pe_is_not_turned_into_risk_premium():
    ctx = context([{"日期": "2026-07-16", "中国国债收益率10年": 1.75}])
    ctx.client.get = lambda function, params: (
        [{"date": "2026-07-17", "value": 0}]
        if function == "stock_zh_valuation_baidu"
        else [{"日期": "2026-07-16", "中国国债收益率10年": 1.75}]
    )

    response = ValuationDataset(ctx).fetch(query())

    assert response.data == []
    assert response.meta.history_gap_reason == "PE TTM history contains nonpositive or missing values"
