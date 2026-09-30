#!/usr/bin/env python3
"""Development-only search for interpretable one-day directional rules.

Uses the original 166 and first external 250 stocks, both already inspected.
Market regime comes from a fixed HS300 file known at signal-day close. A second
external stock cohort must not be read here. Predictions use all eligible T
signals; future entry tradability never filters the directional denominator.
"""

from __future__ import annotations

import hashlib
import json
import math
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
OLD = ROOT / "research/one_day_20260930/ashare_daily_qfq.csv"
FIRST_EXTERNAL = ROOT / "research/normal_stock_65/external_mainboard_qfq.csv"
SECOND_DEVELOPMENT = HERE / "development_qfq.csv"
HS300 = HERE / "hs300_daily.csv"
OUTPUT = HERE / "regime_search_results.json"


@dataclass(frozen=True)
class Condition:
    field: str
    op: str
    value: float | str

    def name(self) -> str:
        return f"{self.field} {self.op} {self.value}"

    def mask(self, frame: pd.DataFrame) -> np.ndarray:
        values = frame[self.field]
        if self.op == "<=":
            return values.le(self.value).fillna(False).to_numpy(dtype=bool)
        if self.op == ">=":
            return values.ge(self.value).fillna(False).to_numpy(dtype=bool)
        if self.op == "<":
            return values.lt(self.value).fillna(False).to_numpy(dtype=bool)
        if self.op == ">":
            return values.gt(self.value).fillna(False).to_numpy(dtype=bool)
        return values.eq(self.value).fillna(False).to_numpy(dtype=bool)


def regime_features() -> pd.DataFrame:
    index = pd.read_csv(HS300, parse_dates=["trade_date"]).sort_values("trade_date")
    if index.duplicated("trade_date").any():
        raise ValueError("duplicate HS300 date")
    close = pd.to_numeric(index["close"], errors="raise")
    for span in (1, 5, 20):
        index[f"hs300_return_{span}d"] = close / close.shift(span) - 1
    index["hs300_vs_ma20"] = close / close.rolling(20).mean() - 1
    return index[["trade_date", "hs300_return_1d", "hs300_return_5d", "hs300_return_20d", "hs300_vs_ma20"]]


def development_events() -> pd.DataFrame:
    old = event_frame(OLD)
    old["cohort"] = "old166"
    external = event_frame(FIRST_EXTERNAL)
    external["cohort"] = "first_external250"
    second = event_frame(SECOND_DEVELOPMENT)
    second["cohort"] = "second_development200"
    for left, right in ((old, external), (old, second), (external, second)):
        shared = set(left["symbol"]).intersection(right["symbol"])
        if shared:
            raise ValueError(f"development cohorts overlap: {sorted(shared)[:3]}")
    events = pd.concat([old, external, second], ignore_index=True)
    events = events.merge(regime_features(), on="trade_date", how="left", validate="many_to_one")
    events["year"] = events["trade_date"].dt.year
    # The outcome must remain within its own calendar year.
    events = events.loc[events["year"].between(2024, 2026) & events["exit_date"].dt.year.eq(events["year"])].copy()
    if events["hs300_return_20d"].isna().any():
        raise ValueError("missing fixed HS300 regime for eligible signal in 2024-26")
    return events.reset_index(drop=True)


def stock_conditions() -> list[Condition]:
    grids = {
        "rsi14": ("<=", [25, 30, 35, 40, 45, 50]),
        "rsi14_high": (">=", [55, 60, 65, 70, 75]),
        "return_1d": ("<=", [-.03, -.02, -.01, 0]),
        "return_1d_high": (">=", [0, .01, .02, .03]),
        "change_pct_5d": ("<=", [-.07, -.05, -.03, 0]),
        "change_pct_5d_high": (">=", [0, .03, .05, .07]),
        "return_20d": ("<=", [-.10, -.05, 0]),
        "return_20d_high": (">=", [0, .05, .10]),
        "intraday_return": ("<=", [-.02, -.01, 0]),
        "intraday_return_high": (">=", [0, .01, .02]),
        "volume_ratio_5d": ("<=", [.7, 1.0]),
        "volume_ratio_5d_high": (">=", [1.0, 1.3]),
        "close_position": ("<=", [.2, .4]),
        "close_position_high": (">=", [.6, .8]),
        "normal_volatility_20d": ("<=", [.02, .03]),
    }
    result = []
    for name, (op, values) in grids.items():
        field = name.replace("_high", "")
        result.extend(Condition(field, op, value) for value in values)
    return result


def regime_conditions() -> list[Condition]:
    return [
        Condition("hs300_return_1d", "<=", -.01),
        Condition("hs300_return_1d", ">=", .01),
        Condition("hs300_return_5d", "<=", -.03),
        Condition("hs300_return_5d", ">=", .03),
        Condition("hs300_return_20d", "<=", -.05),
        Condition("hs300_return_20d", ">=", .05),
        Condition("hs300_vs_ma20", "<=", 0),
        Condition("hs300_vs_ma20", ">=", 0),
    ]


def slices(frame: pd.DataFrame) -> dict[str, np.ndarray]:
    return {
        f"{cohort}_{year}": (frame["cohort"].eq(cohort) & frame["year"].eq(year)).to_numpy(dtype=bool)
        for cohort in ("old166", "first_external250", "second_development200")
        for year in (2024, 2025, 2026)
    }


def preliminary_score(mask: np.ndarray, target: np.ndarray, segments: dict[str, np.ndarray]) -> tuple | None:
    n = int(mask.sum())
    if n < 450:
        return None
    counts = []
    rates = []
    for segment in segments.values():
        selected = mask & segment
        count = int(selected.sum())
        if count < 50:
            return None
        correct = int(target[selected].sum())
        counts.append(count)
        rates.append(correct / count)
    if min(rates) < .53:
        return None
    correct_all = int(target[mask].sum())
    return (min(rates), wilson(correct_all, n), correct_all / n, min(counts), n)


def final_metrics(frame: pd.DataFrame, mask: np.ndarray, direction: str) -> dict:
    chosen = frame.loc[mask].copy()
    # At most one overlapping one-day prediction per stock. This is determined
    # from signal and planned horizon alone, never the return.
    keep = []
    ordered = chosen[["symbol", "entry_date", "exit_date", "trade_date"]].sort_values(
        ["symbol", "entry_date", "trade_date"]
    )
    for _, group in ordered.groupby("symbol", sort=False):
        previous_exit = pd.Timestamp.min
        for row in group.itertuples():
            if row.entry_date > previous_exit:
                keep.append(row.Index)
                previous_exit = row.exit_date
    chosen = chosen.loc[keep]
    n = len(chosen)
    wins = int(chosen[f"{direction}_correct"].sum())
    daily = chosen.groupby("entry_date")[f"{direction}_correct"].mean()
    per_symbol = chosen.groupby("symbol")[f"{direction}_correct"].agg(["count", "mean"])
    return {
        "predictions": n,
        "correct": wins,
        "accuracy": round(wins / n, 5) if n else 0,
        "wilson95_lower": round(wilson(wins, n), 5),
        "symbols": int(chosen["symbol"].nunique()),
        "entry_dates": int(chosen["entry_date"].nunique()),
        "top_symbol_share": round(float(chosen["symbol"].value_counts(normalize=True).iloc[0]), 5) if n else 0,
        "top_entry_date_share": round(float(chosen["entry_date"].value_counts(normalize=True).iloc[0]), 5) if n else 0,
        "daily_mean_accuracy": round(float(daily.mean()), 5) if n else 0,
        "median_symbol_accuracy": round(float(per_symbol["mean"].median()), 5) if n else 0,
        "unobservable_direction": int((~chosen["direction_observable"]).sum()),
        "entry_executable_long": int(chosen["entry_executable"].sum()),
        "blocked_long_exit": int(chosen["blocked_exit"].sum()),
    }


def evaluate(frame: pd.DataFrame, mask: np.ndarray, direction: str) -> dict:
    groups = {"all": np.ones(len(frame), dtype=bool)}
    groups.update(slices(frame))
    result = {name: final_metrics(frame, mask & segment, direction) for name, segment in groups.items()}
    result["annual"] = {
        str(year): final_metrics(frame, mask & frame["year"].eq(year).to_numpy(), direction)
        for year in (2024, 2025, 2026)
    }
    result["cohorts"] = {
        cohort: final_metrics(frame, mask & frame["cohort"].eq(cohort).to_numpy(), direction)
        for cohort in ("old166", "first_external250", "second_development200")
    }
    return result


def search(frame: pd.DataFrame) -> dict:
    stock = stock_conditions()
    regime = regime_conditions()
    stock_masks = [c.mask(frame) for c in stock]
    regime_masks = [c.mask(frame) for c in regime]
    segment = slices(frame)
    out = {}
    for direction in ("up", "down"):
        target = frame[f"{direction}_correct"].to_numpy(dtype=np.uint8)
        preliminary = []
        for regime_atom, rm in zip(regime, regime_masks):
            # A regime-only rule is also reviewed as a same-regime baseline.
            for first, fm in zip(stock, stock_masks):
                mask = rm & fm
                score = preliminary_score(mask, target, segment)
                if score is not None:
                    preliminary.append((score, (regime_atom, first), mask))
        preliminary.sort(key=lambda item: item[0], reverse=True)
        # Extend only the top 80 two-condition candidates with one distinct
        # stock field. This is a search heuristic, fully disclosed in output.
        expanded = preliminary[:80]
        for _, conditions, parent in preliminary[:80]:
            used = {item.field for item in conditions}
            for extra, extra_mask in zip(stock, stock_masks):
                if extra.field in used:
                    continue
                mask = parent & extra_mask
                score = preliminary_score(mask, target, segment)
                if score is not None:
                    expanded.append((score, conditions + (extra,), mask))
        expanded.sort(key=lambda item: item[0], reverse=True)
        result = []
        seen = set()
        for raw_score, conditions, mask in expanded[:100]:
            key = tuple(conditions)
            if key in seen:
                continue
            seen.add(key)
            detailed = evaluate(frame, mask, direction)
            all_metrics = detailed["all"]
            if all_metrics["predictions"] < 400 or all_metrics["entry_dates"] < 80 or all_metrics["symbols"] < 50:
                continue
            if any(
                detailed[f"{cohort}_{year}"]["predictions"] < 50
                or detailed[f"{cohort}_{year}"]["entry_dates"] < 20
                or detailed[f"{cohort}_{year}"]["symbols"] < 10
                or detailed[f"{cohort}_{year}"]["accuracy"] < .53
                for cohort in ("old166", "first_external250", "second_development200")
                for year in (2024, 2025, 2026)
            ):
                continue
            result.append({
                "direction": direction,
                "conditions": [asdict(c) for c in conditions],
                "condition_text": " AND ".join(c.name() for c in conditions),
                "preliminary_score": [round(float(x), 6) for x in raw_score],
                "metrics": detailed,
            })
        out[direction] = result
    return out


def main() -> None:
    frame = development_events()
    result = {
        "purpose": "development search only; three stock cohorts are used for discovery, sealed cohort is untouched",
        "signal_timing": "T close; direction of T+1 market open to T+2 market open",
        "denominator": "all T-close predictions in fixed normal universe; no future execution filter",
        "normal_universe": "mainboard_liquid_stable_v1",
        "fixed_market_reference": "HS300 sh000300; same pre-fetched file must be used for any later stock validation",
        "source_sha256": {"old166": hashlib.sha256(OLD.read_bytes()).hexdigest(),
                          "first_external250": hashlib.sha256(FIRST_EXTERNAL.read_bytes()).hexdigest(),
                          "second_development200": hashlib.sha256(SECOND_DEVELOPMENT.read_bytes()).hexdigest(),
                          "hs300": hashlib.sha256(HS300.read_bytes()).hexdigest()},
        "search_design": "8 fixed index regime atoms x stock atoms; require >=50 raw signals and >=53% accuracy in every 3-cohort x 3-year segment, expand top 80 pairs by a third distinct-field atom; final nonoverlap audit requires >=50 signals, 20 dates, 10 stocks and >=53% each segment",
        "input_events": len(frame),
        "input_symbols": int(frame["symbol"].nunique()),
        "baseline": {direction: evaluate(frame, np.ones(len(frame), dtype=bool), direction)
                     for direction in ("up", "down")},
    }
    result.update(search(frame))
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    for direction in ("up", "down"):
        print(direction, "candidates", len(result[direction]))
        for candidate in result[direction][:10]:
            m = candidate["metrics"]["all"]
            print(candidate["condition_text"], m["accuracy"], m["predictions"], m["wilson95_lower"])
    print("saved", OUTPUT)


if __name__ == "__main__":
    main()
