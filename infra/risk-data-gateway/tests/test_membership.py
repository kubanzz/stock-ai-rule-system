from datetime import date, datetime

from risk_gateway.aktools import AkToolsUnavailable
from risk_gateway.datasets import DatasetContext
from risk_gateway.datasets.membership import MembershipDataset
from risk_gateway.models import RiskQuery


class CurrentSnapshotOnly:
    def get(self, function, params):
        if function == "stock_industry_clf_hist_sw":
            raise AkToolsUnavailable("official file unavailable")
        if function == "sw_index_first_info":
            return [{"行业代码": "801010", "行业名称": "农林牧渔"}]
        if function == "index_component_sw":
            return [{"证券代码": "600519", "计入日期": "2021-12-13"}]
        raise AssertionError(function)


def context(client):
    return DatasetContext(
        client=client,
        fetched_at=datetime.fromisoformat("2026-07-21T10:00:00+08:00"),
        a_share_sessions=(date(2026, 7, 17), date(2026, 7, 20), date(2026, 7, 21)),
        source_version="AKShare-1.18.64",
    )


def test_membership_never_backdates_current_snapshot():
    query = RiskQuery.from_raw("20260717", "20260717", "stock:600519.SH")

    response = MembershipDataset(context(CurrentSnapshotOnly())).fetch(query)

    assert response.data == []
    assert response.meta.insufficient_history is True
    assert response.meta.history_gap_reason == "official SW1 history unavailable; current snapshot cannot be backdated"


def test_membership_uses_current_snapshot_for_latest_session_without_claiming_history():
    query = RiskQuery.from_raw("20260721", "20260721", "stock:600519.SH")

    response = MembershipDataset(context(CurrentSnapshotOnly())).fetch(query)

    assert response.data == [{
        "objectType": "stock",
        "objectId": "600519.SH",
        "sectorCode": "801010",
        "sectorName": "农林牧渔",
        "validFrom": "2026-07-21",
        "validTo": None,
        "qualityStatus": "available",
        "observedAt": "2026-07-21T10:00:00+08:00",
        "availableAt": "2026-07-21T10:00:00+08:00",
        "availabilityPolicyVersion": "cn-a-pit-v1",
    }]
    assert response.meta.history_complete is False
    assert response.meta.insufficient_history is True


def test_market_membership_request_returns_the_whole_current_sw1_universe():
    query = RiskQuery.from_raw("20260721", "20260721", "market:CN-A")

    response = MembershipDataset(context(CurrentSnapshotOnly())).fetch(query)

    assert response.data[0]["objectId"] == "600519.SH"
    assert response.data[0]["sectorCode"] == "801010"
    assert response.meta.history_complete is False


def test_membership_does_not_treat_leaf_classification_codes_as_sw1_indices():
    class LeafHistoryWithCurrentSnapshot(CurrentSnapshotOnly):
        def get(self, function, params):
            if function == "stock_industry_clf_hist_sw":
                return [{
                    "symbol": "600519",
                    "industry_code": "350301",
                    "start_date": "2021-12-13",
                    "update_time": "2025-12-15",
                }]
            return super().get(function, params)

    query = RiskQuery.from_raw("20260721", "20260721", "stock:600519.SH")

    response = MembershipDataset(context(LeafHistoryWithCurrentSnapshot())).fetch(query)

    assert response.data[0]["sectorCode"] == "801010"
    assert response.data[0]["sectorName"] == "农林牧渔"
    assert response.meta.history_complete is False


def test_official_membership_derives_nonoverlapping_validity_intervals():
    class OfficialHistory:
        def get(self, function, params):
            assert function == "stock_industry_clf_hist_sw"
            return [
                {"symbol": "600519", "industry_code": "801120", "start_date": "2016-01-01", "update_time": "2016-01-02"},
                {"symbol": "600519", "industry_code": "801130", "start_date": "2021-12-13", "update_time": "2021-12-14"},
            ]

    query = RiskQuery.from_raw("20200101", "20260717", "stock:600519.SH")

    response = MembershipDataset(context(OfficialHistory())).fetch(query)

    assert response.data[0]["validTo"] == "2021-12-12"
    assert response.data[1]["validTo"] is None
    assert response.data[1]["sectorCode"] == "801130"
    assert response.meta.history_complete is True
