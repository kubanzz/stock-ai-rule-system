#!/usr/bin/env python3
"""Compare fixed normal-universe eligibility with the existing event baseline.

Only development periods through 2025 are summarized here. The source's 2026
outcomes are deliberately not used in defining or evaluating the universe.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from research.normal_stock_65.normal_universe import (
    NORMAL_UNIVERSE_ID, add_normal_universe_eligibility,
)
from research.one_day_20260930.quant_research import (
    COST, build_events, calculate_factors, load_quotes, metrics, nonoverlapping,
)


def directional_baseline(events):
    positions = nonoverlapping(events)
    result = metrics(events)
    gross_up = (positions["net_return"].gt(-COST) & ~positions["blocked_exit"])
    result["gross_up_count"] = int(gross_up.sum())
    result["gross_up_rate"] = round(float(gross_up.mean()), 4) if len(positions) else 0
    result["net_profitable_count"] = result["wins"]
    result["net_profitable_rate"] = result["win_rate"]
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, default=Path(__file__).parents[1] / "one_day_20260930/ashare_daily_qfq.csv")
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("development_baseline.json"))
    args = parser.parse_args()
    quotes = load_quotes(str(args.csv))
    flags = add_normal_universe_eligibility(quotes)[[
        "symbol", "trade_date", "normal_universe_eligible",
    ]]
    events = build_events(calculate_factors(quotes))
    events = events.merge(flags, on=["symbol", "trade_date"], how="left", validate="one_to_one")
    if events["normal_universe_eligible"].isna().any():
        raise AssertionError("Event missing same-day universe flag")
    results = {"normal_universe_id": NORMAL_UNIVERSE_ID, "periods": {}}
    for name, start, end in [
        ("2024_development", "2024-01-01", "2024-12-31"),
        ("2025_development", "2025-01-01", "2025-12-31"),
        ("2026_development_already_inspected", "2026-01-01", "2026-09-29"),
    ]:
        period = events.loc[
            events["trade_date"].between(start, end)
            & events["exit_date"].between(start, end)
        ]
        normal = period.loc[period["normal_universe_eligible"]]
        other = period.loc[~period["normal_universe_eligible"]]
        results["periods"][name] = {
            "eligible_normal": directional_baseline(normal),
            "other_eligible_events": directional_baseline(other),
            "all_eligible_events": directional_baseline(period),
            "normal_raw_event_share": round(len(normal) / len(period), 4) if len(period) else 0,
        }
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
