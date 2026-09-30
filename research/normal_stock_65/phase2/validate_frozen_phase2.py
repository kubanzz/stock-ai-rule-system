#!/usr/bin/env python3
"""One-shot validation of one frozen phase2 directional rule.

Preparation only until the operator confirms a single frozen rule/hash. The
sealed quote cohort is read only after all hashes and disjointness pass.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.phase2.search_regime_rules import (  # noqa: E402
    Condition, regime_features, regime_conditions, stock_conditions,
)
from research.normal_stock_65.search_directional_rules import Atom, atoms as phase2_atoms, event_frame, metrics  # noqa: E402
from research.one_day_20260930.quant_research import nonoverlapping, wilson  # noqa: E402

HERE = Path(__file__).resolve().parent


def load_frozen(path: Path) -> tuple[dict, tuple[Condition | Atom, ...]]:
    frozen = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(frozen, dict) or frozen.get("direction") not in {"up", "down", "up_net"}:
        raise ValueError("One frozen up/down/up_net direction is required")
    raw = frozen.get("conditions")
    if not isinstance(raw, list) or not 1 <= len(raw) <= 3:
        raise ValueError("One frozen rule with 1-3 conditions is required")
    direction = frozen["direction"]
    conditions = []
    for condition in raw:
        if not isinstance(condition, dict) or set(condition) != {"field", "op", "value"}:
            raise ValueError("Each condition must contain field, op, value")
        if not isinstance(condition["field"], str) or condition["op"] not in {"<", "<=", ">", ">=", "="}:
            raise ValueError("Invalid condition")
        if isinstance(condition["value"], (dict, list, bool)):
            raise ValueError("Invalid condition value")
        conditions.append((Atom if direction == "up_net" else Condition)(**condition))
    allowed = set(phase2_atoms()) if direction == "up_net" else set(stock_conditions() + regime_conditions())
    if len({item.field for item in conditions}) != len(conditions) or any(item not in allowed for item in conditions):
        raise ValueError("Condition outside fixed phase2 development grid or repeated field")
    hs300_count = sum(item.field.startswith("hs300_") for item in conditions)
    if direction == "up_net" and hs300_count:
        raise ValueError("up_net stock-only rule cannot contain an HS300 condition")
    if hs300_count > 1:
        raise ValueError("Frozen rule may include at most one fixed HS300 regime condition")
    order_filter = frozen.get("order_filter")
    if order_filter is not None:
        if direction != "up_net" or not isinstance(order_filter, dict) or set(order_filter) != {"T_plus_1_entry_gap"}:
            raise ValueError("Order filter is only supported for up_net as T_plus_1_entry_gap")
        import re
        match = re.fullmatch(r"(<=|>=) (-?(?:0|0\.0[12]))", str(order_filter["T_plus_1_entry_gap"]))
        if not match or float(match.group(2)) not in {-.02, -.01, 0.0, .01, .02}:
            raise ValueError("Order filter must match the fixed development grid")
    return frozen, tuple(conditions)


def symbols_in(path: Path, *, column: str = "symbol") -> set[str]:
    with path.open(newline="", encoding="utf-8") as fh:
        return {row[column] for row in csv.DictReader(fh)}


def position_metrics(frame: pd.DataFrame, direction: str) -> dict:
    chosen = nonoverlapping(frame.loc[frame["entry_executable"]])
    n = len(chosen)
    correct = int(chosen[f"{direction}_correct"].sum()) if n else 0
    net_wins = int((chosen["net_return"].gt(0) & ~chosen["blocked_exit"]).sum()) if n else 0
    return {
        "positions": n, "correct": correct,
        "gross_direction_accuracy": round(correct / n, 5) if n else 0,
        "gross_direction_wilson95_lower": round(wilson(correct, n), 5),
        "net_wins": net_wins,
        "net_long_win_rate": round(net_wins / n, 5) if n else 0,
        "net_long_wilson95_lower": round(wilson(net_wins, n), 5),
        "mean_net_long_return": round(float(chosen["net_return"].mean()), 6) if n else 0,
        "blocked_exits": int(chosen["blocked_exit"].sum()),
        "entry_dates": int(chosen["entry_date"].nunique()),
        "symbols": int(chosen["symbol"].nunique()),
    }


def apply_order_filter(frame: pd.DataFrame, frozen: dict) -> pd.DataFrame:
    """Filter T+1 executable orders, never the T-close prediction cohort."""
    order_filter = frozen.get("order_filter")
    if order_filter is None:
        return frame
    operator, raw_threshold = order_filter["T_plus_1_entry_gap"].split()
    threshold = float(raw_threshold)
    return frame.loc[
        frame["entry_gap"].le(threshold) if operator == "<="
        else frame["entry_gap"].ge(threshold)
    ].copy()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--frozen", type=Path, required=True)
    parser.add_argument("--expected-frozen-sha256", required=True)
    parser.add_argument("--sealed-csv", type=Path, required=True)
    parser.add_argument("--phase2-manifest", type=Path, required=True)
    parser.add_argument("--phase2-universe", type=Path, required=True)
    parser.add_argument("--old-universe", type=Path, required=True)
    parser.add_argument("--first-external-universe", type=Path, required=True)
    parser.add_argument("--hs300-csv", type=Path)
    parser.add_argument("--hs300-manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    frozen_hash = hashlib.sha256(args.frozen.read_bytes()).hexdigest()
    if frozen_hash != args.expected_frozen_sha256:
        raise ValueError("Frozen candidate hash mismatch")
    frozen, conditions = load_frozen(args.frozen)
    phase2_manifest = json.loads(args.phase2_manifest.read_text(encoding="utf-8"))
    if hashlib.sha256(args.phase2_universe.read_bytes()).hexdigest() != phase2_manifest["universe_sha256"]:
        raise ValueError("Phase2 cohort universe hash mismatch")
    if hashlib.sha256((HERE / "cohort_assignment.csv").read_bytes()).hexdigest() != phase2_manifest["cohort_assignment_sha256"]:
        raise ValueError("Pre-fetch cohort assignment hash mismatch")
    sealed_hash = hashlib.sha256(args.sealed_csv.read_bytes()).hexdigest()
    if sealed_hash != phase2_manifest["cohorts"]["validation_sealed"]["sha256"]:
        raise ValueError("Sealed quote hash mismatch")
    uses_hs300 = any(item.field.startswith("hs300_") for item in conditions)
    hs300_hash = None
    if uses_hs300:
        if args.hs300_csv is None or args.hs300_manifest is None:
            raise ValueError("Fixed HS300 CSV and manifest required for this rule")
        index_manifest = json.loads(args.hs300_manifest.read_text(encoding="utf-8"))
        hs300_hash = hashlib.sha256(args.hs300_csv.read_bytes()).hexdigest()
        if hs300_hash != index_manifest["sha256"]:
            raise ValueError("Fixed HS300 hash mismatch")
        # The search module's regime_features uses the fixed adjacent file.
        if args.hs300_csv.resolve() != (HERE / "hs300_daily.csv").resolve():
            raise ValueError("HS300 path differs from development search reference")
        if frozen.get("fixed_hs300_sha256") and frozen["fixed_hs300_sha256"] != hs300_hash:
            raise ValueError("Frozen rule references a different HS300 file")
    old = symbols_in(args.old_universe) | symbols_in(args.first_external_universe)
    with args.phase2_universe.open(newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    development = {row["symbol"] for row in rows if row["cohort"] == "development"}
    sealed = {row["symbol"] for row in rows if row["cohort"] == "validation_sealed"}
    if len(sealed) != 100 or sealed & development or sealed & old:
        raise ValueError("Sealed cohort is not disjoint from all development cohorts")
    # This is a symbol-only pass, not an outcome read.
    if symbols_in(args.sealed_csv) != sealed:
        raise ValueError("Sealed CSV symbol set differs from preassigned universe")

    # First outcome-producing operation: one rule only, no ranking or tuning.
    events = event_frame(args.sealed_csv)
    if uses_hs300:
        index = regime_features()
        events = events.merge(index, on="trade_date", how="left", validate="many_to_one")
        observed = events.dropna(subset=["hs300_return_20d"]).copy()
    else:
        observed = events.copy()
    mask = np.ones(len(observed), dtype=bool)
    for condition in conditions:
        mask &= condition.mask(observed)
    direction = frozen["direction"]
    order_filter = frozen.get("order_filter")
    periods = {}
    for label, start, end in (
        ("2020_2023_auxiliary", "2020-01-01", "2023-12-31"),
        ("2024_auxiliary", "2024-01-01", "2024-12-31"),
        ("2025_auxiliary", "2025-01-01", "2025-12-31"),
        ("2026_primary", "2026-01-01", "2026-09-29"),
    ):
        period_mask = observed["trade_date"].ge(start) & observed["exit_date"].le(end)
        period = observed.loc[period_mask].copy()
        signal_selected = period.loc[mask[period_mask.to_numpy()]].copy()
        executable_selected = apply_order_filter(signal_selected, frozen)
        unavailable = events.loc[
            (events["trade_date"].ge(start) & events["exit_date"].le(end))
            & events["hs300_return_20d"].isna()
        ] if uses_hs300 else events.iloc[0:0]
        periods[label] = {
            "selected_signal_time": metrics(signal_selected, "up" if direction == "up_net" else direction),
            "baseline_signal_time": metrics(period, "up" if direction == "up_net" else direction),
            "selected_executable_long": position_metrics(executable_selected, "up" if direction == "up_net" else direction),
            "baseline_executable_long": position_metrics(period, "up" if direction == "up_net" else direction),
            "index_unavailable_signal_days": int(unavailable["trade_date"].nunique()),
            "index_unavailable_stock_days": len(unavailable),
        }
    main = periods["2026_primary"]["selected_signal_time"]
    main_executable = periods["2026_primary"]["selected_executable_long"]
    report = {
        "candidate_id": frozen.get("candidate_id"),
        "frozen_rule_sha256": frozen_hash,
        "sealed_csv_sha256": sealed_hash,
        "fixed_hs300_sha256": hs300_hash,
        "sealed_symbols": len(sealed), "development_overlap_symbols": 0,
        "direction": direction,
        "conditions": [dict(field=c.field, op=c.op, value=c.value) for c in conditions],
        "order_filter": order_filter,
        "primary_metric": "net_long_win_rate_after_cost" if direction == "up_net" else "gross_direction_accuracy",
        "periods": periods,
        "2026_gross_direction_65_observed": direction != "up_net" and main["events"] >= 100 and main["entry_dates"] >= 30
        and main["symbols"] >= 20 and main["gross_direction_accuracy"] >= .65,
        "2026_gross_direction_65_wilson": direction != "up_net" and main["events"] >= 100 and main["entry_dates"] >= 30
        and main["symbols"] >= 20 and main["wilson95_lower"] >= .65,
        "2026_net_long_65_observed": direction == "up_net" and main_executable["positions"] >= 100
        and main_executable["entry_dates"] >= 30 and main_executable["symbols"] >= 20
        and main_executable["net_long_win_rate"] >= .65,
        "2026_net_long_65_wilson": direction == "up_net" and main_executable["positions"] >= 100
        and main_executable["entry_dates"] >= 30 and main_executable["symbols"] >= 20
        and main_executable["net_long_wilson95_lower"] >= .65,
        "limitations": [
            "If an HS300 condition is present, only dates covered by the fixed index reference are evaluated; earlier periods are unavailable.",
            "Gross direction accuracy is not tradable net win rate; down means avoid long, not short profit.",
            "Sealed stocks share market dates with development and are selected from a current-listed non-ST snapshot.",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
