from datetime import date, datetime

from risk_gateway.aktools import AkToolsUnavailable
from risk_gateway.datasets import DatasetContext
from risk_gateway.datasets.valuation import ValuationDataset
from risk_gateway.models import RiskQuery


class FakeClient:
    def __init__(self, bond_rows):
        self.bond_rows = bond_rows

    def get(self, function, params):
        if function == "stock_info_a_code_name":
            return [{"code": f"{index:06d}"} for index in range(20)]
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
    assert "PE TTM history contains nonpositive or missing values" in response.meta.history_gap_reason


def test_mixed_request_preserves_stock_values_and_builds_auditable_market_proxy():
    stocks = [f"stock:{index:06d}.SH" for index in range(20)]
    mixed = RiskQuery.from_raw(
        "20260717", "20260717",
        ",".join(["market:CN-A", "sector:SW1:801780", *stocks]),
    )

    result = ValuationDataset(context([
        {"日期": "2026-07-16", "中国国债收益率10年": 1.75},
    ])).fetch(mixed)

    assert len([row for row in result.data if row["objectType"] == "stock"]) == 20
    market = next(row for row in result.data if row["objectType"] == "market")
    assert market["objectId"] == "CN-A"
    assert market["proxy"] is True
    assert market["constituentCount"] == 20
    assert market["aggregateDefinition"] == "equalWeightEarningsYieldOfStableConstituents-v1"
    assert result.meta.history_complete is False
    assert "sector" in result.meta.history_gap_reason


def test_market_only_request_uses_a_stable_auditable_constituent_universe():
    result = ValuationDataset(context([
        {"日期": "2026-07-16", "中国国债收益率10年": 1.75},
    ])).fetch(RiskQuery.from_raw("20260717", "20260717", "market:CN-A"))

    assert len(result.data) == 1
    assert result.data[0]["objectType"] == "market"
    assert result.data[0]["constituentCount"] == 20
    assert result.data[0]["universeDefinition"] == "first50CurrentAshareCodesSorted-v1"
    assert result.data[0]["constituentUniversePointInTime"] is False
    assert result.data[0]["scoringEligible"] is False
    assert result.data[0]["qualityStatus"] == "insufficient_history"
    assert "point-in-time constituent" in result.data[0]["qualityReason"]
    assert result.meta.history_complete is True


def test_unavailable_stock_valuation_does_not_discard_other_stock_records():
    ctx = context([
        {"日期": "2026-07-16", "中国国债收益率10年": 1.75},
    ])

    def get(function, params):
        if function == "stock_zh_valuation_baidu":
            if params["symbol"] == "000001":
                raise AkToolsUnavailable("new or suspended stock has no valuation history")
            return [{"date": "2026-07-17", "value": 20}]
        if function == "bond_zh_us_rate":
            return [{"日期": "2026-07-16", "中国国债收益率10年": 1.75}]
        raise AssertionError(function)

    ctx.client.get = get

    result = ValuationDataset(ctx).fetch(RiskQuery.from_raw(
        "20260717", "20260717", "stock:000001.SZ,stock:600519.SH",
    ))

    assert result.meta.history_complete is False
    assert [row["objectId"] for row in result.data] == ["600519.SH"]
    assert result.data[0]["qualityStatus"] == "available"
    assert "000001.SZ" in result.meta.history_gap_reason


def test_partial_history_stock_is_audit_only_while_complete_stock_remains_available():
    ctx = context([
        {"日期": "2026-07-16", "中国国债收益率10年": 1.75},
    ])

    def get(function, params):
        if function == "stock_zh_valuation_baidu":
            if params["symbol"] == "000001":
                return [{"date": "2026-07-20", "value": 25}]
            return [{"date": "2026-07-17", "value": 20}]
        if function == "bond_zh_us_rate":
            return [{"日期": "2026-07-16", "中国国债收益率10年": 1.75}]
        raise AssertionError(function)

    ctx.client.get = get

    result = ValuationDataset(ctx).fetch(RiskQuery.from_raw(
        "20260717", "20260720", "stock:000001.SZ,stock:600519.SH",
    ))

    complete_stock = [
        row for row in result.data if row["objectId"] == "600519.SH"
    ]
    partial_stock = [
        row for row in result.data if row["objectId"] == "000001.SZ"
    ]
    assert len(complete_stock) == 2
    assert all(row["qualityStatus"] == "available" for row in complete_stock)
    assert len(partial_stock) == 1
    assert partial_stock[0]["qualityStatus"] == "insufficient_history"
    assert partial_stock[0]["scoringEligible"] is False
    assert "requested valuation window is incomplete" in partial_stock[0]["qualityReason"]
    assert result.meta.history_complete is False


def test_market_proxy_tolerates_unavailable_constituents_when_minimum_is_met():
    ctx = context([
        {"日期": "2026-07-16", "中国国债收益率10年": 1.75},
    ])

    def get(function, params):
        if function == "stock_info_a_code_name":
            return [{"code": f"{index:06d}"} for index in range(21)]
        if function == "stock_zh_valuation_baidu":
            if params["symbol"] == "000000":
                raise AkToolsUnavailable("one constituent timed out")
            return [{"date": "2026-07-17", "value": 20}]
        if function == "bond_zh_us_rate":
            return [{"日期": "2026-07-16", "中国国债收益率10年": 1.75}]
        raise AssertionError(function)

    ctx.client.get = get

    result = ValuationDataset(ctx).fetch(
        RiskQuery.from_raw("20260717", "20260717", "market:CN-A")
    )

    assert result.meta.history_complete is True
    assert result.data[0]["constituentCount"] == 20


def test_valuation_forward_fills_only_the_last_published_point():
    ctx = context([
        {"日期": "2026-07-16", "中国国债收益率10年": 1.75},
    ])

    def get(function, params):
        if function == "stock_zh_valuation_baidu":
            return [
                {"date": "2026-06-28", "value": 20},
                {"date": "2026-07-19", "value": 25},
            ]
        if function == "bond_zh_us_rate":
            return [{"日期": "2026-07-16", "中国国债收益率10年": 1.75}]
        raise AssertionError(function)

    ctx.client.get = get

    result = ValuationDataset(ctx).fetch(
        RiskQuery.from_raw("20260717", "20260717", "stock:600519.SH")
    )

    assert result.meta.history_complete is True
    assert result.data[0]["peTtm"] == 20.0
    assert result.data[0]["valuationSourceDate"] == "2026-06-28"
    assert result.data[0]["valuationAgeSessions"] >= 1
    assert result.data[0]["stalenessPolicy"] == "lastPublishedWithin20AshareSessions-v1"


def test_latest_session_without_a_known_next_open_is_reported_as_incomplete():
    latest_context = DatasetContext(
        client=FakeClient([
            {"日期": "2026-07-16", "中国国债收益率10年": 1.75},
        ]),
        fetched_at=datetime.fromisoformat("2026-07-17T20:00:00+08:00"),
        a_share_sessions=(date(2026, 7, 17),),
        source_version="AKShare-1.18.64",
    )

    result = ValuationDataset(latest_context).fetch(
        RiskQuery.from_raw("20260717", "20260717", "stock:600519.SH")
    )

    assert result.data == []
    assert result.meta.history_complete is False
    assert "next A-share session" in result.meta.history_gap_reason
