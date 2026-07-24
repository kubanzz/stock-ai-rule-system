from datetime import date, datetime

from risk_gateway.datasets import DatasetContext
from risk_gateway.datasets.etf import EtfRedemptionDataset
from risk_gateway.models import RiskQuery


def test_etf_dataset_never_uses_nav_as_redemption():
    class ExplodingClient:
        def get(self, function, params):
            raise AssertionError("ETF NAV/order-flow source must not be queried")

    context = DatasetContext(
        client=ExplodingClient(),
        fetched_at=datetime.fromisoformat("2026-07-21T10:00:00+08:00"),
        a_share_sessions=(date(2026, 7, 17),),
        source_version="AKShare-1.18.64",
    )
    query = RiskQuery.from_raw("20260717", "20260717", "market:CN-A")

    response = EtfRedemptionDataset(context).fetch(query)

    assert response.data == []
    assert response.meta.history_complete is False
    assert response.meta.history_gap_reason == "free_source_has_no_historical_etf_redemption_fact"
