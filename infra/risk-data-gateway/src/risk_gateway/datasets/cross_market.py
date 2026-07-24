from dataclasses import dataclass
from datetime import date, datetime, time
from zoneinfo import ZoneInfo

import pandas as pd

from risk_gateway.aktools import AkToolsUnavailable
from risk_gateway.datasets import DatasetContext, requested_sessions, response
from risk_gateway.models import GatewayResponse, RiskQuery
from risk_gateway.series import normalize_market_frame
from risk_gateway.time_policy import TIME_POLICY_VERSION, align_global_to_a_share


SOURCE = "AKTools:index_us_stock_sina/stock_hk_index_daily_sina/index_global_hist_sina"
CALCULATION_VERSION = "cross-market-equal-weight-correlation-v1"
BASKET_DEFINITION = "SP500,NASDAQ,HSI,NIKKEI225"


@dataclass(frozen=True)
class Asset:
    code: str
    function: str
    symbol: str
    timezone: str
    close_time: time


ASSETS = (
    Asset("SP500", "index_us_stock_sina", ".INX", "America/New_York", time(16, 0)),
    Asset("NASDAQ", "index_us_stock_sina", ".IXIC", "America/New_York", time(16, 0)),
    Asset("HSI", "stock_hk_index_daily_sina", "HSI", "Asia/Hong_Kong", time(16, 0)),
    Asset("NIKKEI225", "index_global_hist_sina", "NKY", "Asia/Tokyo", time(15, 0)),
)


class CrossMarketDataset:
    def __init__(self, context: DatasetContext):
        self.context = context

    def fetch(self, query: RiskQuery) -> GatewayResponse:
        if query.object_keys != ("market:CN-A",):
            return self._incomplete([], "cross-market supports only market:CN-A")
        requested = requested_sessions(self.context, query.start_date, query.end_date)
        asset_frames = []
        for asset in ASSETS:
            try:
                frame = self._asset_returns(asset)
            except AkToolsUnavailable:
                continue
            if not frame.empty:
                asset_frames.append(frame)
        if len(asset_frames) < 3:
            return self._incomplete([], "fewer than three global markets are available")

        global_returns = pd.concat(asset_frames, ignore_index=True)
        daily = aggregate_aligned_asset_returns(global_returns)
        daily = daily.loc[daily["observedMarketCount"] >= 3]

        target = self._target_returns()
        aligned = target.merge(daily, on="tradeDate", how="inner", validate="one_to_one")
        aligned = aligned.sort_values("tradeDate").reset_index(drop=True)
        aligned["dynamicCorrelation"] = aligned["targetReturn"].rolling(60, min_periods=60).corr(
            aligned["leadingAssetReturn"]
        )

        rows: list[dict[str, object]] = []
        for record in aligned.loc[aligned["tradeDate"].isin(requested)].to_dict("records"):
            correlation = record["dynamicCorrelation"]
            if pd.isna(correlation):
                continue
            rows.append({
                "objectType": "market",
                "objectId": "CN-A",
                "tradeDate": record["tradeDate"].isoformat(),
                "leadingAssetReturn": float(record["leadingAssetReturn"]),
                "dynamicCorrelation": float(correlation),
                "confirmedDownMarketCount": int(record["confirmedDownMarketCount"]),
                "observedMarketCount": int(record["observedMarketCount"]),
                "basketDefinition": BASKET_DEFINITION,
                "proxy": True,
                "calculationVersion": CALCULATION_VERSION,
                "qualityStatus": "available",
                "observedAt": record["observedAt"].isoformat(),
                "availableAt": record["availableAt"].isoformat(),
                "availabilityPolicyVersion": TIME_POLICY_VERSION,
            })

        complete = history_coverage_complete(
            tuple(date.fromisoformat(str(row["tradeDate"])) for row in rows), requested,
        )
        reason = None if complete else "60-day correlation or aligned global history is incomplete"
        earliest = date.fromisoformat(str(rows[0]["tradeDate"])) if rows else None
        return response(
            self.context, rows, source=SOURCE, calculation_version=CALCULATION_VERSION,
            complete=complete, reason=reason, earliest=earliest,
        )

    def _asset_returns(self, asset: Asset) -> pd.DataFrame:
        rows = self.context.client.get(asset.function, {"symbol": asset.symbol})
        frame = normalize_market_frame(rows, require_positive_open=False)
        frame["assetReturn"] = frame["close"].pct_change()
        records = []
        timezone = ZoneInfo(asset.timezone)
        for row in frame.dropna(subset=["assetReturn"]).to_dict("records"):
            close_at = datetime.combine(row["date"], asset.close_time, timezone)
            try:
                point = align_global_to_a_share(close_at, self.context.a_share_sessions)
            except ValueError:
                continue
            records.append({
                "tradeDate": point.trade_date,
                "asset": asset.code,
                "assetReturn": float(row["assetReturn"]),
                "observedAt": point.observed_at,
                "availableAt": point.available_at,
            })
        return pd.DataFrame(records)

    def _target_returns(self) -> pd.DataFrame:
        rows = self.context.client.get("stock_zh_index_daily", {"symbol": "sh000001"})
        frame = normalize_market_frame(rows, require_positive_open=False)
        frame["targetReturn"] = frame["close"].pct_change()
        return frame.dropna(subset=["targetReturn"])[["date", "targetReturn"]].rename(
            columns={"date": "tradeDate"}
        )

    def _incomplete(self, data: list[dict[str, object]], reason: str) -> GatewayResponse:
        return response(
            self.context, data, source=SOURCE, calculation_version=CALCULATION_VERSION,
            complete=False, reason=reason, earliest=None,
        )


def aggregate_aligned_asset_returns(global_returns: pd.DataFrame) -> pd.DataFrame:
    """Collapse every asset to one cumulative move per upcoming A-share session."""
    by_asset = global_returns.groupby(["tradeDate", "asset"], as_index=False).agg(
        assetReturn=("assetReturn", lambda values: float((1.0 + values).prod() - 1.0)),
        observedAt=("observedAt", "max"),
        availableAt=("availableAt", "max"),
    )
    return by_asset.groupby("tradeDate", as_index=False).agg(
        leadingAssetReturn=("assetReturn", "mean"),
        confirmedDownMarketCount=("assetReturn", lambda values: int((values < 0).sum())),
        observedMarketCount=("asset", "nunique"),
        observedAt=("observedAt", "max"),
        availableAt=("availableAt", "max"),
    )


def history_coverage_complete(
    returned_dates: tuple[date, ...],
    requested_dates: tuple[date, ...],
) -> bool:
    if not requested_dates or not returned_dates:
        return False
    returned = set(returned_dates)
    covered = sum(value in returned for value in requested_dates)
    return requested_dates[-1] in returned and covered / len(requested_dates) >= 0.80
