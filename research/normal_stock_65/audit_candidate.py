#!/usr/bin/env python3
"""Independent audit of the first bearish development candidate.

This deliberately evaluates every T-close prediction in the fixed universe,
including signals whose future T+1 open is not buyable. The bearish signal is
an avoid-long decision, so a hypothetical long's entry filter must not silently
remove its prediction from direction accuracy.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import pandas as pd

from research.normal_stock_65.normal_universe import add_normal_universe_eligibility
from research.one_day_20260930.quant_research import calculate_factors, load_quotes, wilson


SOURCE = Path(__file__).parents[1] / "one_day_20260930/ashare_daily_qfq.csv"
OUTPUT = Path(__file__).with_name("candidate_audit.json")


def unique_predictions(frame: pd.DataFrame) -> pd.DataFrame:
    """Keep one active prediction per stock for this one-day horizon."""
    chosen = []
    for _, group in frame.sort_values(["symbol", "entry_date", "trade_date"]).groupby("symbol", sort=False):
        last_exit = pd.Timestamp.min
        for index, entry, exit_date in zip(group.index, group["entry_date"], group["exit_date"]):
            if entry > last_exit:
                chosen.append(index)
                last_exit = exit_date
    return frame.loc[chosen].copy()


def summarize(frame: pd.DataFrame) -> dict:
    chosen = unique_predictions(frame)
    evaluable = chosen.loc[chosen["has_entry_bar"] & chosen["has_exit_bar"]]
    n = len(evaluable)
    down = int((evaluable["exit_open"] < evaluable["entry_open"]).sum())
    return {
        "unique_T_predictions": len(chosen),
        "missing_entry_bar": int((~chosen["has_entry_bar"]).sum()),
        "missing_exit_bar": int((chosen["has_entry_bar"] & ~chosen["has_exit_bar"]).sum()),
        "evaluable": n,
        "down_count": down,
        "down_accuracy": round(down / n, 5) if n else 0,
        "wilson95_lower": round(wilson(down, n), 5),
        "symbols": int(evaluable["symbol"].nunique()),
        "entry_dates": int(evaluable["entry_date"].nunique()),
        "top_symbol_share": round(float(evaluable["symbol"].value_counts(normalize=True).iloc[0]), 5) if n else 0,
        "excluded_by_future_entry_limit_filter": int((evaluable["future_entry_limit_reject"]).sum()),
        "excluded_by_future_entry_limit_down_count": int((
            evaluable["future_entry_limit_reject"] & (evaluable["exit_open"] < evaluable["entry_open"])
        ).sum()),
    }


def main() -> None:
    quotes = load_quotes(str(SOURCE))
    factors = calculate_factors(quotes)
    factors["return_1d"] = factors["close_price"] / factors.groupby("symbol")["close_price"].shift(1) - 1
    flags = add_normal_universe_eligibility(quotes)[["symbol", "trade_date", "normal_universe_eligible"]]
    frame = factors.merge(flags, on=["symbol", "trade_date"], validate="one_to_one")
    counts = frame.groupby("trade_date")["symbol"].nunique()
    calendar = counts[counts >= max(5, math.ceil(counts.max() * .5))].index.sort_values()
    mapping = {date: index for index, date in enumerate(calendar)}
    frame["calendar_index"] = frame["trade_date"].map(mapping)
    frame["entry_date"] = frame["calendar_index"].map({i: calendar[i + 1] for i in range(len(calendar) - 1)})
    frame["exit_date"] = frame["calendar_index"].map({i: calendar[i + 2] for i in range(len(calendar) - 2)})
    opens = quotes[["symbol", "trade_date", "open_price", "volume"]]
    frame = frame.merge(opens.rename(columns={"trade_date": "entry_date", "open_price": "entry_open", "volume": "entry_volume"}),
                        on=["symbol", "entry_date"], how="left", validate="many_to_one")
    frame = frame.merge(opens.rename(columns={"trade_date": "exit_date", "open_price": "exit_open", "volume": "exit_volume"}),
                        on=["symbol", "exit_date"], how="left", validate="many_to_one")
    frame["has_entry_bar"] = frame["entry_open"].gt(0) & frame["entry_volume"].gt(0)
    frame["has_exit_bar"] = frame["exit_open"].gt(0) & frame["exit_volume"].gt(0)
    frame["future_entry_limit_reject"] = (
        frame["entry_open"] / frame["close_price"] - 1 >= .095
    )
    candidates = frame.loc[
        frame["normal_universe_eligible"]
        & frame["risk_status"].eq("normal")
        & frame["rsi14"].ge(75)
        & frame["return_1d"].ge(.01)
        & frame["entry_date"].notna()
        & frame["exit_date"].notna()
        & frame["volume_ratio_5d"].notna()
    ].copy()
    output = {
        "candidate": "bearish: T RSI14 >= 75 AND T close return >= 1%",
        "normal_universe": "mainboard_liquid_stable_v1",
        "reason_for_independent_denominator": "Avoid-long bearish prediction does not require an executable future long entry.",
        "periods": {},
    }
    for name, start, end in [
        ("2024_development", "2024-01-01", "2024-12-31"),
        ("2025_validation", "2025-01-01", "2025-12-31"),
        ("2026_already_inspected", "2026-01-01", "2026-09-29"),
    ]:
        period = candidates.loc[candidates["trade_date"].between(start, end) & candidates["exit_date"].between(start, end)]
        output["periods"][name] = summarize(period)
    OUTPUT.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
