from datetime import date, timedelta

import pandas as pd

from risk_gateway.datasets import DatasetContext, object_parts, requested_sessions, response
from risk_gateway.models import GatewayResponse, RiskQuery
from risk_gateway.time_policy import TIME_POLICY_VERSION, dated_value_times


SOURCE = "AKTools:stock_zh_valuation_baidu/bond_zh_us_rate"
CALCULATION_VERSION = "pe-risk-premium-v1"
MAX_BOND_FORWARD_SESSIONS = 5


class ValuationDataset:
    def __init__(self, context: DatasetContext):
        self.context = context

    def fetch(self, query: RiskQuery) -> GatewayResponse:
        sessions = requested_sessions(self.context, query.start_date, query.end_date)
        if not sessions:
            return self._incomplete([], "A-share trading calendar has no requested sessions")
        if any(object_parts(key)[0] != "stock" for key in query.object_keys):
            return self._incomplete(
                [], "historical constituent PE is unavailable for aggregate valuation",
            )

        bond = self._bond_frame(query.start_date)
        if bond.empty:
            return self._incomplete([], "risk-free yield history is incomplete")

        result: list[dict[str, object]] = []
        complete = True
        for object_key in query.object_keys:
            object_type, object_id = object_parts(object_key)
            pe = self._pe_frame(object_id)
            requested_pe = pe.loc[pe["date"].isin(sessions)]
            if requested_pe["peTtm"].isna().any() or (requested_pe["peTtm"] <= 0).any():
                return self._incomplete([], "PE TTM history contains nonpositive or missing values")
            if not set(sessions).issubset(set(requested_pe["date"])):
                complete = False

            for row in requested_pe.to_dict("records"):
                trade_date = row["date"]
                bond_row = self._latest_usable_bond(bond, trade_date)
                if bond_row is None:
                    complete = False
                    continue
                pe_ttm = float(row["peTtm"])
                pe_times = dated_value_times(trade_date, self.context.a_share_sessions)
                bond_times = dated_value_times(bond_row["date"], self.context.a_share_sessions)
                available_at = max(pe_times.available_at, bond_times.available_at)
                result.append({
                    "objectType": object_type,
                    "objectId": object_id,
                    "tradeDate": trade_date.isoformat(),
                    "peTtm": pe_ttm,
                    "earningsYield": 1.0 / pe_ttm,
                    "riskFreeYield": float(bond_row["riskFreeYield"]),
                    "proxy": False,
                    "qualityStatus": "available",
                    "observedAt": pe_times.observed_at.isoformat(),
                    "availableAt": available_at.isoformat(),
                    "availabilityPolicyVersion": TIME_POLICY_VERSION,
                })

        result.sort(key=lambda row: (str(row["tradeDate"]), str(row["objectId"])))
        if len(result) < len(sessions) * len(query.object_keys):
            complete = False
        reason = None if complete else "valuation or risk-free yield history is incomplete"
        earliest = min((date.fromisoformat(str(row["tradeDate"])) for row in result), default=None)
        return response(
            self.context, result, source=SOURCE, calculation_version=CALCULATION_VERSION,
            complete=complete, reason=reason, earliest=earliest,
        )

    def _pe_frame(self, object_id: str) -> pd.DataFrame:
        rows = self.context.client.get("stock_zh_valuation_baidu", {
            "symbol": object_id.split(".", 1)[0],
            "indicator": "市盈率(TTM)",
            "period": "全部",
        })
        frame = pd.DataFrame(rows)
        if frame.empty or not {"date", "value"}.issubset(frame.columns):
            return pd.DataFrame(columns=["date", "peTtm"])
        frame = frame[["date", "value"]].rename(columns={"value": "peTtm"})
        frame["date"] = pd.to_datetime(frame["date"], errors="raise").dt.date
        frame["peTtm"] = pd.to_numeric(frame["peTtm"], errors="coerce")
        return frame.sort_values("date").drop_duplicates("date", keep=False).reset_index(drop=True)

    def _bond_frame(self, start: date) -> pd.DataFrame:
        rows = self.context.client.get("bond_zh_us_rate", {
            "start_date": (start - timedelta(days=14)).strftime("%Y%m%d"),
        })
        frame = pd.DataFrame(rows)
        columns = {"日期", "中国国债收益率10年"}
        if frame.empty or not columns.issubset(frame.columns):
            return pd.DataFrame(columns=["date", "riskFreeYield"])
        frame = frame[["日期", "中国国债收益率10年"]].rename(
            columns={"日期": "date", "中国国债收益率10年": "riskFreeYield"}
        )
        frame["date"] = pd.to_datetime(frame["date"], errors="raise").dt.date
        frame["riskFreeYield"] = pd.to_numeric(frame["riskFreeYield"], errors="coerce") / 100.0
        return frame.dropna().sort_values("date").drop_duplicates("date", keep="last").reset_index(drop=True)

    def _latest_usable_bond(self, frame: pd.DataFrame, trade_date: date) -> dict[str, object] | None:
        eligible = frame.loc[frame["date"] <= trade_date]
        if eligible.empty:
            return None
        record = eligible.iloc[-1].to_dict()
        sessions_since = sum(record["date"] < value <= trade_date for value in self.context.a_share_sessions)
        return record if sessions_since <= MAX_BOND_FORWARD_SESSIONS else None

    def _incomplete(self, data: list[dict[str, object]], reason: str) -> GatewayResponse:
        earliest = min((date.fromisoformat(str(row["tradeDate"])) for row in data), default=None)
        return response(
            self.context, data, source=SOURCE, calculation_version=CALCULATION_VERSION,
            complete=False, reason=reason, earliest=earliest,
        )
