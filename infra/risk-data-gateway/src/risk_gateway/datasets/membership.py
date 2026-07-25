from datetime import date, datetime, time, timedelta

import pandas as pd

from risk_gateway.aktools import AkToolsUnavailable
from risk_gateway.datasets import DatasetContext, response
from risk_gateway.models import GatewayResponse, RiskQuery
from risk_gateway.series import normalize_object_key
from risk_gateway.time_policy import SHANGHAI, TIME_POLICY_VERSION, dated_value_times


SOURCE = "AKTools:stock_industry_clf_hist_sw"
CALCULATION_VERSION = "sw1-membership-validity-v1"
FALLBACK_REASON = "official SW1 history unavailable; current snapshot cannot be backdated"


class MembershipDataset:
    def __init__(self, context: DatasetContext):
        self.context = context

    def fetch(self, query: RiskQuery) -> GatewayResponse:
        requested = tuple(key for key in query.object_keys if key.startswith("stock:"))
        if not requested:
            return self._incomplete([], "SW1 membership requires stock objects")
        try:
            official_rows = self.context.client.get("stock_industry_clf_hist_sw", {})
        except AkToolsUnavailable:
            return self._current_snapshot(query, requested)
        return self._official(query, requested, official_rows)

    def _official(
        self,
        query: RiskQuery,
        requested: tuple[str, ...],
        raw_rows: list[dict[str, object]],
    ) -> GatewayResponse:
        wanted = {key.split(":", 1)[1].split(".", 1)[0]: key for key in requested}
        parsed = []
        for row in raw_rows:
            symbol = str(row.get("symbol") or row.get("股票代码") or "").zfill(6)
            sector = self._sector(row.get("industry_code") or row.get("行业代码"))
            valid_from = self._date(row.get("start_date") or row.get("计入日期"))
            updated = self._date(row.get("update_time") or row.get("更新日期"))
            if symbol in wanted and sector and valid_from and updated:
                parsed.append({
                    "objectKey": wanted[symbol], "symbol": symbol, "sector": sector,
                    "validFrom": valid_from, "updated": updated,
                })
        if not parsed:
            return self._incomplete([], "official SW1 history has no usable requested records")

        frame = pd.DataFrame(parsed).sort_values(["symbol", "validFrom"])
        frame["validTo"] = frame.groupby("symbol")["validFrom"].shift(-1).map(
            lambda value: value - timedelta(days=1) if pd.notna(value) else None
        )
        output: list[dict[str, object]] = []
        for row in frame.to_dict("records"):
            valid_to = row["validTo"] if isinstance(row["validTo"], date) else None
            if row["validFrom"] > query.end_date or (valid_to and valid_to < query.start_date):
                continue
            point = self._updated_times(row["updated"])
            stock_id = row["objectKey"].split(":", 1)[1]
            output.append({
                "objectType": "stock",
                "objectId": stock_id,
                "sectorCode": row["sector"],
                "validFrom": row["validFrom"].isoformat(),
                "validTo": valid_to.isoformat() if valid_to else None,
                "qualityStatus": "available",
                "observedAt": point[0].isoformat(),
                "availableAt": point[1].isoformat(),
                "availabilityPolicyVersion": TIME_POLICY_VERSION,
            })

        complete = all(self._covers(frame.loc[frame["symbol"] == symbol], query) for symbol in wanted)
        earliest = min((row["validFrom"] for row in frame.to_dict("records")), default=None)
        return response(
            self.context, output, source=SOURCE, calculation_version=CALCULATION_VERSION,
            complete=complete,
            reason=None if complete else "official SW1 history does not cover every requested stock date",
            earliest=earliest,
        )

    def _current_snapshot(self, query: RiskQuery, requested: tuple[str, ...]) -> GatewayResponse:
        current_session = max(
            (
                session for session in self.context.a_share_sessions
                if session <= self.context.fetched_at.date()
            ),
            default=None,
        )
        if current_session is None or query.end_date < current_session:
            return self._incomplete([], FALLBACK_REASON)
        requested_ids = {key.split(":", 1)[1].split(".", 1)[0]: key for key in requested}
        sectors = self.context.client.get("sw_index_first_info", {})
        output = []
        for sector_row in sectors:
            sector = self._sector(sector_row.get("行业代码") or sector_row.get("index_code"))
            if not sector:
                continue
            sector_name = str(
                sector_row.get("行业名称")
                or sector_row.get("index_name")
                or sector_row.get("名称")
                or ""
            ).strip() or None
            components = self.context.client.get("index_component_sw", {"symbol": sector})
            for component in components:
                symbol = str(component.get("证券代码") or component.get("symbol") or "").zfill(6)
                if symbol not in requested_ids:
                    continue
                output.append({
                    "objectType": "stock",
                    "objectId": normalize_object_key(requested_ids[symbol]).split(":", 1)[1],
                    "sectorCode": sector,
                    "sectorName": sector_name,
                    "validFrom": current_session.isoformat(),
                    "validTo": None,
                    "qualityStatus": "available",
                    "observedAt": self.context.fetched_at.isoformat(),
                    "availableAt": self.context.fetched_at.isoformat(),
                    "availabilityPolicyVersion": TIME_POLICY_VERSION,
                })
        earliest = min((date.fromisoformat(str(row["validFrom"])) for row in output), default=None)
        return response(
            self.context, output, source="AKTools:index_component_sw-current-snapshot",
            calculation_version=CALCULATION_VERSION, complete=False, reason=FALLBACK_REASON,
            earliest=earliest,
        )

    def _updated_times(self, updated: date) -> tuple[datetime, datetime]:
        observed = datetime.combine(updated, time(23, 59, 59), SHANGHAI)
        try:
            point = dated_value_times(updated, self.context.a_share_sessions)
            return observed, point.available_at
        except ValueError:
            return observed, max(observed, self.context.fetched_at)

    @staticmethod
    def _covers(frame: pd.DataFrame, query: RiskQuery) -> bool:
        if frame.empty:
            return False
        intervals = []
        for row in frame.sort_values("validFrom").to_dict("records"):
            end = row["validTo"] if isinstance(row["validTo"], date) else date.max
            intervals.append((row["validFrom"], end))
        cursor = query.start_date
        for start, end in intervals:
            if end < cursor:
                continue
            if start > cursor:
                return False
            cursor = end + timedelta(days=1) if end != date.max else date.max
            if cursor > query.end_date:
                return True
        return cursor > query.end_date

    @staticmethod
    def _sector(raw: object) -> str | None:
        value = str(raw or "").upper().replace("SW1:", "").replace(".SI", "").strip()
        return value if len(value) == 6 and value.isdigit() else None

    @staticmethod
    def _date(raw: object) -> date | None:
        if raw is None or raw == "":
            return None
        try:
            return pd.Timestamp(raw).date()
        except (TypeError, ValueError):
            return None

    def _incomplete(self, data: list[dict[str, object]], reason: str) -> GatewayResponse:
        return response(
            self.context, data, source=SOURCE, calculation_version=CALCULATION_VERSION,
            complete=False, reason=reason, earliest=None,
        )
