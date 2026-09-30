#!/usr/bin/env python3
"""Validate exactly one frozen directional rule on disjoint external symbols.

Do not run this before the candidate JSON and its SHA-256 are frozen and logged.
This module performs no search, no threshold optimization and no DB writes.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.search_directional_rules import (  # noqa: E402
    Atom, atoms as search_atoms, event_frame, mask_rule, metrics,
)
from research.one_day_20260930.quant_research import (  # noqa: E402
    nonoverlapping, wilson,
)

OPERATORS = {"<=", ">=", "<", ">", "="}


def load_frozen(path: Path) -> tuple[dict, tuple[Atom, ...]]:
    frozen = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(frozen, dict) or frozen.get("direction") not in {"up", "down"}:
        raise ValueError("Frozen file must contain one direction: up or down")
    conditions = frozen.get("conditions")
    if not isinstance(conditions, list) or not 1 <= len(conditions) <= 3:
        raise ValueError("Frozen file must contain exactly one rule with 1-3 conditions")
    atoms = []
    for condition in conditions:
        if not isinstance(condition, dict) or set(condition) != {"field", "op", "value"}:
            raise ValueError("Each frozen condition must contain field, op, value")
        field, op, value = condition["field"], condition["op"], condition["value"]
        if not isinstance(field, str) or op not in OPERATORS or isinstance(value, (dict, list, bool)):
            raise ValueError("Invalid frozen condition")
        atoms.append(Atom(field, op, value))
    if len({atom.field for atom in atoms}) != len(atoms):
        raise ValueError("Repeated fields in frozen rule")
    allowed_atoms = set(search_atoms())
    if any(atom not in allowed_atoms for atom in atoms):
        raise ValueError("Frozen rule contains a condition outside the development search grid")
    return frozen, tuple(atoms)


def position_metrics(frame: pd.DataFrame, direction: str) -> dict:
    """Diagnostic for executable long positions after same-symbol deduplication."""
    chosen = nonoverlapping(frame.loc[frame["entry_executable"]])
    n = len(chosen)
    correct = int(chosen[f"{direction}_correct"].sum()) if n else 0
    return {
        "positions": n, "correct": correct,
        "gross_direction_accuracy": round(correct / n, 5) if n else 0,
        "wilson95_lower": round(wilson(correct, n), 5),
        "net_long_win_rate": round(float(chosen["net_return"].gt(0).mean()), 5) if n else 0,
        "mean_net_long_return": round(float(chosen["net_return"].mean()), 6) if n else 0,
        "blocked_exits": int(chosen["blocked_exit"].sum()),
        "entry_dates": int(chosen["entry_date"].nunique()),
        "symbols": int(chosen["symbol"].nunique()),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--frozen", type=Path, required=True)
    parser.add_argument("--external-csv", type=Path, required=True)
    parser.add_argument("--external-manifest", type=Path, required=True)
    parser.add_argument("--old-universe", type=Path, required=True)
    parser.add_argument("--expected-frozen-sha256", required=True,
                        help="SHA-256 announced before external outcomes are read")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    frozen_hash = hashlib.sha256(args.frozen.read_bytes()).hexdigest()
    if frozen_hash != args.expected_frozen_sha256:
        raise ValueError("Frozen rule SHA-256 mismatch")
    frozen, rule = load_frozen(args.frozen)
    external_hash = hashlib.sha256(args.external_csv.read_bytes()).hexdigest()
    manifest = json.loads(args.external_manifest.read_text(encoding="utf-8"))
    if external_hash != manifest.get("data_sha256"):
        raise ValueError("External CSV hash does not match manifest")
    with args.old_universe.open(newline="", encoding="utf-8") as fh:
        old_symbols = {row["symbol"] for row in csv.DictReader(fh)}
    with args.external_csv.open(newline="", encoding="utf-8") as fh:
        external_symbols = {row["symbol"] for row in csv.DictReader(fh)}
    if old_symbols & external_symbols:
        raise ValueError("External sample overlaps development universe")

    # The only outcome-producing call. There is one candidate and one 2026
    # evaluation; no caller-supplied search, ranking or threshold tuning.
    events = event_frame(args.external_csv)
    unknown = {atom.field for atom in rule}.difference(events.columns)
    if unknown:
        raise ValueError(f"Unknown frozen fields: {sorted(unknown)}")
    direction = frozen["direction"]
    periods = {}
    for name, start, end in (
        ("2024_auxiliary", "2024-01-01", "2024-12-31"),
        ("2025_auxiliary", "2025-01-01", "2025-12-31"),
        ("2026_primary", "2026-01-01", "2026-09-29"),
    ):
        period = events.loc[events["trade_date"].ge(start) & events["exit_date"].le(end)].copy()
        selected = period.loc[mask_rule(period, rule)]
        periods[name] = {
            "signal_time_selected": metrics(selected, direction),
            "signal_time_baseline": metrics(period, direction),
            "executable_positions_selected": position_metrics(selected, direction),
            "executable_positions_baseline": position_metrics(period, direction),
        }
    observed = periods["2026_primary"]["signal_time_selected"]
    report = {
        "candidate_id": frozen.get("candidate_id"),
        "frozen_rule_sha256": frozen_hash,
        "external_csv_sha256": external_hash,
        "external_symbols": len(external_symbols),
        "development_overlap_symbols": 0,
        "signal_period": "2026-01-01 through 2026-09-29",
        "direction": direction,
        "conditions": [dict(field=atom.field, op=atom.op, value=atom.value) for atom in rule],
        "periods": periods,
        "gross_direction_65_observed": observed["events"] >= 100 and observed["entry_dates"] >= 30
        and observed["symbols"] >= 20 and observed["gross_direction_accuracy"] >= .65,
        "gross_direction_65_wilson": observed["events"] >= 100 and observed["entry_dates"] >= 30
        and observed["symbols"] >= 20 and observed["wilson95_lower"] >= .65,
        "interpretation": "Signal-time gross direction accuracy includes missing next-session price as failure, counts adjacent same-symbol forecasts, and excludes cost. Executable long positions remove overlapping same-symbol entries and include 30bp round-trip cost. Down means avoid a long, not short-trade profit.",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
