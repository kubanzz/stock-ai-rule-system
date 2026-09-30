"""Stress two pre-existing, unmodified development rules on delisted stocks.

This is an out-of-source diagnostic, not candidate selection. The delisted
cohort has a changing, small 2025-26 survivor count and shared market dates.
The sealed phase2 validation file is never read.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pandas as pd


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.normal_universe import add_normal_universe_eligibility  # noqa: E402
from research.one_day_20260930.quant_research import wilson  # noqa: E402


SOURCE = HERE / "delisted_unadjusted_baostock.csv"
INDEX = ROOT / "research/normal_stock_65/phase2/hs300_daily.csv"
RULE_FILE = ROOT / "research/normal_stock_65/phase3/rare_signal_normal_results.json"


def metric(frame: pd.DataFrame, correct: str) -> dict:
    n = len(frame)
    wins = int(frame[correct].sum()) if n else 0
    return {
        "predictions": n,
        "correct": wins,
        "accuracy": round(wins / n, 5) if n else None,
        "wilson95_lower": round(wilson(wins, n), 5) if n else None,
        "symbols": int(frame["symbol"].nunique()) if n else 0,
        "signal_dates": int(frame["trade_date"].nunique()) if n else 0,
        "unobservable": int((~frame["direction_observable"]).sum()) if n else 0,
        "top_symbol_share": round(float(frame["symbol"].value_counts(normalize=True).iloc[0]), 5) if n else None,
        "top_date_share": round(float(frame["trade_date"].value_counts(normalize=True).iloc[0]), 5) if n else None,
        "market_phase_2024_26": int(frame["trade_date"].dt.year.between(2024, 2026).sum()) if n else 0,
    }


def main() -> None:
    frozen = json.loads(RULE_FILE.read_text())
    expected = {
        "up": "open_gap >= 0.01 AND index_close_position <= 0.2 AND exchange = SZ",
        "down": "change_pct_5d >= 0.12 AND weekday = 0",
    }
    for direction, condition in expected.items():
        matches = [rule for rule in frozen[direction]["reported"] if rule["text"] == condition]
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one frozen {direction} rule: {condition}")

    raw = pd.read_csv(SOURCE, dtype={"symbol": str, "is_st": str, "trade_status": str}, parse_dates=["trade_date"])
    # Market dates are independently supplied by the already collected HS300
    # index, plus BaoStock's own daily rows outside the index coverage window.
    # The latter make 2020-22 usable without peeking at individual outcomes.
    ix = pd.read_csv(INDEX, parse_dates=["trade_date"])
    index_dates = set(ix["trade_date"])
    daily_counts = raw.loc[raw["trade_status"].eq("1")].groupby("trade_date")["symbol"].nunique()
    fallback_dates = set(daily_counts.loc[daily_counts.ge(10)].index)
    calendar = sorted(index_dates | fallback_dates)
    next_date = {date: calendar[i + 1] for i, date in enumerate(calendar[:-1])}
    exit_date = {date: calendar[i + 2] for i, date in enumerate(calendar[:-2])}

    traded = raw.loc[raw["trade_status"].eq("1")].copy()
    traded = traded.dropna(subset=["open", "high", "low", "close", "volume_shares", "amount_yuan"])
    traded = traded.loc[traded[["open", "high", "low", "close", "volume_shares", "amount_yuan"]].gt(0).all(axis=1)]
    traded = traded.sort_values(["symbol", "trade_date"]).copy()
    traded["close_price"] = traded["close"]
    traded["amount"] = traded["amount_yuan"]
    eligibility = add_normal_universe_eligibility(traded)
    by = eligibility.groupby("symbol", sort=False)
    eligibility["open_gap"] = eligibility["open"] / by["close"].shift(1) - 1
    eligibility["change_pct_5d"] = eligibility["close"] / by["close"].shift(5) - 1
    eligibility["weekday"] = eligibility["trade_date"].dt.dayofweek
    # Use project calculate_factors for the exact established risk-status gate.
    from research.one_day_20260930.quant_research import calculate_factors  # noqa: E402
    factor_input = eligibility.rename(columns={"open": "open_price", "high": "high_price", "low": "low_price", "volume_shares": "volume"})
    factors = calculate_factors(factor_input[["symbol", "trade_date", "open_price", "high_price", "low_price", "close_price", "volume", "amount"]].copy())
    eligibility = eligibility.merge(factors[["symbol", "trade_date", "risk_status"]], on=["symbol", "trade_date"], how="left", validate="one_to_one")

    ix["index_close_position"] = (ix["close"] - ix["low"]) / (ix["high"] - ix["low"])
    eligibility = eligibility.merge(ix[["trade_date", "index_close_position"]], on="trade_date", how="left", validate="many_to_one")
    eligibility = eligibility.loc[
        eligibility["normal_universe_eligible"] & eligibility["is_st"].eq("0") & eligibility["risk_status"].eq("normal")
    ].copy()
    eligibility["entry_date"] = eligibility["trade_date"].map(next_date)
    eligibility["exit_date"] = eligibility["trade_date"].map(exit_date)
    eligibility = eligibility.loc[eligibility["entry_date"].notna() & eligibility["exit_date"].notna()].copy()
    bars = raw[["symbol", "trade_date", "open", "volume_shares", "trade_status"]]
    eligibility = eligibility.merge(bars.rename(columns={"trade_date": "entry_date", "open": "entry_open", "volume_shares": "entry_volume", "trade_status": "entry_status"}), on=["symbol", "entry_date"], how="left", validate="many_to_one")
    eligibility = eligibility.merge(bars.rename(columns={"trade_date": "exit_date", "open": "exit_open", "volume_shares": "exit_volume", "trade_status": "exit_status"}), on=["symbol", "exit_date"], how="left", validate="many_to_one")
    eligibility["direction_observable"] = eligibility["entry_status"].eq("1") & eligibility["exit_status"].eq("1") & eligibility["entry_volume"].gt(0) & eligibility["exit_volume"].gt(0)
    eligibility["gross_return"] = eligibility["exit_open"] / eligibility["entry_open"] - 1
    eligibility["up_correct"] = eligibility["direction_observable"] & eligibility["gross_return"].gt(0)
    eligibility["down_correct"] = eligibility["direction_observable"] & eligibility["gross_return"].lt(0)
    eligibility = eligibility.loc[eligibility["trade_date"].dt.year.eq(eligibility["exit_date"].dt.year)]

    masks = {
        "up": eligibility["open_gap"].ge(.01) & eligibility["index_close_position"].le(.2) & eligibility["symbol"].str.endswith(".SZ"),
        "down": eligibility["change_pct_5d"].ge(.12) & eligibility["weekday"].eq(0),
    }
    output = {
        "purpose": "fixed-rule stress test only; no rule selection or threshold change",
        "source_sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        "index_sha256": hashlib.sha256(INDEX.read_bytes()).hexdigest(),
        "frozen_rule_source_sha256": hashlib.sha256(RULE_FILE.read_bytes()).hexdigest(),
        "calendar": "union of HS300 dates and BaoStock dates with >=10 trading delisted constituents; date-only gate",
        "eligibility": "mainboard_liquid_stable_v1, source isST=0 and tradestatus=1 at T, exact project risk_status=normal",
        "prediction": "T close -> T+1 open to T+2 open strict sign; missing or suspended entry/exit counts incorrect",
        "rules": {},
        "limitations": [
            "Delisted stocks share market dates with the rule's prior development cohorts and are not a new market-regime validation.",
            "The terminal-date cohort is not a full point-in-time historical A-share market universe.",
            "Raw unadjusted price ratios cross ex-right/dividend dates; they are a sensitivity check, not exact economic P/L.",
            "Entry/exit opening prices do not prove actual fillability; limit queue and consecutive blocked exits remain unmodeled.",
            "The HS300 index file starts 2023, so 2020-22 calendar uses an explicit >=10 stock-date proxy; later years use union with index dates.",
        ],
        "sealed_validation_access": "none",
    }
    for direction, mask in masks.items():
        chosen = eligibility.loc[mask].copy()
        output["rules"][direction] = {
            "condition": expected[direction],
            "all": metric(chosen, f"{direction}_correct"),
            "by_year": {str(year): metric(group, f"{direction}_correct") for year, group in chosen.groupby(chosen["trade_date"].dt.year)},
        }
    (HERE / "delisted_frozen_rule_stress.json").write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output["rules"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
