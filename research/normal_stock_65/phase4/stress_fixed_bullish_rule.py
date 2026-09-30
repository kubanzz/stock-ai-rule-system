"""Test one previously reported bullish rule on disjoint historical sample.

The condition and source rule are fixed. This is an out-of-source diagnostic,
not a search; it never reads the sealed stock quote cohort.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pandas as pd


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.normal_universe import add_normal_universe_eligibility  # noqa: E402
from research.one_day_20260930.quant_research import calculate_factors, wilson  # noqa: E402


SOURCE = HERE / "historical_unadjusted_baostock.csv"
INDEX = HERE / "hs300_unadjusted_baostock.csv"
RULE_FILE = ROOT / "research/normal_stock_65/phase3/rare_signal_normal_results.json"
CONDITION = "open_gap >= 0.01 AND index_close_position <= 0.2 AND exchange = SZ"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def metric(frame: pd.DataFrame) -> dict:
    n = len(frame)
    wins = int(frame["correct"].sum()) if n else 0
    return {
        "predictions": n, "correct": wins,
        "accuracy": round(wins / n, 5) if n else None,
        "wilson95_lower": round(wilson(wins, n), 5) if n else None,
        "symbols": int(frame["symbol"].nunique()) if n else 0,
        "signal_dates": int(frame["trade_date"].nunique()) if n else 0,
        "unobservable": int((~frame["direction_observable"]).sum()) if n else 0,
        "top_symbol_share": round(float(frame["symbol"].value_counts(normalize=True).iloc[0]), 5) if n else None,
        "top_date_share": round(float(frame["trade_date"].value_counts(normalize=True).iloc[0]), 5) if n else None,
    }


def nonoverlap(frame: pd.DataFrame) -> pd.DataFrame:
    """Keep the first planned holding until its T+2 exit open for each stock."""
    keep = []
    last_exit = {}
    for row in frame.sort_values(["symbol", "entry_date", "trade_date"]).itertuples():
        previous = last_exit.get(row.symbol)
        if previous is None or row.entry_date > previous:
            keep.append(row.Index)
            last_exit[row.symbol] = row.exit_date
    return frame.loc[keep].copy()


def main() -> None:
    frozen = json.loads(RULE_FILE.read_text(encoding="utf-8"))
    matches = [rule for rule in frozen["up"]["reported"] if rule["text"] == CONDITION]
    if len(matches) != 1:
        raise ValueError("Previously reported bullish rule no longer unique")
    raw = pd.read_csv(SOURCE, dtype={"symbol": str, "trade_status": str, "is_st": str}, parse_dates=["trade_date"])
    assignment = pd.read_csv(HERE / "historical_assignment.csv", dtype={"symbol": str}, parse_dates=["snapshot_date"])
    ix = pd.read_csv(INDEX, parse_dates=["trade_date"])
    calendar = sorted(ix["trade_date"].unique())
    entry_day = {date: calendar[i + 1] for i, date in enumerate(calendar[:-1])}
    exit_day = {date: calendar[i + 2] for i, date in enumerate(calendar[:-2])}
    traded = raw.loc[raw["trade_status"].eq("1")].copy()
    traded = traded.dropna(subset=["open", "high", "low", "close", "volume_shares", "amount_yuan"])
    traded = traded.loc[traded[["open", "high", "low", "close", "volume_shares", "amount_yuan"]].gt(0).all(axis=1)].copy()
    traded = traded.sort_values(["symbol", "trade_date"])
    traded["close_price"] = traded["close"]
    traded["amount"] = traded["amount_yuan"]
    eligible = add_normal_universe_eligibility(traded)
    eligible["open_gap"] = eligible["open"] / eligible.groupby("symbol")["close"].shift(1) - 1
    factor_input = eligible.rename(columns={"open": "open_price", "high": "high_price", "low": "low_price", "volume_shares": "volume"})
    factors = calculate_factors(factor_input[["symbol", "trade_date", "open_price", "high_price", "low_price", "close_price", "volume", "amount"]].copy())
    eligible = eligible.merge(factors[["symbol", "trade_date", "risk_status"]], on=["symbol", "trade_date"], how="left", validate="one_to_one")
    ix["index_close_position"] = (ix["close"] - ix["low"]) / (ix["high"] - ix["low"])
    eligible = eligible.merge(ix[["trade_date", "index_close_position"]], on="trade_date", how="left", validate="many_to_one")
    eligible = eligible.loc[
        eligible["normal_universe_eligible"]
        & eligible["is_st"].eq("0")
        & eligible["risk_status"].eq("normal")
        & eligible["open_gap"].ge(.01)
        & eligible["index_close_position"].le(.2)
        & eligible["symbol"].str.endswith(".SZ")
    ].copy()
    eligible["entry_date"] = eligible["trade_date"].map(entry_day)
    eligible["exit_date"] = eligible["trade_date"].map(exit_day)
    eligible = eligible.loc[eligible["entry_date"].notna() & eligible["exit_date"].notna()].copy()
    bars = raw[["symbol", "trade_date", "open", "volume_shares", "trade_status"]]
    eligible = eligible.merge(bars.rename(columns={"trade_date": "entry_date", "open": "entry_open", "volume_shares": "entry_volume", "trade_status": "entry_status"}), on=["symbol", "entry_date"], how="left", validate="many_to_one")
    eligible = eligible.merge(bars.rename(columns={"trade_date": "exit_date", "open": "exit_open", "volume_shares": "exit_volume", "trade_status": "exit_status"}), on=["symbol", "exit_date"], how="left", validate="many_to_one")
    eligible["direction_observable"] = eligible["entry_status"].eq("1") & eligible["exit_status"].eq("1") & eligible["entry_volume"].gt(0) & eligible["exit_volume"].gt(0)
    eligible["correct"] = eligible["direction_observable"] & eligible["exit_open"].gt(eligible["entry_open"])
    eligible = eligible.loc[eligible["trade_date"].dt.year.eq(eligible["exit_date"].dt.year)].copy()
    eligible = eligible.merge(assignment[["symbol", "snapshot_date"]], on="symbol", validate="many_to_one")
    selected_nonoverlap = nonoverlap(eligible)
    output = {
        "purpose": "single previously reported bullish condition, tested once on disjoint historical sample; no parameter selection",
        "condition": CONDITION,
        "source_sha256": digest(SOURCE), "index_sha256": digest(INDEX), "rule_source_sha256": digest(RULE_FILE),
        "eligibility": "mainboard_liquid_stable_v1, source T-day isST=0/tradestatus=1, exact project risk_status=normal",
        "prediction": "T close signal, T+1 open to T+2 open strict positive sign; missing/suspended open counts incorrect",
        "all": metric(eligible),
        "all_nonoverlap": metric(selected_nonoverlap),
        "by_year": {str(year): metric(group) for year, group in eligible.groupby(eligible["trade_date"].dt.year)},
        "by_year_nonoverlap": {str(year): metric(group) for year, group in selected_nonoverlap.groupby(selected_nonoverlap["trade_date"].dt.year)},
        "by_snapshot": {str(day.date()): metric(group) for day, group in eligible.groupby("snapshot_date")},
        "by_snapshot_nonoverlap": {str(day.date()): metric(group) for day, group in selected_nonoverlap.groupby("snapshot_date")},
        "limitations": [
            "Shares market dates with prior exposed rule development and therefore is not a new regime validation.",
            "Historical sample excludes the known future-delisted 192-stock cohort before assignment; all 80 selected stocks remain active as of 2026.",
            "Unadjusted prices around corporate actions and opening prices do not prove executable profit.",
        ],
        "sealed_validation_access": "none",
    }
    path = HERE / "historical_fixed_bullish_stress.json"
    path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: output[key] for key in ("condition", "all", "by_year")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
