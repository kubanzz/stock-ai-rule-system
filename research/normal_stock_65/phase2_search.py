#!/usr/bin/env python3
"""Phase 2 development search on already unblinded, disjoint stock samples.

This module does not read or search the second external validation sample.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import asdict
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.search_directional_rules import (  # noqa: E402
    Atom,
    add_features,
    atoms,
    event_frame,
    mask_rule,
    metrics,
)
from research.one_day_20260930.quant_research import (  # noqa: E402
    load_quotes,
    nonoverlapping,
    wilson,
)

OLD = ROOT / "research/one_day_20260930/ashare_daily_qfq.csv"
FIRST_EXTERNAL = ROOT / "research/normal_stock_65/external_mainboard_qfq.csv"
SECOND_DEVELOPMENT = ROOT / "research/normal_stock_65/phase2/development_qfq.csv"


def development_events() -> pd.DataFrame:
    # The two samples were both unblinded in phase 1; they are development now.
    first = event_frame(OLD)
    second = event_frame(FIRST_EXTERNAL)
    third = event_frame(SECOND_DEVELOPMENT)
    assert not set(first.symbol).intersection(second.symbol)
    assert not set(first.symbol).intersection(third.symbol)
    assert not set(second.symbol).intersection(third.symbol)
    first["sample"] = "old166"
    second["sample"] = "first_external250"
    third["sample"] = "phase2_development200"
    joined = pd.concat([first, second, third], ignore_index=True)
    joined["long_net_win"] = joined["entry_executable"] & ~joined["blocked_exit"] & joined["net_return"].gt(0)
    return joined


def periods(events: pd.DataFrame) -> dict[str, pd.DataFrame]:
    output = {}
    for year in (2020, 2021, 2022, 2023, 2024, 2025, 2026):
        output[str(year)] = events.loc[
            events["trade_date"].dt.year.eq(year) & events["exit_date"].dt.year.eq(year)
        ].copy()
    return output


def candidate_metrics(frame: pd.DataFrame, direction: str) -> dict:
    if direction == "up_net":
        chosen = nonoverlapping(frame.loc[frame["entry_executable"]].copy())
        n = len(chosen)
        wins = int((chosen["net_return"].gt(0) & ~chosen["blocked_exit"]).sum())
        daily = chosen.groupby("entry_date")["long_net_win"].mean() if n else pd.Series(dtype=float)
        return {
            "positions": n, "wins": wins, "net_win_rate": round(wins / n, 5) if n else 0,
            "wilson95_lower": round(wilson(wins, n), 5),
            "mean_net_return": round(float(chosen["net_return"].mean()), 6) if n else 0,
            "symbols": int(chosen["symbol"].nunique()),
            "entry_dates": int(chosen["entry_date"].nunique()),
            "mean_daily_win_rate": round(float(daily.mean()), 5) if n else 0,
            "blocked_exits": int(chosen["blocked_exit"].sum()),
            "top_symbol_share": round(float(chosen["symbol"].value_counts(normalize=True).iloc[0]), 5) if n else 0,
        }
    return metrics(frame, "down")


def search_two_atom(data: dict[str, pd.DataFrame], direction: str, atom_list: list[Atom]) -> tuple[list[dict], dict]:
    # Keep broad year-by-year signal coverage before exact nonoverlap metrics.
    rules = [(a,) for a in atom_list]
    for i, first in enumerate(atom_list):
        for second in atom_list[i + 1:]:
            if first.field != second.field:
                rules.append((first, second))
    arrays = {year: np.vstack([atom.mask(frame) for atom in atom_list]) for year, frame in data.items()}
    idx = {a: i for i, a in enumerate(atom_list)}
    coarse = []
    for rule in rules:
        ids = [idx[a] for a in rule]
        yearly = {}
        okay = True
        for year, frame in data.items():
            selected = arrays[year][ids[0]].copy()
            if len(ids) == 2:
                selected &= arrays[year][ids[1]]
            if direction == "up_net":
                selected &= frame["entry_executable"].to_numpy(dtype=bool)
            n = int(selected.sum())
            if n < 120:
                okay = False
                break
            wins = int((frame["long_net_win"] if direction == "up_net" else frame["down_correct"])[selected].sum())
            rate = wins / n
            if rate < (.48 if direction == "up_net" else .52):
                okay = False
                break
            yearly[year] = {"raw_events": n, "raw_correct": wins, "raw_rate": rate, "raw_wilson": wilson(wins, n)}
        if okay:
            coarse.append((min(v["raw_wilson"] for v in yearly.values()), rule, yearly))
    coarse.sort(key=lambda x: x[0], reverse=True)
    finalists = []
    for _, rule, _ in coarse[:150]:
        m = {year: candidate_metrics(frame.loc[mask_rule(frame, rule)], direction) for year, frame in data.items()}
        if min(v["positions" if direction == "up_net" else "events"] for v in m.values()) < 100:
            continue
        if min(v["symbols"] for v in m.values()) < 30:
            continue
        if min(v["entry_dates"] for v in m.values()) < 40:
            continue
        finalists.append({"conditions": [asdict(a) for a in rule],
                          "condition_text": " AND ".join(a.name() for a in rule), "metrics": m})
    finalists.sort(key=lambda x: (
        min(v["wilson95_lower"] for v in x["metrics"].values()),
        min(v["net_win_rate" if direction == "up_net" else "gross_direction_accuracy"] for v in x["metrics"].values()),
    ), reverse=True)
    return finalists, {"raw_rule_count": len(rules), "coarse_survivors": len(coarse), "exact_finalists": len(finalists)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("phase2_search_results.json"))
    args = parser.parse_args()
    events = development_events()
    data = periods(events)
    atom_list = atoms()
    results = {}
    for direction in ("up_net", "down"):
        finalists, counts = search_two_atom(data, direction, atom_list)
        results[direction] = {"counts": counts, "top": finalists[:50]}
        print(direction, counts)
        for item in finalists[:10]:
            m = item["metrics"]
            print(item["condition_text"], "|", *(f"{y}:{m[y]['net_win_rate' if direction=='up_net' else 'gross_direction_accuracy']:.3f}/{m[y]['positions' if direction=='up_net' else 'events']}" for y in m))
    report = {
        "development_only": True,
        "input_sha256": {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in (OLD, FIRST_EXTERNAL, SECOND_DEVELOPMENT)},
        "samples": events["sample"].value_counts().to_dict(),
        "symbols": int(events["symbol"].nunique()),
        "period_baseline": {direction: {year: candidate_metrics(frame, direction) for year, frame in data.items()} for direction in ("up_net", "down")},
        "atomic_condition_count": len(atom_list),
        "results": results,
        "interpretation": "up_net is an executable long, 30bps net-win search. down is gross direction and long avoidance only. First external sample is development; second is untouched.",
    }
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print("wrote", args.output)


if __name__ == "__main__":
    main()
