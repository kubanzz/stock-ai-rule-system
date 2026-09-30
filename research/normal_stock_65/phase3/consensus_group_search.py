#!/usr/bin/env python3
"""Development-only directional consensus groups with an explicit abstention policy.

The group members are single T-close predicates. Fit a 2-of-3 or 3-of-4
``WEIGHTED`` (count, not weight sum) group solely on the 200-stock 2020-23
calibration period, then diagnose the frozen choices on three already exposed
2024-26 stock cohorts. This module never reads the sealed 100-stock cohort.
"""

from __future__ import annotations

import hashlib
import itertools
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import pandas as pd

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
OUTPUT = HERE / "consensus_group_results.json"
CALIBRATION_END = pd.Timestamp("2023-12-31")
YEARS = (2024, 2025, 2026)


@dataclass(frozen=True)
class SingleRule:
    field: str
    op: str
    value: float

    def mask(self, frame: pd.DataFrame) -> np.ndarray:
        field = frame[self.field]
        return (field.le(self.value) if self.op == "<=" else field.ge(self.value)).fillna(False).to_numpy(bool)


# Curated T-day atoms span separate factor families, so a count-based group is
# not merely three thresholds of the same input. Values were specified before
# this script's 2024-26 evaluation; they are broad, round and interpretable.
UP_RULES = (
    SingleRule("rsi14", "<=", 25), SingleRule("rsi14", "<=", 30),
    SingleRule("return_1d", "<=", -.03), SingleRule("return_1d", "<=", -.04),
    SingleRule("change_pct_5d", "<=", -.05), SingleRule("change_pct_5d", "<=", -.07),
    SingleRule("return_20d", "<=", -.10), SingleRule("return_20d", "<=", -.15),
    SingleRule("intraday_return", "<=", -.02), SingleRule("open_gap", "<=", -.02),
    SingleRule("close_position", "<=", .2), SingleRule("lower_shadow", ">=", .6),
    SingleRule("distance_ma5", "<=", -.03), SingleRule("distance_ma20", "<=", -.05),
    SingleRule("volume_ratio_5d", "<=", .7), SingleRule("amount_ratio20", "<=", .7),
    SingleRule("normal_volatility_20d", "<=", .025),
    SingleRule("close_position", ">=", .8), SingleRule("intraday_return", ">=", .02),
)
DOWN_RULES = (
    SingleRule("rsi14", ">=", 70), SingleRule("rsi14", ">=", 75),
    SingleRule("return_1d", ">=", .02), SingleRule("return_1d", ">=", .03),
    SingleRule("change_pct_5d", ">=", .05), SingleRule("change_pct_5d", ">=", .07),
    SingleRule("return_20d", ">=", .10), SingleRule("return_20d", ">=", .15),
    SingleRule("intraday_return", ">=", .02), SingleRule("open_gap", ">=", .02),
    SingleRule("close_position", ">=", .8), SingleRule("upper_shadow", ">=", .6),
    SingleRule("distance_ma5", ">=", .03), SingleRule("distance_ma20", ">=", .05),
    SingleRule("volume_ratio_5d", ">=", 1.5), SingleRule("amount_ratio20", ">=", 1.5),
    SingleRule("normal_volatility_20d", "<=", .025),
    SingleRule("close_position", "<=", .2), SingleRule("intraday_return", "<=", -.02),
)


def nonoverlap(frame: pd.DataFrame) -> pd.DataFrame:
    """Keep the earliest planned holding per stock; no outcomes enter selection."""
    if frame.empty:
        return frame
    ordered = frame.sort_values(["symbol", "entry_date", "trade_date"])
    keep = []
    for _, group in ordered.groupby("symbol", sort=False):
        previous_exit = pd.Timestamp.min
        for index, entry, exit_date in zip(group.index, group["entry_date"], group["exit_date"]):
            if entry > previous_exit:
                keep.append(index)
                previous_exit = exit_date
    return frame.loc[keep]


def date_cluster_lower(frame: pd.DataFrame, correct: pd.Series) -> float | None:
    """Fixed-seed date-cluster 95% lower bound, preserving same-day correlation."""
    if frame.empty or frame["entry_date"].nunique() < 10:
        return None
    daily = pd.DataFrame({"date": frame["entry_date"], "correct": correct.to_numpy()})
    aggregate = daily.groupby("date", sort=True)["correct"].agg(["sum", "size"]).to_numpy()
    rng = np.random.default_rng(20260930)
    draws = rng.integers(0, len(aggregate), size=(1000, len(aggregate)))
    sampled = aggregate[draws]
    rates = sampled[:, :, 0].sum(axis=1) / sampled[:, :, 1].sum(axis=1)
    return round(float(np.quantile(rates, .025)), 5)


def metric(frame: pd.DataFrame, direction: str, eligible_denominator: int) -> dict:
    raw_n = len(frame)
    selected = nonoverlap(frame)
    correct = selected[f"{direction}_correct"]
    n = len(selected)
    wins = int(correct.sum())
    executable = selected.loc[selected["entry_executable"]]
    net_win = executable["net_return"].gt(0) & ~executable["blocked_exit"]
    return {
        "raw_predictions": raw_n, "predictions_nonoverlap": n,
        "correct": wins, "direction_accuracy": round(wins / n, 5) if n else None,
        "direction_wilson95_lower": round(wilson(wins, n), 5) if n else None,
        "direction_date_cluster95_lower": date_cluster_lower(selected, correct),
        "coverage_raw": round(raw_n / eligible_denominator, 6) if eligible_denominator else None,
        "eligible_denominator": eligible_denominator,
        "symbols": int(selected["symbol"].nunique()), "entry_dates": int(selected["entry_date"].nunique()),
        "top_symbol_share": round(float(selected["symbol"].value_counts(normalize=True).iloc[0]), 5) if n else None,
        "top_date_share": round(float(selected["entry_date"].value_counts(normalize=True).iloc[0]), 5) if n else None,
        "unobservable_counted_wrong": int((~selected["direction_observable"]).sum()),
        "blocked_exits": int(selected["blocked_exit"].sum()),
        "executable_long_positions": len(executable),
        "net_long_wins": int(net_win.sum()),
        "net_long_win_rate": round(float(net_win.mean()), 5) if len(executable) else None,
        "net_long_wilson95_lower": round(wilson(int(net_win.sum()), len(executable)), 5) if len(executable) else None,
        "mean_net_long_return": round(float(executable["net_return"].mean()), 6) if len(executable) else None,
    }


def prepare() -> pd.DataFrame:
    frames = []
    for cohort, path in SOURCES.items():
        events = event_frame(path)
        events["cohort"] = cohort
        # From OHLC through T; existing event_frame includes T bar columns.
        day_range = (events["high_price"] - events["low_price"]).replace(0, np.nan)
        events["upper_shadow"] = (events["high_price"] - events[["open_price", "close_price"]].max(axis=1)) / day_range
        events["lower_shadow"] = (events[["open_price", "close_price"]].min(axis=1) - events["low_price"]) / day_range
        frames.append(events)
    all_events = pd.concat(frames, ignore_index=True)
    all_events = all_events.loc[all_events["trade_date"].dt.year.eq(all_events["exit_date"].dt.year)].copy()
    all_events["year"] = all_events["trade_date"].dt.year
    return all_events.reset_index(drop=True)


def group_options(rules: tuple[SingleRule, ...]):
    for size, minimum in ((3, 2), (4, 3)):
        for member_indices in itertools.combinations(range(len(rules)), size):
            fields = [rules[i].field for i in member_indices]
            if len(fields) != len(set(fields)):
                continue
            yield member_indices, minimum


def calibrate_groups(train: pd.DataFrame, rules: tuple[SingleRule, ...], direction: str) -> tuple[list[dict], int]:
    matrix = np.stack([rule.mask(train) for rule in rules]).astype(np.uint8)
    target = (train["net_return"].gt(0) & train["entry_executable"] & ~train["blocked_exit"]).to_numpy(np.uint8) if direction == "up" else train["down_correct"].to_numpy(np.uint8)
    dates = train["entry_date"].to_numpy()
    symbols = train["symbol"].to_numpy()
    candidates = []
    option_count = 0
    for indices, minimum in group_options(rules):
        option_count += 1
        hits = matrix[list(indices)].sum(axis=0) >= minimum
        count = int(hits.sum())
        if count < 200 or count > len(train) // 3:
            continue
        if len(np.unique(dates[hits])) < 50 or len(np.unique(symbols[hits])) < 25:
            continue
        wins = int(target[hits].sum())
        lower = wilson(wins, count)
        candidates.append({
            "indices": list(indices), "min_matched_rules": minimum,
            "train_raw": count, "train_wins": wins,
            "train_rate": round(wins / count, 5), "train_wilson95_lower": round(lower, 5),
        })
    candidates.sort(key=lambda item: (item["train_wilson95_lower"], item["train_raw"]), reverse=True)
    return candidates, option_count


def group_mask(frame: pd.DataFrame, rules: tuple[SingleRule, ...], candidate: dict) -> np.ndarray:
    hit_count = sum(rules[i].mask(frame).astype(np.uint8) for i in candidate["indices"])
    return hit_count >= candidate["min_matched_rules"]


def main() -> None:
    data = prepare()
    train = data.loc[data["cohort"].eq("phase2dev200") & data["trade_date"].le(CALIBRATION_END)].copy()
    evaluation = data.loc[data["year"].isin(YEARS)].copy()
    results = {}
    winners = {}
    for direction, rules in (("up", UP_RULES), ("down", DOWN_RULES)):
        ranked, option_count = calibrate_groups(train, rules, direction)
        # Full candidate ranking is calibrated on 2020-23. Keep the diagnostic
        # pass bounded; cross-cohort outcomes must not choose a replacement.
        short = ranked[:5]
        for item in short:
            item["rules"] = [asdict(rules[i]) for i in item["indices"]]
            train_mask = group_mask(train, rules, item)
            item["calibration"] = metric(train.loc[train_mask], direction, len(train))
            eval_mask = group_mask(evaluation, rules, item)
            item["evaluation"] = {}
            for year in YEARS:
                for cohort in SOURCES:
                    segment = evaluation.loc[evaluation["year"].eq(year) & evaluation["cohort"].eq(cohort)]
                    selected = evaluation.loc[eval_mask & evaluation["year"].eq(year) & evaluation["cohort"].eq(cohort)]
                    item["evaluation"][f"{cohort}_{year}"] = metric(selected, direction, len(segment))
            support_ok = all(m["predictions_nonoverlap"] >= 30 and m["entry_dates"] >= 15 and m["symbols"] >= 10
                             for m in item["evaluation"].values())
            rate_key = "net_long_win_rate" if direction == "up" else "direction_accuracy"
            item["all_nine_at_least_65"] = bool(support_ok and all(m[rate_key] is not None and m[rate_key] >= .65
                                                                    for m in item["evaluation"].values()))
        results[direction] = {"atoms": [asdict(rule) for rule in rules], "option_count": option_count,
                              "calibration_supported": len(ranked), "top5": short}
        winners[direction] = ranked[0] if ranked else None

    # A complete two-direction predictor: issue one direction if only its
    # trained group qualifies, otherwise abstain. This mirrors the backend's
    # directional threshold conflict and leaves all other eligible days as watch.
    up = group_mask(evaluation, UP_RULES, winners["up"]) if winners["up"] else np.zeros(len(evaluation), bool)
    down = group_mask(evaluation, DOWN_RULES, winners["down"]) if winners["down"] else np.zeros(len(evaluation), bool)
    pair = {"up_group": winners["up"], "down_group": winners["down"], "conflict_abstains": int((up & down).sum()),
            "segments": {}}
    for year in YEARS:
        for cohort in SOURCES:
            scope = evaluation["year"].eq(year) & evaluation["cohort"].eq(cohort)
            segment = evaluation.loc[scope]
            direction_result = {}
            for direction, emitted in (("up", up & ~down), ("down", down & ~up)):
                selected = evaluation.loc[scope & emitted]
                direction_result[direction] = metric(selected, direction, len(segment))
            direction_result["abstain_conflict"] = int((scope & up & down).sum())
            direction_result["abstain_neither"] = int((scope & ~up & ~down).sum())
            pair["segments"][f"{cohort}_{year}"] = direction_result

    report = {
        "development_only": True,
        "sources_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in SOURCES.items()},
        "universe": "mainboard_liquid_stable_v1; existing risk_status normal",
        "signal": "T close; T+1 open entry; T+2 open exit; 30 bps long round-trip cost",
        "calibration": "phase2dev200, through 2023-12-31; all groups ranked by calibration Wilson lower",
        "evaluation": "2024-26 x three already exposed development cohorts; no independent validation",
        "group_semantics": "each member one single-rule predicate; WEIGHTED minMatchedRules is match count; no required groups; conflicting up/down or neither means watch",
        "results": results, "trained_pair": pair,
    }
    OUTPUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print("output", OUTPUT)
    for direction in ("up", "down"):
        item = results[direction]["top5"][0]
        print(direction, "calibration", item["calibration"]["predictions_nonoverlap"],
              item["calibration"]["net_long_win_rate" if direction == "up" else "direction_accuracy"])
        for cohort in SOURCES:
            print(cohort, *(f"{year}: {item['evaluation'][f'{cohort}_{year}']['predictions_nonoverlap']}"
                            f"/{item['evaluation'][f'{cohort}_{year}']['net_long_win_rate' if direction == 'up' else 'direction_accuracy']}"
                            for year in YEARS))
        print("top5 passing all nine at 65%", sum(item["all_nine_at_least_65"] for item in results[direction]["top5"]))


if __name__ == "__main__":
    main()
