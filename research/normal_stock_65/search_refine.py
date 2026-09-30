#!/usr/bin/env python3
"""Explore one additional as-of-T filter for selected bearish development roots.

This only reads the original, previously inspected 166-symbol development CSV.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from pathlib import Path

from search_directional_rules import Atom, atoms, event_frame, mask_rule, metrics, split_events


ROOTS = [
    (Atom("rsi14", ">=", 75), Atom("return_1d", ">=", .01)),
    (Atom("rsi14", ">=", 70), Atom("return_1d", ">=", .01)),
    (Atom("return_1d", ">=", .01), Atom("return_20d", ">=", .15)),
]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", type=Path, default=Path(__file__).resolve().parents[1] / "one_day_20260930/ashare_daily_qfq.csv")
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("search_refine_results.json"))
    args = parser.parse_args()
    split = split_events(event_frame(args.csv))
    options = atoms()
    records = []
    for root in ROOTS:
        for extra in options:
            if extra.field in {a.field for a in root}:
                continue
            rule = root + (extra,)
            m = {name: metrics(data.loc[mask_rule(data, rule)], "down") for name, data in split.items()}
            train, val, dev = (m[s] for s in ("train_2024", "validate_2025", "develop_2026"))
            if train["events"] < 70 or val["events"] < 65 or dev["events"] < 50:
                continue
            if min(train["symbols"], val["symbols"], dev["symbols"]) < 20:
                continue
            if min(train["entry_dates"], val["entry_dates"], dev["entry_dates"]) < 30:
                continue
            records.append({
                "conditions": [asdict(a) for a in rule],
                "condition_text": " AND ".join(a.name() for a in rule),
                "metrics": m,
            })
    # Maximise the weakest year's lower confidence bound, rather than an
    # unusually good recent year. This ranking is fixed before external data.
    records.sort(key=lambda x: (
        min(x["metrics"][s]["wilson95_lower"] for s in ("train_2024", "validate_2025", "develop_2026")),
        min(x["metrics"][s]["gross_direction_accuracy"] for s in ("train_2024", "validate_2025", "develop_2026")),
        min(x["metrics"][s]["events"] for s in ("train_2024", "validate_2025", "develop_2026")),
    ), reverse=True)
    output = {
        "search": "add one atom to three fixed bearish roots",
        "root_count": len(ROOTS),
        "additional_atom_count": len(options),
        "raw_combinations": sum(sum(a.field not in {x.field for x in root} for a in options) for root in ROOTS),
        "survivors_count": len(records),
        "ranking": "max min(2024,2025,2026 Wilson95 lower), then min observed accuracy, then min sample; each period >=70 signals, >=20 symbols, >=30 dates",
        "results": records,
    }
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    for item in records[:20]:
        m = item["metrics"]
        print(item["condition_text"], "|", *(f"{s}: {m[s]['gross_direction_accuracy']:.3f}/{m[s]['events']}" for s in m))
    print("searched", output["raw_combinations"], "survivors", len(records), "wrote", args.output)


if __name__ == "__main__":
    main()
