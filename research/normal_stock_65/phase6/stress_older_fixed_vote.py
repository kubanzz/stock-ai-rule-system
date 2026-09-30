"""Test the unchanged phase4 2-of-3 bullish vote in 2010–2018 market years.

The older stock assignment and quotes are frozen before this script reads any
outcome. This script does not rank alternatives or access sealed100 outcomes.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.normal_universe import add_normal_universe_eligibility  # noqa: E402
from research.normal_stock_65.phase3.consensus_group_search import metric, nonoverlap  # noqa: E402
from research.one_day_20260930.quant_research import COST, calculate_factors  # noqa: E402

SOURCE = HERE / "pit_older_unadjusted_baostock.csv"
ASSIGNMENT = HERE / "pit_older_assignment.csv"
INDEX = HERE / "hs300_older_unadjusted_baostock.csv"
VOTE_SOURCE = ROOT / "research/normal_stock_65/phase4/bullish_group_results.json"
EXPECTED_MEMBERS = [
    {"field": "change_pct_5d", "op": "<=", "value": -.10, "family": "momentum"},
    {"field": "open_gap", "op": ">=", "value": .02, "family": "candle"},
    {"field": "index_close_position", "op": "<=", "value": .2, "family": "market"},
]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_manifest(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def event_frame() -> pd.DataFrame:
    assignment_manifest = read_manifest(HERE / "pit_older_assignment_manifest.json")
    quote_manifest = read_manifest(HERE / "pit_older_baostock_manifest.json")
    index_manifest = read_manifest(HERE / "hs300_older_manifest.json")
    if digest(ASSIGNMENT) != assignment_manifest["assignment_sha256"]:
        raise ValueError("Frozen older assignment changed")
    if digest(SOURCE) != quote_manifest["sha256"][SOURCE.name]:
        raise ValueError("Frozen older quotes changed")
    if digest(INDEX) != index_manifest["sha256"][INDEX.name]:
        raise ValueError("Frozen older HS300 series changed")
    if quote_manifest["fetch_errors"] or quote_manifest["symbols_with_rows"] != assignment_manifest["assigned_symbols"]:
        raise ValueError("Incomplete older quote collection")
    assignment = pd.read_csv(ASSIGNMENT, dtype={"symbol": str}, parse_dates=["snapshot_date"])
    if assignment.duplicated("symbol").any():
        raise ValueError("Older assignment contains duplicate stocks")
    raw = pd.read_csv(SOURCE, dtype={"symbol": str, "trade_status": str, "is_st": str},
                      parse_dates=["trade_date"])
    if raw.duplicated(["symbol", "trade_date"]).any():
        raise ValueError("Duplicate older stock dates")
    if set(raw["symbol"]) != set(assignment["symbol"]):
        raise ValueError("Older stock quote coverage differs from assignment")
    raw = raw.merge(assignment[["symbol", "snapshot_date"]], on="symbol", validate="many_to_one")
    if raw["trade_date"].lt(raw["snapshot_date"]).any():
        raise ValueError("Quotes precede point-in-time snapshot")
    if not raw["adjust_flag"].eq(3).all():
        raise ValueError("Mixed stock adjustment flags")
    ix = pd.read_csv(INDEX, parse_dates=["trade_date"])
    if ix.duplicated("trade_date").any() or not ix["trade_status"].eq(1).all():
        raise ValueError("Duplicate or non-trading older HS300 dates")
    if (ix[["open", "high", "low", "close"]].le(0).any().any()
            or ix["low"].gt(ix[["open", "close"]].min(axis=1)).any()
            or ix["high"].lt(ix[["open", "close"]].max(axis=1)).any()
            or ix["low"].gt(ix["high"]).any()):
        raise ValueError("Invalid older HS300 OHLC")
    calendar = sorted(ix["trade_date"].unique())
    entry_day = {day: calendar[i + 1] for i, day in enumerate(calendar[:-1])}
    exit_day = {day: calendar[i + 2] for i, day in enumerate(calendar[:-2])}
    traded = raw.loc[raw["trade_status"].eq("1")].copy()
    cols = ["open", "high", "low", "close", "volume_shares", "amount_yuan"]
    traded = traded.dropna(subset=cols)
    traded = traded.loc[traded[cols].gt(0).all(axis=1)].copy()
    traded = traded.sort_values(["symbol", "trade_date"])
    prior_close = traded.groupby("symbol")["close"].shift(1)
    traded["action_proxy"] = ((traded["pre_close"] / prior_close - 1).abs().gt(.005)
                               & prior_close.gt(0))
    traded["action_lookback_6bars"] = (traded.groupby("symbol")["action_proxy"]
                                        .transform(lambda series: series.rolling(6, min_periods=1).max())
                                        .fillna(0).astype(bool))
    quotes = traded.rename(columns={"open": "open_price", "high": "high_price",
                                    "low": "low_price", "close": "close_price",
                                    "volume_shares": "volume", "amount_yuan": "amount"})
    factors = calculate_factors(quotes[["symbol", "trade_date", "open_price", "high_price",
                                        "low_price", "close_price", "volume", "amount"]].copy())
    normal = add_normal_universe_eligibility(quotes[["symbol", "trade_date", "close_price", "amount"]])
    factors = factors.merge(normal[["symbol", "trade_date", "normal_universe_eligible"]],
                            on=["symbol", "trade_date"], validate="one_to_one")
    factors = factors.merge(traded[["symbol", "trade_date", "is_st", "action_lookback_6bars"]],
                            on=["symbol", "trade_date"], validate="one_to_one")
    factors["open_gap"] = factors["open_price"] / factors.groupby("symbol")["close_price"].shift(1) - 1
    ix["index_close_position"] = ((ix["close"] - ix["low"])
                                  / (ix["high"] - ix["low"]).replace(0, np.nan))
    factors = factors.merge(ix[["trade_date", "index_close_position"]],
                            on="trade_date", how="left", validate="many_to_one")
    eligible = factors.loc[factors["normal_universe_eligible"]
                           & factors["is_st"].eq("0")
                           & factors["risk_status"].eq("normal")].copy()
    eligible = eligible.loc[eligible["trade_date"].dt.year.between(2010, 2018)].copy()
    eligible["entry_date"] = eligible["trade_date"].map(entry_day)
    eligible["exit_date"] = eligible["trade_date"].map(exit_day)
    eligible = eligible.loc[eligible["entry_date"].notna() & eligible["exit_date"].notna()]
    eligible = eligible.loc[eligible["trade_date"].dt.year.eq(eligible["exit_date"].dt.year)].copy()
    bars = raw[["symbol", "trade_date", "open", "close", "volume_shares", "trade_status"]]
    action_bars = traded[["symbol", "trade_date", "action_proxy"]]
    bars = bars.merge(action_bars, on=["symbol", "trade_date"], how="left", validate="one_to_one")
    eligible = eligible.merge(bars.rename(columns={"trade_date": "entry_date", "open": "entry_open",
                                                   "close": "entry_close", "volume_shares": "entry_volume",
                                                   "trade_status": "entry_status",
                                                   "action_proxy": "entry_action_proxy"}),
                              on=["symbol", "entry_date"], how="left", validate="many_to_one")
    eligible = eligible.merge(bars.rename(columns={"trade_date": "exit_date", "open": "exit_open",
                                                   "volume_shares": "exit_volume", "trade_status": "exit_status",
                                                   "action_proxy": "exit_action_proxy"}),
                              on=["symbol", "exit_date"], how="left", validate="many_to_one")
    eligible["entry_price_known"] = (eligible["entry_status"].eq("1")
                                      & eligible["entry_volume"].gt(0)
                                      & eligible["entry_open"].gt(0))
    eligible["exit_price_known"] = (eligible["exit_status"].eq("1")
                                     & eligible["exit_volume"].gt(0)
                                     & eligible["exit_open"].gt(0))
    eligible["direction_observable"] = eligible["entry_price_known"] & eligible["exit_price_known"]
    eligible["gross_return"] = eligible["exit_open"] / eligible["entry_open"] - 1
    eligible["up_correct"] = eligible["direction_observable"] & eligible["gross_return"].gt(0)
    eligible["entry_gap"] = eligible["entry_open"] / eligible["close_price"] - 1
    eligible["exit_gap"] = eligible["exit_open"] / eligible["entry_close"] - 1
    eligible["entry_executable"] = eligible["entry_price_known"] & eligible["entry_gap"].lt(.095)
    eligible["blocked_exit"] = ~eligible["exit_price_known"] | eligible["exit_gap"].le(-.095)
    eligible["net_return"] = eligible["gross_return"] - COST
    eligible.loc[eligible["blocked_exit"], "net_return"] = -.10 - COST
    eligible["company_action_proxy_window"] = (eligible["action_lookback_6bars"]
                                                | eligible["entry_action_proxy"].fillna(False)
                                                | eligible["exit_action_proxy"].fillna(False))
    eligible["year"] = eligible["trade_date"].dt.year
    return eligible.reset_index(drop=True)


def report_metric(frame: pd.DataFrame, eligible_denominator: int) -> dict:
    return metric(frame, "up", eligible_denominator)


def matched_day_comparison(data: pd.DataFrame, selected: pd.DataFrame) -> dict:
    selected = nonoverlap(selected)
    if selected.empty:
        return {"signal_count": 0, "entry_dates": 0, "signal_accuracy": None,
                "matched_date_baseline": None, "lift_percentage_points": None,
                "date_bootstrap95_lift_lower": None}
    # All contemporaneously eligible stocks form a date-matched descriptive
    # baseline. Each signal gets its entry day's cross-sectional mean; no
    # stock-specific outcome enters rule selection or cohort assignment.
    baseline = data.groupby("entry_date")["up_correct"].mean()
    paired = selected[["entry_date", "up_correct"]].copy()
    paired["baseline"] = paired["entry_date"].map(baseline)
    if paired["baseline"].isna().any():
        raise ValueError("Missing matched-date baseline")
    paired["difference"] = paired["up_correct"].astype(float) - paired["baseline"]
    by_day = paired.groupby("entry_date")["difference"].agg(["sum", "size"]).to_numpy()
    lower = None
    if len(by_day) >= 10:
        rng = np.random.default_rng(20260930)
        draws = rng.integers(0, len(by_day), size=(1000, len(by_day)))
        sampled = by_day[draws]
        lift_draws = sampled[:, :, 0].sum(axis=1) / sampled[:, :, 1].sum(axis=1)
        lower = round(float(np.quantile(lift_draws, .025) * 100), 3)
    return {"signal_count": len(paired), "entry_dates": int(paired["entry_date"].nunique()),
            "signal_accuracy": round(float(paired["up_correct"].mean()), 5),
            "matched_date_baseline": round(float(paired["baseline"].mean()), 5),
            "lift_percentage_points": round(float(paired["difference"].mean() * 100), 3),
        "date_bootstrap95_lift_lower": lower}


def company_action_sensitivity(data: pd.DataFrame, selected: pd.DataFrame) -> dict:
    """Apply the pre-specified T-5 through T+2 action proxy to fixed signals."""
    selected = nonoverlap(selected)
    return {
        "definition": "abs(pre_close / prior valid traded close - 1) > 0.5% in T and prior five observed bars, T+1 or T+2",
        "flagged_nonoverlap_signals": int(selected["company_action_proxy_window"].sum()),
        "excluding_flagged": report_metric(selected.loc[
            ~selected["company_action_proxy_window"]], len(data)),
    }


def matched_day_other_stock_comparison(data: pd.DataFrame, selected: pd.DataFrame) -> dict:
    """Describe within-day stock selection after removing that day's signals."""
    selected = nonoverlap(selected)
    if selected.empty:
        return {"signal_count": 0, "entry_dates": 0, "signal_accuracy": None,
                "matched_date_other_stock_baseline": None, "lift_percentage_points": None,
                "date_bootstrap95_lift_lower": None}
    selected_pairs = selected[["symbol", "entry_date"]].drop_duplicates()
    controls = data.merge(selected_pairs.assign(is_signal=True), on=["symbol", "entry_date"],
                          how="left", validate="many_to_one")
    controls = controls.loc[controls["is_signal"].isna()].copy()
    baseline = controls.groupby("entry_date")["up_correct"].mean()
    paired = selected[["entry_date", "up_correct"]].copy()
    paired["baseline"] = paired["entry_date"].map(baseline)
    without_controls = int(paired["baseline"].isna().sum())
    paired = paired.loc[paired["baseline"].notna()].copy()
    if paired.empty:
        return {"signal_count": 0, "signals_without_other_stock_control": without_controls,
                "entry_dates": 0, "signal_accuracy": None,
                "matched_date_other_stock_baseline": None, "lift_percentage_points": None,
                "date_bootstrap95_lift_lower": None}
    paired["difference"] = paired["up_correct"].astype(float) - paired["baseline"]
    by_day = paired.groupby("entry_date")["difference"].agg(["sum", "size"]).to_numpy()
    lower = None
    if len(by_day) >= 10:
        rng = np.random.default_rng(20260930)
        draws = rng.integers(0, len(by_day), size=(1000, len(by_day)))
        sampled = by_day[draws]
        lift_draws = sampled[:, :, 0].sum(axis=1) / sampled[:, :, 1].sum(axis=1)
        lower = round(float(np.quantile(lift_draws, .025) * 100), 3)
    return {"signal_count": len(paired), "signals_without_other_stock_control": without_controls,
            "entry_dates": int(paired["entry_date"].nunique()),
            "signal_accuracy": round(float(paired["up_correct"].mean()), 5),
            "matched_date_other_stock_baseline": round(float(paired["baseline"].mean()), 5),
            "lift_percentage_points": round(float(paired["difference"].mean() * 100), 3),
            "date_bootstrap95_lift_lower": lower}


def main() -> None:
    source = read_manifest(VOTE_SOURCE)
    chosen = source["results"]["vote"][0]
    if chosen["members"] != EXPECTED_MEMBERS or chosen["min_matched_rules"] != 2:
        raise ValueError("Pre-fixed phase4 vote definition changed")
    data = event_frame()
    hits = (data["change_pct_5d"].le(-.10).fillna(False).astype(int)
            + data["open_gap"].ge(.02).fillna(False).astype(int)
            + data["index_close_position"].le(.2).fillna(False).astype(int)) >= 2
    selected = data.loc[hits].copy()
    nonoverlap_selected = nonoverlap(selected)
    day_counts = nonoverlap_selected["entry_date"].value_counts()
    top_day = day_counts.index[0] if len(day_counts) else None
    top5_days = set(day_counts.head(5).index)
    segments = {"2010-2013": (2010, 2013), "2014-2016": (2014, 2016),
                "2017-2018": (2017, 2018)}
    assignment = pd.read_csv(ASSIGNMENT, dtype={"symbol": str})
    result = {
        "purpose": "one unchanged phase4 2-of-3 bullish vote tested on pre-2019 PIT stocks and market dates",
        "fixed_group": {"members": chosen["members"], "min_matched_rules": 2},
        "source_sha256": {str(path.relative_to(ROOT)): digest(path)
                          for path in (SOURCE, ASSIGNMENT, INDEX, VOTE_SOURCE)},
        "prediction": "T close predicts strict T+1 market open to T+2 market open rise; missing or suspended opens count wrong",
        "baseline_normal_2010_18": report_metric(data, len(data)),
        "fixed_vote_2010_18": report_metric(selected, len(data)),
        "matched_date_comparison": matched_day_comparison(data, selected),
        "matched_date_other_stock_comparison": matched_day_other_stock_comparison(data, selected),
        "by_year": {str(year): report_metric(selected.loc[selected["year"].eq(year)],
                                              len(data.loc[data["year"].eq(year)]))
                    for year in range(2010, 2019)},
        "by_segment": {
            name: report_metric(selected.loc[selected["year"].between(start, end)],
                                len(data.loc[data["year"].between(start, end)]))
            for name, (start, end) in segments.items()
        },
        "by_snapshot": {
            snapshot: report_metric(selected.loc[selected["symbol"].isin(symbols)],
                                    len(data.loc[data["symbol"].isin(symbols)]))
            for snapshot in sorted(assignment["snapshot_date"].unique())
            for symbols in [set(assignment.loc[assignment["snapshot_date"].eq(snapshot), "symbol"])]
        },
        "top_entry_day": str(top_day.date()) if top_day is not None else None,
        "excluding_top_entry_day": report_metric(nonoverlap_selected.loc[
            nonoverlap_selected["entry_date"].ne(top_day)], len(data)),
        "excluding_top5_entry_days": report_metric(nonoverlap_selected.loc[
            ~nonoverlap_selected["entry_date"].isin(top5_days)], len(data)),
        "company_action_proxy": company_action_sensitivity(data, selected),
        "selected_signal_days": int(selected["entry_date"].nunique()),
        "sealed_validation_access": "none",
        "limitations": ["Historical BaoStock membership and ST flags are not exchange-verified.",
                        "Only 100 stocks are sampled; this is not the full historical universe.",
                        "Unadjusted company actions can distort price features and labels.",
                        "A same-date baseline is descriptive, not a trade-executable portfolio.",
                        "Daily bars cannot establish actual opening auction fill or blocked exits."],
    }
    output = HERE / "pit_older_fixed_vote_stress.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in (
        "baseline_normal_2010_18", "fixed_vote_2010_18", "matched_date_comparison",
        "matched_date_other_stock_comparison", "by_year")},
        ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
