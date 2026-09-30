"""Single fixed 2-of-3 bullish vote stress on the phase5 PIT100 stock cohort.

The group is phase4 fit-ranked vote #1. No parameters are fitted or selected
from the phase5 stock quotes. The sealed100 quote/outcome file is never read.
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

SOURCE = HERE / "pit_unadjusted_baostock.csv"
ASSIGNMENT = HERE / "pit_assignment.csv"
INDEX = ROOT / "research/normal_stock_65/phase4/hs300_unadjusted_baostock.csv"
VOTE_SOURCE = ROOT / "research/normal_stock_65/phase4/bullish_group_results.json"
EXPECTED_MEMBERS = [
    {"field": "change_pct_5d", "op": "<=", "value": -.10, "family": "momentum"},
    {"field": "open_gap", "op": ">=", "value": .02, "family": "candle"},
    {"field": "index_close_position", "op": "<=", "value": .2, "family": "market"},
]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def event_frame() -> pd.DataFrame:
    assignment_manifest = json.loads((HERE / "pit_assignment_manifest.json").read_text(encoding="utf-8"))
    quote_manifest = json.loads((HERE / "pit_baostock_manifest.json").read_text(encoding="utf-8"))
    if digest(ASSIGNMENT) != assignment_manifest["assignment_sha256"]:
        raise ValueError("Frozen PIT assignment changed")
    if digest(SOURCE) != quote_manifest["sha256"][SOURCE.name]:
        raise ValueError("Frozen PIT quotes changed")
    if quote_manifest["fetch_errors"] or quote_manifest["symbols_with_rows"] != 100:
        raise ValueError("Incomplete PIT quote collection")
    raw = pd.read_csv(SOURCE, dtype={"symbol": str, "trade_status": str, "is_st": str},
                      parse_dates=["trade_date"])
    ix = pd.read_csv(INDEX, parse_dates=["trade_date"])
    if ix.duplicated("trade_date").any():
        raise ValueError("Duplicate index dates")
    calendar = sorted(ix["trade_date"].unique())
    entry_day = {day: calendar[i + 1] for i, day in enumerate(calendar[:-1])}
    exit_day = {day: calendar[i + 2] for i, day in enumerate(calendar[:-2])}
    traded = raw.loc[raw["trade_status"].eq("1")].copy()
    cols = ["open", "high", "low", "close", "volume_shares", "amount_yuan"]
    traded = traded.dropna(subset=cols)
    traded = traded.loc[traded[cols].gt(0).all(axis=1)].copy()
    traded = traded.sort_values(["symbol", "trade_date"])
    quotes = traded.rename(columns={"open": "open_price", "high": "high_price",
                                    "low": "low_price", "close": "close_price",
                                    "volume_shares": "volume", "amount_yuan": "amount"})
    factors = calculate_factors(quotes[["symbol", "trade_date", "open_price", "high_price",
                                        "low_price", "close_price", "volume", "amount"]].copy())
    normal = add_normal_universe_eligibility(quotes[["symbol", "trade_date", "close_price", "amount"]])
    factors = factors.merge(normal[["symbol", "trade_date", "normal_universe_eligible"]],
                            on=["symbol", "trade_date"], validate="one_to_one")
    factors = factors.merge(traded[["symbol", "trade_date", "is_st"]],
                            on=["symbol", "trade_date"], validate="one_to_one")
    factors["open_gap"] = factors["open_price"] / factors.groupby("symbol")["close_price"].shift(1) - 1
    ix["index_close_position"] = ((ix["close"] - ix["low"])
                                  / (ix["high"] - ix["low"]).replace(0, np.nan))
    factors = factors.merge(ix[["trade_date", "index_close_position"]],
                            on="trade_date", how="left", validate="many_to_one")
    eligible = factors.loc[factors["normal_universe_eligible"]
                           & factors["is_st"].eq("0")
                           & factors["risk_status"].eq("normal")].copy()
    eligible["entry_date"] = eligible["trade_date"].map(entry_day)
    eligible["exit_date"] = eligible["trade_date"].map(exit_day)
    eligible = eligible.loc[eligible["entry_date"].notna() & eligible["exit_date"].notna()]
    eligible = eligible.loc[eligible["trade_date"].dt.year.eq(eligible["exit_date"].dt.year)].copy()
    bars = raw[["symbol", "trade_date", "open", "close", "volume_shares", "trade_status"]]
    eligible = eligible.merge(bars.rename(columns={"trade_date": "entry_date", "open": "entry_open",
                                                   "close": "entry_close", "volume_shares": "entry_volume",
                                                   "trade_status": "entry_status"}),
                              on=["symbol", "entry_date"], how="left", validate="many_to_one")
    eligible = eligible.merge(bars.rename(columns={"trade_date": "exit_date", "open": "exit_open",
                                                   "volume_shares": "exit_volume", "trade_status": "exit_status"}),
                              on=["symbol", "exit_date"], how="left", validate="many_to_one")
    eligible["entry_price_known"] = (eligible["entry_status"].eq("1")
                                      & eligible["entry_volume"].gt(0)
                                      & eligible["entry_open"].gt(0))
    eligible["exit_price_known"] = (eligible["exit_status"].eq("1")
                                     & eligible["exit_volume"].gt(0)
                                     & eligible["exit_open"].gt(0))
    eligible["direction_observable"] = (eligible["entry_price_known"]
                                         & eligible["exit_price_known"])
    eligible["gross_return"] = eligible["exit_open"] / eligible["entry_open"] - 1
    eligible["up_correct"] = (eligible["direction_observable"]
                              & eligible["gross_return"].gt(0))
    eligible["entry_gap"] = eligible["entry_open"] / eligible["close_price"] - 1
    eligible["exit_gap"] = eligible["exit_open"] / eligible["entry_close"] - 1
    eligible["entry_executable"] = eligible["entry_price_known"] & eligible["entry_gap"].lt(.095)
    eligible["blocked_exit"] = (~eligible["exit_price_known"] | eligible["exit_gap"].le(-.095))
    eligible["net_return"] = eligible["gross_return"] - COST
    eligible.loc[eligible["blocked_exit"], "net_return"] = -.10 - COST
    eligible["year"] = eligible["trade_date"].dt.year
    return eligible.reset_index(drop=True)


def report_metric(frame: pd.DataFrame, eligible_denominator: int) -> dict:
    return metric(frame, "up", eligible_denominator)


def main() -> None:
    source = json.loads(VOTE_SOURCE.read_text(encoding="utf-8"))
    chosen = source["results"]["vote"][0]
    if chosen["members"] != EXPECTED_MEMBERS or chosen["min_matched_rules"] != 2:
        raise ValueError("Phase4 fit-ranked #1 vote group no longer matches pre-fixed rule")
    data = event_frame()
    hits = (data["change_pct_5d"].le(-.10).fillna(False).astype(int)
            + data["open_gap"].ge(.02).fillna(False).astype(int)
            + data["index_close_position"].le(.2).fillna(False).astype(int)) >= 2
    selected = data.loc[hits].copy()
    selected_nonoverlap = nonoverlap(selected)
    assignment = pd.read_csv(ASSIGNMENT, dtype={"symbol": str})
    prior80 = set(pd.read_csv(ROOT / "research/normal_stock_65/phase4/historical_assignment.csv",
                              dtype={"symbol": str})["symbol"])
    delisted = set(pd.read_csv(ROOT / "research/normal_stock_65/phase3/data_quality/delisted_assignment.csv",
                               dtype={"symbol": str})["symbol"])
    top_day = selected_nonoverlap["entry_date"].value_counts().idxmax() if len(selected_nonoverlap) else None
    top5_days = set(selected_nonoverlap["entry_date"].value_counts().head(5).index)
    pre2024 = data.loc[data["year"].between(2020, 2023)]
    recent = data.loc[data["year"].between(2024, 2026)]
    output = {
        "purpose": "one pre-fixed phase4 fit-ranked bullish vote tested once on phase5 PIT100; no tuning",
        "fixed_group": {"members": chosen["members"], "min_matched_rules": 2},
        "selection_source": "phase4/bullish_group_results.json results.vote[0], selected on phase2 dev200 2020-2023 before phase5 PIT100 quotes",
        "source_sha256": {str(path.relative_to(ROOT)): digest(path)
                          for path in (SOURCE, ASSIGNMENT, INDEX, VOTE_SOURCE)},
        "prediction": "T close vote predicts strict T+1 market open to T+2 market open rise; missing or suspended opens count wrong",
        "baseline_normal_all_2020_26": report_metric(data, len(data)),
        "baseline_normal_2024_26": report_metric(recent, len(recent)),
        "fixed_vote_all_2020_26": report_metric(selected, len(data)),
        "fixed_vote_train_calendar_2020_23": report_metric(selected.loc[selected["year"].between(2020, 2023)], len(pre2024)),
        "fixed_vote_recent_2024_26": report_metric(selected.loc[selected["year"].between(2024, 2026)], len(recent)),
        "by_year": {str(year): report_metric(group, len(data.loc[data["year"].eq(year)]))
                    for year, group in selected.groupby("year")},
        "by_snapshot": {
            snapshot: report_metric(selected.loc[selected["symbol"].isin(symbols)],
                                    len(data.loc[data["symbol"].isin(symbols)]))
            for snapshot in ("2019-01-02", "2023-01-03")
            for symbols in [set(assignment.loc[assignment["snapshot_date"].eq(snapshot), "symbol"])]
        },
        "known_future_delisted": report_metric(selected.loc[selected["symbol"].isin(delisted)],
                                                len(data.loc[data["symbol"].isin(delisted)])),
        "excluding_phase4_overlap_4": report_metric(selected.loc[~selected["symbol"].isin(prior80)],
                                                    len(data.loc[~data["symbol"].isin(prior80)])),
        "top_entry_day": str(top_day.date()) if top_day is not None else None,
        "excluding_top_entry_day": report_metric(selected_nonoverlap.loc[
            selected_nonoverlap["entry_date"].ne(top_day)], len(data)),
        "excluding_top5_entry_days": report_metric(selected_nonoverlap.loc[
            ~selected_nonoverlap["entry_date"].isin(top5_days)], len(data)),
        "selected_known_future_delisted_symbols": sorted(set(data["symbol"]) & delisted),
        "phase4_overlap_symbols": sorted(set(data["symbol"]) & prior80),
        "sealed_validation_access": "none",
        "limitations": ["The PIT100 is a historical stock sample but shares market dates with phase4 development.",
                        "Four PIT100 symbols overlap the exposed phase4 historical80; exclusion is sensitivity only.",
                        "Only five sampled stocks are known to have delisted by 2026; no subset inference from small support.",
                        "BaoStock unadjusted prices and source ST/membership statuses are not exchange-verified.",
                        "Gross direction accuracy is not executable net profit."],
    }
    output["fixed_vote_raw_all"] = {"predictions": len(selected), "correct": int(selected["up_correct"].sum()),
                                     "accuracy": round(float(selected["up_correct"].mean()), 5) if len(selected) else None}
    output["fixed_vote_nonoverlap_all"] = {"predictions": len(selected_nonoverlap),
                                            "correct": int(selected_nonoverlap["up_correct"].sum()),
                                            "accuracy": round(float(selected_nonoverlap["up_correct"].mean()), 5)
                                            if len(selected_nonoverlap) else None}
    target = HERE / "pit_fixed_vote_stress.json"
    target.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: output[key] for key in ("baseline_normal_2024_26",
                                                   "fixed_vote_recent_2024_26", "fixed_vote_all_2020_26",
                                                   "known_future_delisted", "top_entry_day")},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
