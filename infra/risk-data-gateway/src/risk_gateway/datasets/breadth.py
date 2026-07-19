from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, timedelta

import pandas as pd

from risk_gateway.datasets import DatasetContext, requested_sessions, response
from risk_gateway.models import GatewayResponse, RiskQuery
from risk_gateway.series import InvalidSeries, normalize_market_frame
from risk_gateway.time_policy import TIME_POLICY_VERSION, market_times


SOURCE = "AKTools:stock_info_a_code_name/stock_zh_a_hist"
CALCULATION_VERSION = "breadth-current-universe-proxy-v1"
BREADTH_DEFINITION = "advanceDecline-250dHighLow-20dMA-v1"


class BreadthDataset:
    def __init__(
        self,
        context: DatasetContext,
        *,
        minimum_universe: int = 1000,
        max_concurrency: int = 4,
    ):
        if minimum_universe <= 0 or not 1 <= max_concurrency <= 16:
            raise ValueError("invalid breadth execution limits")
        self.context = context
        self.minimum_universe = minimum_universe
        self.max_concurrency = max_concurrency

    def fetch(self, query: RiskQuery) -> GatewayResponse:
        if query.object_keys != ("market:CN-A",):
            return self._incomplete([], "breadth supports only market:CN-A")
        sessions = requested_sessions(self.context, query.start_date, query.end_date)
        if not sessions:
            return self._incomplete([], "A-share trading calendar has no requested sessions")

        stocks = self._stock_symbols()
        histories = self._histories(stocks, query.start_date, query.end_date)
        if not histories:
            return self._incomplete([], "no observable stock history is available")
        combined = pd.concat(histories, ignore_index=True)
        enriched = combined.groupby("symbol", group_keys=False).apply(
            self._enrich, include_groups=False,
        ).reset_index(drop=True)

        rows: list[dict[str, object]] = []
        below_minimum = False
        for trade_date in sessions:
            day = enriched.loc[
                (enriched["date"] == trade_date)
                & enriched["previousClose"].notna()
                & enriched["priorHigh"].notna()
                & enriched["priorLow"].notna()
                & enriched["movingAverage"].notna()
            ]
            total = len(day)
            if total == 0:
                below_minimum = True
                continue
            below_minimum = below_minimum or total < self.minimum_universe
            times = market_times(trade_date)
            rows.append({
                "objectType": "market",
                "objectId": "CN-A",
                "tradeDate": trade_date.isoformat(),
                "advancingCount": int((day["close"] > day["previousClose"]).sum()),
                "decliningCount": int((day["close"] < day["previousClose"]).sum()),
                "newHighCount": int((day["close"] >= day["priorHigh"]).sum()),
                "newLowCount": int((day["close"] <= day["priorLow"]).sum()),
                "aboveMovingAverageCount": int((day["close"] > day["movingAverage"]).sum()),
                "totalCount": total,
                "breadthDefinition": BREADTH_DEFINITION,
                "universeDefinition": "currentListedStocksWithObservableHistoricalBars",
                "proxy": True,
                "qualityStatus": "available",
                "observedAt": times.observed_at.isoformat(),
                "availableAt": times.available_at.isoformat(),
                "availabilityPolicyVersion": TIME_POLICY_VERSION,
            })

        complete = len(rows) == len(sessions) and not below_minimum and len(histories) == len(stocks)
        reason = None
        if below_minimum:
            reason = f"daily observable universe is below {self.minimum_universe} stocks"
        elif len(histories) != len(stocks):
            reason = "one or more current stock histories are unavailable"
        elif len(rows) != len(sessions):
            reason = "one or more requested trading days have no breadth cross-section"
        earliest = date.fromisoformat(str(rows[0]["tradeDate"])) if rows else None
        return response(
            self.context, rows, source=SOURCE, calculation_version=CALCULATION_VERSION,
            complete=complete, reason=reason, earliest=earliest,
        )

    def _stock_symbols(self) -> tuple[str, ...]:
        rows = self.context.client.get("stock_info_a_code_name", {})
        symbols = {
            str(row.get("code") or row.get("代码") or "").strip()
            for row in rows
        }
        return tuple(sorted(symbol for symbol in symbols if len(symbol) == 6 and symbol.isdigit()))

    def _histories(self, symbols: tuple[str, ...], start: date, end: date) -> list[pd.DataFrame]:
        histories: list[pd.DataFrame] = []
        with ThreadPoolExecutor(max_workers=self.max_concurrency) as executor:
            futures = {executor.submit(self._history, symbol, start, end): symbol for symbol in symbols}
            for future in as_completed(futures):
                try:
                    frame = future.result()
                except (InvalidSeries, RuntimeError, ValueError):
                    continue
                if not frame.empty:
                    histories.append(frame)
        return histories

    def _history(self, symbol: str, start: date, end: date) -> pd.DataFrame:
        rows = self.context.client.get("stock_zh_a_hist", {
            "symbol": symbol,
            "period": "daily",
            "start_date": (start - timedelta(days=550)).strftime("%Y%m%d"),
            "end_date": end.strftime("%Y%m%d"),
            "adjust": "qfq",
        })
        frame = normalize_market_frame(rows)
        frame["symbol"] = symbol
        return frame[["symbol", "date", "close"]]

    @staticmethod
    def _enrich(group: pd.DataFrame) -> pd.DataFrame:
        ordered = group.sort_values("date").copy()
        ordered["previousClose"] = ordered["close"].shift(1)
        ordered["priorHigh"] = ordered["close"].shift(1).rolling(250, min_periods=60).max()
        ordered["priorLow"] = ordered["close"].shift(1).rolling(250, min_periods=60).min()
        ordered["movingAverage"] = ordered["close"].rolling(20, min_periods=20).mean()
        return ordered

    def _incomplete(self, data: list[dict[str, object]], reason: str) -> GatewayResponse:
        return response(
            self.context, data, source=SOURCE, calculation_version=CALCULATION_VERSION,
            complete=False, reason=reason, earliest=None,
        )
