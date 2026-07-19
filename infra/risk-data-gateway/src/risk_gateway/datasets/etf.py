from risk_gateway.datasets import DatasetContext, response
from risk_gateway.models import GatewayResponse, RiskQuery


HISTORY_GAP = "free_source_has_no_historical_etf_redemption_fact"


class EtfRedemptionDataset:
    def __init__(self, context: DatasetContext):
        self.context = context

    def fetch(self, query: RiskQuery) -> GatewayResponse:
        del query
        return response(
            self.context,
            [],
            source="AKTools:no-free-historical-etf-redemption-source",
            calculation_version="etf-redemption-fact-v1",
            complete=False,
            reason=HISTORY_GAP,
            earliest=None,
        )
