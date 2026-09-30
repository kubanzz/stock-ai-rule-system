#!/usr/bin/env python3
"""Predefined 2020-23 stock-group calibration, then 2024-26 time evaluation.

Only phase2 development200 is read. Four simple T-close direction rules are
specified in source before evaluation. Each selects at most 40 stocks using
2020-23 outcomes with beta shrinkage and minimum calibration observations.
No later outcome is used to choose a symbol or threshold. This is development
research, not a production rule publication mechanism.
"""

from __future__ import annotations

import hashlib
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.search_directional_rules import event_frame  # noqa: E402
from research.one_day_20260930.quant_research import wilson  # noqa: E402


HERE = Path(__file__).resolve().parent
SOURCE = HERE / "development_qfq.csv"
OUTPUT = HERE / "calibrated_group_result.json"
CALIBRATION_START = "2020-01-01"
CALIBRATION_END = "2023-12-31"
EVALUATION_YEARS = (2024, 2025, 2026)
MIN_CALIBRATION_SIGNALS_PER_STOCK = 12
SELECTED_STOCKS = 40
PRIOR_STRENGTH = 30


@dataclass(frozen=True)
class FixedRule:
    name: str
    direction: str
    terms: tuple[tuple[str, str, float], ...]

    def mask(self, frame: pd.DataFrame) -> pd.Series:
        result = pd.Series(True, index=frame.index)
        for field, operator, threshold in self.terms:
            if operator == "<=":
                result &= frame[field].le(threshold)
            elif operator == ">=":
                result &= frame[field].ge(threshold)
            else:
                raise AssertionError(operator)
        return result.fillna(False)


# Fixed before using 2024-26 outcomes; no threshold is tuned after evaluation.
RULES = (
    FixedRule("bearish_overbought", "down", (("rsi14", ">=", 70), ("return_1d", ">=", .01))),
    FixedRule("bearish_peak_close", "down", (("rsi14", ">=", 75), ("intraday_return", ">=", .01))),
    FixedRule("bullish_deep_pullback", "up", (("return_1d", "<=", -.04), ("change_pct_5d", "<=", -.05))),
    FixedRule("bullish_oversold", "up", (("rsi14", "<=", 30), ("change_pct_5d", "<=", -.05))),
)


def nonoverlapping_predictions(frame: pd.DataFrame) -> pd.DataFrame:
    """One planned one-day exposure per stock, based on dates only."""
    if frame.empty:
        return frame
    keep = []
    for _, group in frame.sort_values(["symbol", "entry_date", "trade_date"]).groupby("symbol", sort=False):
        last_exit = pd.Timestamp.min
        for index, entry, exit_date in zip(group.index, group["entry_date"], group["exit_date"]):
            if entry > last_exit:
                keep.append(index)
                last_exit = exit_date
    return frame.loc[keep].copy()


def metric(frame: pd.DataFrame, direction: str) -> dict:
    chosen = nonoverlapping_predictions(frame)
    n = len(chosen)
    correct = int(chosen[f"{direction}_correct"].sum())
    days = chosen.groupby("entry_date")[f"{direction}_correct"].mean()
    result = {
        "predictions": n,
        "correct": correct,
        "direction_accuracy": round(correct / n, 5) if n else 0,
        "wilson95_lower": round(wilson(correct, n), 5),
        "symbols": int(chosen["symbol"].nunique()),
        "entry_dates": int(chosen["entry_date"].nunique()),
        "top_symbol_share": round(float(chosen["symbol"].value_counts(normalize=True).iloc[0]), 5) if n else 0,
        "top_date_share": round(float(chosen["entry_date"].value_counts(normalize=True).iloc[0]), 5) if n else 0,
        "mean_daily_accuracy": round(float(days.mean()), 5) if n else 0,
        "unobservable_direction_counted_wrong": int((~chosen["direction_observable"]).sum()),
    }
    if direction == "up":
        executable = chosen.loc[chosen["entry_executable"]]
        wins = int((executable["net_return"].gt(0) & ~executable["blocked_exit"]).sum())
        result.update({
            "executable_long_positions": len(executable),
            "net_long_wins": wins,
            "net_long_win_rate": round(wins / len(executable), 5) if len(executable) else 0,
            "mean_long_net_return": round(float(executable["net_return"].mean()), 6) if len(executable) else 0,
            "blocked_long_exits": int(executable["blocked_exit"].sum()),
        })
    return result


def select_group(calibration: pd.DataFrame, rule: FixedRule) -> tuple[list[str], dict]:
    selected = nonoverlapping_predictions(calibration.loc[rule.mask(calibration)])
    target = f"{rule.direction}_correct"
    pooled_mean = float(selected[target].mean()) if len(selected) else .5
    stats = selected.groupby("symbol")[target].agg(["size", "sum"])
    stats = stats.loc[stats["size"].ge(MIN_CALIBRATION_SIGNALS_PER_STOCK)].copy()
    stats["posterior_mean"] = (
        stats["sum"] + PRIOR_STRENGTH * pooled_mean
    ) / (stats["size"] + PRIOR_STRENGTH)
    # Stable tie break; the entire ranking depends only on calibration data.
    stats = stats.reset_index().sort_values(["posterior_mean", "size", "symbol"], ascending=[False, False, True])
    members = stats["symbol"].head(SELECTED_STOCKS).tolist()
    return members, {
        "calibration_signals": len(selected),
        "pooled_direction_accuracy": round(pooled_mean, 5),
        "calibration_symbols_with_min_signals": len(stats),
        "selected_stock_count": len(members),
        "min_selected_posterior_mean": round(float(stats["posterior_mean"].iloc[min(len(members), len(stats)) - 1]), 5) if members else None,
    }


def main() -> None:
    events = event_frame(SOURCE)
    calibration = events.loc[
        events["trade_date"].between(CALIBRATION_START, CALIBRATION_END)
        & events["exit_date"].le(CALIBRATION_END)
    ].copy()
    result = {
        "development_only": True,
        "source_csv": str(SOURCE),
        "source_sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        "normal_universe": "mainboard_liquid_stable_v1",
        "calibration": [CALIBRATION_START, CALIBRATION_END],
        "evaluation_years": EVALUATION_YEARS,
        "selection": {
            "min_calibration_signals_per_stock": MIN_CALIBRATION_SIGNALS_PER_STOCK,
            "group_size_target": SELECTED_STOCKS,
            "beta_prior_strength": PRIOR_STRENGTH,
            "ranking": "(stock correct + 30 * pooled rule accuracy) / (stock signals + 30), descending; ties by count then symbol",
        },
        "rule_results": [],
    }
    for rule in RULES:
        group, calibration_stats = select_group(calibration, rule)
        item = {
            "rule": asdict(rule),
            "selected_symbols": group,
            "calibration_stats": calibration_stats,
            "calibration_group": metric(calibration.loc[calibration["symbol"].isin(group) & rule.mask(calibration)], rule.direction),
            "evaluation": {},
        }
        for year in EVALUATION_YEARS:
            period = events.loc[
                events["trade_date"].dt.year.eq(year)
                & events["exit_date"].dt.year.eq(year)
            ]
            matched = period.loc[period["symbol"].isin(group) & rule.mask(period)]
            item["evaluation"][str(year)] = metric(matched, rule.direction)
        item["passes_65_all_years"] = (
            len(group) >= 20
            and all(
                m["predictions"] >= 100 and m["entry_dates"] >= 40
                and m["symbols"] >= 20 and m["direction_accuracy"] >= .65
                and (rule.direction != "up" or (
                    m["executable_long_positions"] >= 100
                    and m["net_long_win_rate"] >= .65
                ))
                for m in item["evaluation"].values()
            )
        )
        result["rule_results"].append(item)
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    for item in result["rule_results"]:
        print(item["rule"]["name"], "members", len(item["selected_symbols"]),
              "calibration", item["calibration_group"]["direction_accuracy"],
              "passes", item["passes_65_all_years"])
        for year, m in item["evaluation"].items():
            print(" ", year, m["correct"], "/", m["predictions"], m["direction_accuracy"],
                  "stocks", m["symbols"], "dates", m["entry_dates"],
                  "net", m.get("net_long_win_rate"))


if __name__ == "__main__":
    main()
