#!/usr/bin/env python3
"""Targeted three-atom and entry-open order-filter development checks.

Only previously unblinded stock samples are read. No sealed cohort access.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import asdict
from pathlib import Path

import numpy as np

from phase2_search import candidate_metrics, development_events, periods
from search_directional_rules import Atom, atoms, mask_rule


ROOTS = [
    (Atom("return_1d", "<=", -.04), Atom("change_pct_5d", "<=", -.07)),
    (Atom("change_pct_5d", "<=", -.07), Atom("range_pct", ">=", .05)),
    (Atom("return_1d", "<=", -.04), Atom("change_pct_5d", "<=", -.05)),
    (Atom("change_pct_5d", "<=", -.10), Atom("range_pct", ">=", .04)),
]


def evaluate_yearly(data, rule, gap_rule=None):
    output = {}
    for year, frame in data.items():
        selected = frame.loc[mask_rule(frame, rule)]
        if gap_rule is not None:
            op, threshold = gap_rule
            selected = selected.loc[selected["entry_gap"].le(threshold) if op == "<=" else selected["entry_gap"].ge(threshold)]
        output[year] = candidate_metrics(selected, "up_net")
    return output


def broad(m):
    return all(v["positions"] >= 100 and v["symbols"] >= 30 and v["entry_dates"] >= 40 for v in m.values())


def rank(m):
    return (min(v["wilson95_lower"] for v in m.values()),
            min(v["net_win_rate"] for v in m.values()),
            min(v["positions"] for v in m.values()))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("phase2_refine_results.json"))
    args = parser.parse_args()
    data = periods(development_events())
    options = atoms()
    records = []
    tested = 0
    for root in ROOTS:
        for third in options:
            if third.field in {a.field for a in root}:
                continue
            rule = (*root, third)
            tested += 1
            m = evaluate_yearly(data, rule)
            if broad(m):
                records.append({"conditions": [asdict(a) for a in rule],
                                "condition_text": " AND ".join(a.name() for a in rule),
                                "order_filter": None, "metrics": m})
    # Entry-open filters are an order decision at T+1, not a T-close forecast.
    # Apply only to a small fixed set of roots and never label resulting
    # percentages as T-close prediction accuracy.
    for root in ROOTS:
        for op in ("<=", ">="):
            for threshold in (-.02, -.01, 0, .01, .02):
                tested += 1
                m = evaluate_yearly(data, root, (op, threshold))
                if broad(m):
                    records.append({"conditions": [asdict(a) for a in root],
                                    "condition_text": " AND ".join(a.name() for a in root),
                                    "order_filter": {"T_plus_1_entry_gap": f"{op} {threshold}"},
                                    "metrics": m})
    records.sort(key=lambda x: rank(x["metrics"]), reverse=True)
    out = {
        "development_only": True,
        "root_count": len(ROOTS), "additional_atom_count": len(options),
        "tested_combinations": tested, "broad_survivors": len(records),
        "selection": "max minimum yearly Wilson lower bound, 2020-26; each year >=100 executed long positions, >=30 stocks and >=40 entry dates",
        "interpretation": "T-close rules and separate T+1 opening order filter; 30bps net win is an executed-long metric, not T-close directional accuracy",
        "results": records,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
    print("tested", tested, "broad", len(records))
    for r in records[:20]:
        print(r["condition_text"], r["order_filter"],
              [(y, r["metrics"][y]["net_win_rate"], r["metrics"][y]["positions"]) for y in r["metrics"]])


if __name__ == "__main__":
    main()
