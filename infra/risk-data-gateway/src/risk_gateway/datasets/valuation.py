from datetime import date, timedelta

import pandas as pd

from risk_gateway.aktools import AkToolsSchemaError, AkToolsUnavailable
from risk_gateway.datasets import DatasetContext, object_parts, requested_sessions, response
from risk_gateway.models import GatewayResponse, RiskQuery
from risk_gateway.time_policy import TIME_POLICY_VERSION, dated_value_times


SOURCE = "AKTools:stock_info_a_code_name/stock_zh_valuation_baidu/bond_zh_us_rate"
CALCULATION_VERSION = "pe-risk-premium-v2"
MAX_BOND_FORWARD_SESSIONS = 5
MAX_VALUATION_FORWARD_SESSIONS = 20
VALUATION_STALENESS_POLICY = "lastPublishedWithin20AshareSessions-v1"
MINIMUM_AGGREGATE_CONSTITUENTS = 20
MARKET_AGGREGATE_DEFINITION = "equalWeightEarningsYieldOfStableConstituents-v1"
MARKET_UNIVERSE_DEFINITION = "first50CurrentAshareCodesSorted-v1"
MARKET_UNIVERSE_SIZE = 50
CURRENT_UNIVERSE_QUALITY_REASON = (
    "historical market proxy has no point-in-time constituent evidence"
)


class ValuationDataset:
    def __init__(self, context: DatasetContext):
        self.context = context

    def fetch(self, query: RiskQuery) -> GatewayResponse:
        sessions = requested_sessions(self.context, query.start_date, query.end_date)
        if not sessions:
            return self._incomplete([], "A-share trading calendar has no requested sessions")

        bond = self._bond_frame(query.start_date)
        if bond.empty:
            return self._incomplete([], "risk-free yield history is incomplete")

        result: list[dict[str, object]] = []
        complete = True
        reasons: set[str] = set()
        stock_keys = tuple(
            key for key in query.object_keys if object_parts(key)[0] == "stock"
        )
        aggregate_types = {
            object_parts(key)[0] for key in query.object_keys
            if object_parts(key)[0] != "stock"
        }
        if "sector" in aggregate_types:
            complete = False
            reasons.add("historical sector constituent valuation is unavailable")

        market_requested = "market" in aggregate_types
        market_constituents = self._market_constituent_keys() if market_requested else ()
        rows_by_date: dict[date, list[dict[str, object]]] = {
            session: [] for session in sessions
        }
        direct_stock_set = set(stock_keys)
        market_constituent_set = set(market_constituents)
        for object_key in sorted(direct_stock_set | market_constituent_set):
            object_type, object_id = object_parts(object_key)
            directly_requested = object_key in direct_stock_set
            direct_records: list[dict[str, object]] = []
            try:
                pe = self._pe_frame(object_id)
            except (AkToolsUnavailable, AkToolsSchemaError):
                if directly_requested:
                    complete = False
                    reasons.add(f"{object_id} valuation history is unavailable")
                continue

            for trade_date in sessions:
                pe_row = self._latest_usable_pe(pe, trade_date)
                if pe_row is None:
                    if directly_requested:
                        complete = False
                        reasons.add(f"{object_id} valuation history is incomplete")
                    continue
                pe_ttm = float(pe_row["peTtm"])
                if pd.isna(pe_ttm) or pe_ttm <= 0:
                    if directly_requested:
                        complete = False
                        reasons.add("PE TTM history contains nonpositive or missing values")
                    continue
                bond_row = self._latest_usable_bond(bond, trade_date)
                if bond_row is None:
                    if directly_requested:
                        complete = False
                        reasons.add("valuation or risk-free yield history is incomplete")
                    continue
                valuation_source_date = pe_row["date"]
                valuation_age_sessions = self._sessions_since(
                    valuation_source_date, trade_date,
                )
                try:
                    pe_times = dated_value_times(
                        valuation_source_date, self.context.a_share_sessions,
                    )
                    bond_times = dated_value_times(
                        bond_row["date"], self.context.a_share_sessions,
                    )
                except ValueError:
                    if directly_requested:
                        complete = False
                        reasons.add("next A-share session is unavailable for valuation timing")
                    continue
                available_at = max(pe_times.available_at, bond_times.available_at)
                record = {
                    "objectType": object_type,
                    "objectId": object_id,
                    "tradeDate": trade_date.isoformat(),
                    "peTtm": pe_ttm,
                    "earningsYield": 1.0 / pe_ttm,
                    "riskFreeYield": float(bond_row["riskFreeYield"]),
                    "valuationSourceDate": valuation_source_date.isoformat(),
                    "valuationAgeSessions": valuation_age_sessions,
                    "stalenessPolicy": VALUATION_STALENESS_POLICY,
                    "proxy": False,
                    "constituentUniversePointInTime": True,
                    "scoringEligible": True,
                    "qualityReason": None,
                    "calculationVersion": CALCULATION_VERSION,
                    "qualityStatus": "available",
                    "observedAt": max(
                        pe_times.observed_at, bond_times.observed_at,
                    ).isoformat(),
                    "availableAt": available_at.isoformat(),
                    "availabilityPolicyVersion": TIME_POLICY_VERSION,
                }
                if directly_requested:
                    direct_records.append(record)
                if object_key in market_constituent_set:
                    rows_by_date[trade_date].append(record)
            if directly_requested:
                if len(direct_records) < len(sessions):
                    complete = False
                    reason = f"{object_id} requested valuation window is incomplete"
                    reasons.add(reason)
                    direct_records = [{
                        **record,
                        "scoringEligible": False,
                        "qualityReason": reason,
                        "qualityStatus": "insufficient_history",
                    } for record in direct_records]
                result.extend(direct_records)

        if market_requested:
            for trade_date in sessions:
                constituents = rows_by_date[trade_date]
                if len(constituents) < MINIMUM_AGGREGATE_CONSTITUENTS:
                    complete = False
                    reasons.add(
                        f"market valuation requires at least {MINIMUM_AGGREGATE_CONSTITUENTS} constituents"
                    )
                    continue
                earnings_yield = sum(
                    float(row["earningsYield"]) for row in constituents
                ) / len(constituents)
                risk_free_yield = sum(
                    float(row["riskFreeYield"]) for row in constituents
                ) / len(constituents)
                result.append({
                    "objectType": "market",
                    "objectId": "CN-A",
                    "tradeDate": trade_date.isoformat(),
                    "peTtm": 1.0 / earnings_yield,
                    "earningsYield": earnings_yield,
                    "riskFreeYield": risk_free_yield,
                    "valuationSourceDate": min(
                        str(row["valuationSourceDate"]) for row in constituents
                    ),
                    "valuationAgeSessions": max(
                        int(row["valuationAgeSessions"]) for row in constituents
                    ),
                    "stalenessPolicy": VALUATION_STALENESS_POLICY,
                    "proxy": True,
                    "constituentCount": len(constituents),
                    "aggregateDefinition": MARKET_AGGREGATE_DEFINITION,
                    "universeDefinition": MARKET_UNIVERSE_DEFINITION,
                    "constituentUniversePointInTime": False,
                    "scoringEligible": False,
                    "qualityReason": CURRENT_UNIVERSE_QUALITY_REASON,
                    "calculationVersion": CALCULATION_VERSION,
                    "qualityStatus": "insufficient_history",
                    "observedAt": max(str(row["observedAt"]) for row in constituents),
                    "availableAt": max(str(row["availableAt"]) for row in constituents),
                    "availabilityPolicyVersion": TIME_POLICY_VERSION,
                })

        result.sort(key=lambda row: (str(row["tradeDate"]), str(row["objectId"])))
        expected_direct_rows = len(sessions) * len(stock_keys)
        expected_market_rows = len(sessions) if market_requested else 0
        if len(result) < expected_direct_rows + expected_market_rows:
            complete = False
        reason = None if complete else "; ".join(sorted(reasons))
        if not complete and not reason:
            reason = "valuation or risk-free yield history is incomplete"
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

    def _market_constituent_keys(self) -> tuple[str, ...]:
        rows = self.context.client.get("stock_info_a_code_name", {})
        codes = sorted({
            str(row.get("code") or row.get("代码") or "").strip()
            for row in rows
        })
        valid = [code for code in codes if len(code) == 6 and code.isdigit()]
        return tuple(f"stock:{code}.SH" for code in valid[:MARKET_UNIVERSE_SIZE])

    def _latest_usable_pe(
            self, frame: pd.DataFrame, trade_date: date,
    ) -> dict[str, object] | None:
        eligible = frame.loc[frame["date"] <= trade_date]
        if eligible.empty:
            return None
        record = eligible.iloc[-1].to_dict()
        return (record if self._sessions_since(record["date"], trade_date)
                <= MAX_VALUATION_FORWARD_SESSIONS else None)

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

    def _sessions_since(self, source_date: date, trade_date: date) -> int:
        return sum(
            source_date < value <= trade_date
            for value in self.context.a_share_sessions
        )

    def _incomplete(self, data: list[dict[str, object]], reason: str) -> GatewayResponse:
        earliest = min((date.fromisoformat(str(row["tradeDate"])) for row in data), default=None)
        return response(
            self.context, data, source=SOURCE, calculation_version=CALCULATION_VERSION,
            complete=False, reason=reason, earliest=earliest,
        )
