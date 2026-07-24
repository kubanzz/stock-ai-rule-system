from datetime import date

import pandas as pd

from risk_gateway.datasets import DatasetContext, object_parts, requested_sessions, response
from risk_gateway.models import GatewayResponse, RiskQuery
from risk_gateway.series import akshare_stock_symbol, normalize_market_frame
from risk_gateway.time_policy import TIME_POLICY_VERSION, market_times


SOURCE = "AKTools:stock_zh_a_daily/index_hist_sw/stock_zh_index_daily"
CALCULATION_VERSION = "market-daily-proxy-v1"
BENCHMARK_SYMBOL = "sh000300"
LEADER_SYMBOL = "sh000016"


class MarketDailyDataset:
    def __init__(self, context: DatasetContext):
        self.context = context

    def fetch(self, query: RiskQuery) -> GatewayResponse:
        sessions = requested_sessions(self.context, query.start_date, query.end_date)
        if not sessions:
            return response(
                self.context, [], source=SOURCE, calculation_version=CALCULATION_VERSION,
                complete=False, reason="A-share trading calendar has no requested sessions",
            )

        benchmark = self._index(BENCHMARK_SYMBOL, query.start_date, query.end_date)
        leader = self._index(LEADER_SYMBOL, query.start_date, query.end_date)
        all_rows: list[dict[str, object]] = []
        complete = self._covers(benchmark, sessions) and self._covers(leader, sessions)

        for object_key in query.object_keys:
            target = self._target(object_key, query.start_date, query.end_date)
            complete = complete and self._covers(target, sessions)
            merged = target.merge(
                benchmark[["date", "close"]].rename(columns={"close": "benchmarkClose"}),
                on="date", how="inner", validate="one_to_one",
            ).merge(
                leader[["date", "close"]].rename(columns={"close": "leaderClose"}),
                on="date", how="inner", validate="one_to_one",
            )
            object_type, object_id = object_parts(object_key)
            for record in merged.to_dict("records"):
                trade_date = record["date"]
                if trade_date not in sessions:
                    continue
                times = market_times(trade_date)
                all_rows.append({
                    "objectType": object_type,
                    "objectId": object_id,
                    "tradeDate": trade_date.isoformat(),
                    "open": float(record["open"]),
                    "close": float(record["close"]),
                    "volume": float(record["volume"]),
                    "benchmarkClose": float(record["benchmarkClose"]),
                    "leaderClose": float(record["leaderClose"]),
                    "benchmarkDefinition": "CSI300",
                    "leaderDefinition": "SSE50",
                    "proxy": True,
                    "qualityStatus": "available",
                    "observedAt": times.observed_at.isoformat(),
                    "availableAt": times.available_at.isoformat(),
                    "availabilityPolicyVersion": TIME_POLICY_VERSION,
                })

        all_rows.sort(key=lambda row: (str(row["tradeDate"]), str(row["objectType"]), str(row["objectId"])))
        earliest = min((date.fromisoformat(str(row["tradeDate"])) for row in all_rows), default=None)
        return response(
            self.context, all_rows, source=SOURCE, calculation_version=CALCULATION_VERSION,
            complete=complete,
            reason=None if complete else "target, benchmark or leader daily history is incomplete",
            earliest=earliest,
        )

    def _target(self, object_key: str, start: date, end: date) -> pd.DataFrame:
        object_type, object_id = object_parts(object_key)
        if object_type == "stock":
            rows = self.context.client.get("stock_zh_a_daily", {
                "symbol": akshare_stock_symbol(object_id),
                "start_date": start.strftime("%Y%m%d"),
                "end_date": end.strftime("%Y%m%d"),
                "adjust": "qfq",
            })
        elif object_type == "sector":
            rows = self.context.client.get("index_hist_sw", {
                "symbol": object_id.rsplit(":", 1)[-1], "period": "day",
            })
        elif object_type == "market" and object_id == "CN-A":
            rows = self.context.client.get("stock_zh_index_daily", {"symbol": "sh000001"})
        else:
            raise ValueError(f"unsupported market daily object: {object_key}")
        return self._within(normalize_market_frame(rows), start, end)

    def _index(self, symbol: str, start: date, end: date) -> pd.DataFrame:
        rows = self.context.client.get("stock_zh_index_daily", {"symbol": symbol})
        return self._within(normalize_market_frame(rows), start, end)

    @staticmethod
    def _within(frame: pd.DataFrame, start: date, end: date) -> pd.DataFrame:
        return frame.loc[frame["date"].between(start, end)].reset_index(drop=True)

    @staticmethod
    def _covers(frame: pd.DataFrame, sessions: tuple[date, ...]) -> bool:
        return set(sessions).issubset(set(frame["date"]))
