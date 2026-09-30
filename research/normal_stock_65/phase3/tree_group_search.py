#!/usr/bin/env python3
"""Development-only interpretable rule-group discovery; never reads sealed stocks.

Fit shallow trees on 2020-23 phase2-development stocks, select paths by leaf
confidence, and evaluate 2024-26 on all three exposed development cohorts.
Each selected path is a conjunctive single rule; their union is an OR group.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.tree import DecisionTreeClassifier, _tree


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.search_directional_rules import event_frame  # noqa: E402
from research.one_day_20260930.quant_research import wilson  # noqa: E402

HERE = Path(__file__).resolve().parent
SOURCES = {
    "old166": ROOT / "research/one_day_20260930/ashare_daily_qfq.csv",
    "first250": ROOT / "research/normal_stock_65/external_mainboard_qfq.csv",
    "phase2dev200": ROOT / "research/normal_stock_65/phase2/development_qfq.csv",
}
FEATURES = [
    "rsi14", "return_1d", "return_2d", "change_pct_5d", "return_10d",
    "return_20d", "intraday_return", "open_gap", "close_position", "range_pct",
    "distance_ma5", "distance_ma20", "volume_ratio_5d", "amount_ratio20",
    "normal_volatility_20d",
]
CALIBRATION_END = "2023-12-31"


def path_leaves(model: DecisionTreeClassifier) -> dict[int, list[dict]]:
    tree = model.tree_
    result: dict[int, list[dict]] = {}

    def visit(node: int, terms: list[dict]) -> None:
        if tree.feature[node] == _tree.TREE_UNDEFINED:
            result[node] = terms
            return
        field = FEATURES[tree.feature[node]]
        threshold = float(tree.threshold[node])
        visit(tree.children_left[node], terms + [{"field": field, "op": "<=", "value": threshold}])
        visit(tree.children_right[node], terms + [{"field": field, "op": ">", "value": threshold}])

    visit(0, [])
    return result


def metrics(frame: pd.DataFrame, predicted: np.ndarray, year: int | None = None, cohort: str | None = None) -> dict:
    chosen = frame.loc[predicted].copy()
    if year is not None:
        chosen = chosen.loc[chosen["trade_date"].dt.year.eq(year) & chosen["exit_date"].dt.year.eq(year)]
    if cohort is not None:
        chosen = chosen.loc[chosen["cohort"].eq(cohort)]
    n = len(chosen)
    wins = int(chosen["up_correct"].sum())
    shorts = int(chosen["down_correct"].sum())
    executable = chosen.loc[chosen["entry_executable"]]
    net = int((executable["net_return"].gt(0) & ~executable["blocked_exit"]).sum())
    return {
        "predictions": n,
        "up_correct": wins,
        "up_accuracy": round(wins / n, 5) if n else None,
        "up_wilson95_lower": round(wilson(wins, n), 5),
        "down_correct": shorts,
        "down_accuracy": round(shorts / n, 5) if n else None,
        "symbols": int(chosen["symbol"].nunique()),
        "dates": int(chosen["entry_date"].nunique()),
        "top_date_share": round(float(chosen["entry_date"].value_counts(normalize=True).iloc[0]), 5) if n else 0,
        "executable_long": len(executable),
        "net_long_wins": net,
        "net_long_win_rate": round(net / len(executable), 5) if len(executable) else None,
        "mean_net_long_return": round(float(executable["net_return"].mean()), 6) if len(executable) else None,
    }


def main() -> None:
    frames = []
    for name, path in SOURCES.items():
        frame = event_frame(path)
        frame["cohort"] = name
        frames.append(frame)
    all_events = pd.concat(frames, ignore_index=True)
    all_events = all_events.loc[all_events[FEATURES].notna().all(axis=1)].copy().reset_index(drop=True)
    train = all_events.loc[all_events["cohort"].eq("phase2dev200")
                           & all_events["trade_date"].le(CALIBRATION_END)
                           & all_events["exit_date"].le(CALIBRATION_END)]
    x_train = train[FEATURES].to_numpy(dtype=np.float32)
    x_all = all_events[FEATURES].to_numpy(dtype=np.float32)
    results = []
    for direction in ("up", "down"):
        y_train = train[f"{direction}_correct"].to_numpy(dtype=np.uint8)
        for min_leaf in (80, 150, 300, 500):
            for max_depth in (2, 3, 4):
                model = DecisionTreeClassifier(max_depth=max_depth, min_samples_leaf=min_leaf,
                                               max_leaf_nodes=12, random_state=20260930)
                model.fit(x_train, y_train)
                train_leaf = model.apply(x_train)
                all_leaf = model.apply(x_all)
                leaf_stats = {}
                paths = path_leaves(model)
                for leaf in np.unique(train_leaf):
                    subset = y_train[train_leaf == leaf]
                    correct = int(subset.sum())
                    n = len(subset)
                    leaf_stats[int(leaf)] = {
                        "n": n, "correct": correct,
                        "accuracy": round(correct / n, 5),
                        "wilson95_lower": round(wilson(correct, n), 5),
                        "path": paths[int(leaf)],
                    }
                ranked = sorted(leaf_stats, key=lambda leaf: leaf_stats[leaf]["wilson95_lower"], reverse=True)
                for leaf_count in (1, 2, 3):
                    selected = ranked[:leaf_count]
                    mask = np.isin(all_leaf, selected)
                    calibration = metrics(all_events, mask & all_events["cohort"].eq("phase2dev200").to_numpy()
                                          & all_events["trade_date"].le(CALIBRATION_END).to_numpy())
                    segments = {}
                    for year in (2024, 2025, 2026):
                        for cohort in SOURCES:
                            segments[f"{cohort}_{year}"] = metrics(all_events, mask, year, cohort)
                    # Each path is one single rule; selected paths form an OR group.
                    results.append({
                        "min_leaf": min_leaf, "max_depth": max_depth, "direction": direction,
                        "leaf_count": leaf_count, "selected_leaf_ids": selected,
                        "rules": [leaf_stats[leaf] for leaf in selected],
                        "calibration": calibration, "segments": segments,
                    })
    report = {
        "objective": "Development-only shallow-tree OR rule groups; no sealed validation read",
        "sources_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in SOURCES.items()},
        "features": FEATURES,
        "calibration": "phase2dev200 2020-23 only; one-day signal T-close, direction T+1/T+2 open",
        "configurations": len(results), "results": results,
    }
    output = HERE / "tree_group_results.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print("configurations", len(results), "output", output)
    for direction in ("up", "down"):
        eligible = [item for item in results if item["direction"] == direction
                    and all(item["segments"][f"{cohort}_{year}"]["predictions"] >= 20
                            for cohort in SOURCES for year in (2024, 2025, 2026))]
        eligible.sort(key=lambda item: min(item["segments"][f"{cohort}_{year}"][f"{direction}_accuracy"]
                                           for cohort in SOURCES for year in (2024, 2025, 2026)), reverse=True)
        print(direction, "broad_configs", len(eligible))
        for item in eligible[:5]:
            print(item["min_leaf"], item["max_depth"], item["leaf_count"],
                  min(item["segments"][f"{cohort}_{year}"][f"{direction}_accuracy"]
                      for cohort in SOURCES for year in (2024, 2025, 2026)))


if __name__ == "__main__":
    main()
