#!/usr/bin/env python3
"""Reproducible development search for one-day directions on normal A shares.

All predicates use data known by signal-day close. 2026 in the original CSV is
development data already inspected in prior research, never an untouched holdout.
The output is a shortlist for verification on separately collected symbols.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.normal_universe import (  # noqa: E402
    NORMAL_UNIVERSE_ID,
    add_normal_universe_eligibility,
)
from research.one_day_20260930.quant_research import (  # noqa: E402
    COST,
    calculate_factors,
    load_quotes,
    wilson,
)


@dataclass(frozen=True)
class Atom:
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


def add_features(quotes: pd.DataFrame) -> pd.DataFrame:
    frame = calculate_factors(quotes)
    by = frame.groupby("symbol", sort=False)
    close = frame["close_price"]
    frame["return_1d"] = close / by["close_price"].shift(1) - 1
    for n in (2, 3, 10, 20):
        frame[f"return_{n}d"] = close / by["close_price"].shift(n) - 1
    frame["intraday_return"] = close / frame["open_price"] - 1
    frame["open_gap"] = frame["open_price"] / by["close_price"].shift(1) - 1
    frame["close_position"] = (close - frame["low_price"]) / (
        frame["high_price"] - frame["low_price"]
    ).replace(0, np.nan)
    frame["range_pct"] = (frame["high_price"] - frame["low_price"]) / close
    frame["distance_ma5"] = close / frame["ma5"] - 1
    frame["distance_ma20"] = close / frame["ma20"] - 1
    frame["amount_ratio20"] = frame["amount"] / (
        by["amount"].transform(lambda x: x.shift(1).rolling(20).median())
    )
    frame["exchange"] = frame["symbol"].str[-2:]
    flagged = add_normal_universe_eligibility(quotes)[
        ["symbol", "trade_date", "normal_universe_eligible", "normal_volatility_20d"]
    ]
    return frame.merge(flagged, on=["symbol", "trade_date"], how="left", validate="one_to_one")


def event_frame(csv: Path) -> pd.DataFrame:
    quotes = load_quotes(str(csv))
    events = add_features(quotes)
    # Signal eligibility is fixed at T close. Neither the next open nor the
    # intended exit can remove a prediction from the directional denominator.
    counts = quotes.groupby("trade_date")["symbol"].nunique()
    calendar = counts.loc[counts.ge(max(5, math.ceil(counts.max() * .5)))].index.sort_values()
    date_index = {date: index for index, date in enumerate(calendar)}
    events["calendar_index"] = events["trade_date"].map(date_index)
    next_open = {index: calendar[index + 1] for index in range(len(calendar) - 1)}
    next_exit = {index: calendar[index + 2] for index in range(len(calendar) - 2)}
    events["entry_date"] = events["calendar_index"].map(next_open)
    events["exit_date"] = events["calendar_index"].map(next_exit)
    events = events.loc[
        events["normal_universe_eligible"]
        & events["risk_status"].eq("normal")
        & events["entry_date"].notna()
        & events["exit_date"].notna()
    ].copy()
    bars = quotes[["symbol", "trade_date", "open_price", "close_price", "volume", "amount"]]
    events = events.merge(
        bars.rename(columns={
            "trade_date": "entry_date", "open_price": "entry_open",
            "close_price": "entry_close", "volume": "entry_volume", "amount": "entry_amount",
        }), on=["symbol", "entry_date"], how="left", validate="many_to_one",
    )
    events = events.merge(
        bars.rename(columns={
            "trade_date": "exit_date", "open_price": "exit_open",
            "close_price": "exit_close", "volume": "exit_volume", "amount": "exit_amount",
        }), on=["symbol", "exit_date"], how="left", validate="many_to_one",
    )
    events["entry_price_known"] = events["entry_open"].gt(0) & events["entry_volume"].gt(0)
    events["exit_price_known"] = events["exit_open"].gt(0) & events["exit_volume"].gt(0)
    events["direction_observable"] = events["entry_price_known"] & events["exit_price_known"]
    events["limit_fraction"] = .10  # normal-universe v1 contains only mainboard symbols
    events["entry_gap"] = events["entry_open"] / events["close_price"] - 1
    events["exit_gap"] = events["exit_open"] / events["entry_close"] - 1
    events["entry_executable"] = events["entry_price_known"] & events["entry_gap"].lt(.095)
    events["blocked_exit"] = ~events["exit_price_known"] | events["exit_gap"].le(-.095)
    events["gross_return"] = events["exit_open"] / events["entry_open"] - 1
    events["up_correct"] = events["gross_return"].gt(0) & events["direction_observable"]
    events["down_correct"] = events["gross_return"].lt(0) & events["direction_observable"]
    # A down signal means avoid a long, not a claim of short-side net profit.
    events["net_return"] = events["gross_return"] - COST
    events.loc[events["blocked_exit"], "net_return"] = -.10 - COST
    events["would_lose_long_net"] = events["net_return"].lt(0) | events["blocked_exit"]
    return events.reset_index(drop=True)


def atoms() -> list[Atom]:
    result: list[Atom] = []
    grids: dict[str, tuple[list[float], list[float]]] = {
        "rsi14": ([25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75], [30, 35, 40, 45, 50, 55, 60, 65, 70, 75]),
        "return_1d": ([-.05, -.04, -.03, -.02, -.01, 0, .01, .02, .03, .04], [-.04, -.03, -.02, -.01, 0, .01, .02, .03, .04, .05]),
        "return_2d": ([-.07, -.05, -.03, -.01, 0, .01, .03, .05], [-.05, -.03, -.01, 0, .01, .03, .05, .07]),
        "change_pct_5d": ([-.10, -.07, -.05, -.03, -.01, 0, .03, .05, .07, .10], [-.07, -.05, -.03, 0, .03, .05, .07, .10]),
        "return_10d": ([-.15, -.10, -.07, -.05, -.03, 0, .03, .05, .07, .10], [-.10, -.07, -.05, -.03, 0, .03, .05, .07, .10, .15]),
        "return_20d": ([-.20, -.15, -.10, -.05, 0, .05, .10], [-.10, -.05, 0, .05, .10, .15, .20]),
        "intraday_return": ([-.05, -.04, -.03, -.02, -.01, 0, .01, .02, .03], [-.03, -.02, -.01, 0, .01, .02, .03, .04, .05]),
        "open_gap": ([-.04, -.03, -.02, -.01, 0, .01, .02], [-.02, -.01, 0, .01, .02, .03, .04]),
        "close_position": ([.1, .2, .3, .4, .5, .6, .7, .8], [.2, .3, .4, .5, .6, .7, .8, .9]),
        "range_pct": ([.02, .03, .04, .05, .06], [.02, .03, .04, .05, .06]),
        "distance_ma5": ([-.05, -.03, -.01, 0, .01, .03], [-.03, -.01, 0, .01, .03, .05]),
        "distance_ma20": ([-.10, -.05, -.03, 0, .03, .05, .10], [-.05, -.03, 0, .03, .05, .10]),
        "volume_ratio_5d": ([.5, .7, 1.0, 1.3], [.7, 1.0, 1.3, 1.5]),
        "amount_ratio20": ([.5, .7, 1.0, 1.3], [.7, 1.0, 1.3, 1.5]),
        "normal_volatility_20d": ([.015, .02, .025, .03], [.015, .02, .025, .03]),
    }
    for field, (low, high) in grids.items():
        result.extend(Atom(field, "<=", value) for value in low)
        result.extend(Atom(field, ">=", value) for value in high)
    for field, values in {
        "exchange": ["SH", "SZ"],
        "short_term_trend": ["up", "strong_up", "down", "strong_down", "sideways"],
        "technical_status": ["bullish", "bearish", "neutral"],
        "volume_status": ["low", "normal", "high"],
    }.items():
        result.extend(Atom(field, "=", value) for value in values)
    return result


def split_events(events: pd.DataFrame) -> dict[str, pd.DataFrame]:
    return {
        "train_2024": events.loc[events["exit_date"].le("2024-12-31")].copy(),
        "validate_2025": events.loc[events["trade_date"].gt("2024-12-31") & events["exit_date"].le("2025-12-31")].copy(),
        "develop_2026": events.loc[events["trade_date"].gt("2025-12-31")].copy(),
    }


def metrics(frame: pd.DataFrame, direction: str) -> dict:
    chosen = frame
    n = len(chosen)
    correct = chosen[f"{direction}_correct"]
    count = int(correct.sum()) if n else 0
    daily = chosen.groupby("entry_date")[f"{direction}_correct"].mean() if n else pd.Series(dtype=float)
    executable = chosen.loc[chosen["entry_executable"]]
    return {
        "events": n,
        "correct": count,
        "gross_direction_accuracy": round(count / n, 5) if n else 0,
        "wilson95_lower": round(wilson(count, n), 5),
        "symbols": int(chosen["symbol"].nunique()),
        "entry_dates": int(chosen["entry_date"].nunique()),
        "blocked_exits": int(chosen["blocked_exit"].sum()),
        "unobservable_direction": int((~chosen["direction_observable"]).sum()),
        "long_executable_entry_events": len(executable),
        "long_executable_net_win_rate": round(float(executable["net_return"].gt(0).mean()), 5) if len(executable) else 0,
        "long_executable_mean_net_return": round(float(executable["net_return"].mean()), 6) if len(executable) else 0,
        "mean_daily_accuracy": round(float(daily.mean()), 5) if n else 0,
        "top_symbol_share": round(float(chosen["symbol"].value_counts(normalize=True).iloc[0]), 5) if n else 0,
        "mean_long_net_return": round(float(chosen["net_return"].mean()), 6) if n else 0,
        "long_net_win_rate": round(float(chosen["net_return"].gt(0).mean()), 5) if n else 0,
        "long_net_loss_rate": round(float(chosen["would_lose_long_net"].mean()), 5) if n else 0,
    }


def mask_rule(frame: pd.DataFrame, rule: tuple[Atom, ...]) -> np.ndarray:
    selected = np.ones(len(frame), dtype=bool)
    for atom in rule:
        selected &= atom.mask(frame)
    return selected


def candidate_rules(atom_list: list[Atom]) -> list[tuple[Atom, ...]]:
    rules = [(a,) for a in atom_list]
    for i, a in enumerate(atom_list):
        for b in atom_list[i + 1:]:
            if a.field != b.field:
                rules.append((a, b))
    return rules


def rank(split: dict[str, pd.DataFrame], direction: str, rules: list[tuple[Atom, ...]], top: int) -> list[dict]:
    train = split["train_2024"]
    valid = split["validate_2025"]
    atom_list = atoms()
    matrices = {
        "train": np.vstack([atom.mask(train) for atom in atom_list]),
        "valid": np.vstack([atom.mask(valid) for atom in atom_list]),
    }
    indices = {atom: i for i, atom in enumerate(atom_list)}
    target_train = train[f"{direction}_correct"].to_numpy(dtype=np.uint8)
    target_valid = valid[f"{direction}_correct"].to_numpy(dtype=np.uint8)
    train_dates = train["entry_date"].to_numpy(dtype="datetime64[D]")
    valid_dates = valid["entry_date"].to_numpy(dtype="datetime64[D]")
    train_symbols = train["symbol"].to_numpy()
    valid_symbols = valid["symbol"].to_numpy()
    preliminary = []
    for rule in rules:
        idx = [indices[a] for a in rule]
        mt = matrices["train"][idx[0]].copy()
        mv = matrices["valid"][idx[0]].copy()
        if len(idx) > 1:
            mt &= matrices["train"][idx[1]]
            mv &= matrices["valid"][idx[1]]
        nt, nv = int(mt.sum()), int(mv.sum())
        if nt < 100 or nv < 60:
            continue
        wt, wv = int(target_train[mt].sum()), int(target_valid[mv].sum())
        dt, dv = np.unique(train_dates[mt]).size, np.unique(valid_dates[mv]).size
        st, sv = np.unique(train_symbols[mt]).size, np.unique(valid_symbols[mv]).size
        if dt < 30 or dv < 25 or st < 15 or sv < 12:
            continue
        at, av = wt / nt, wv / nv
        # A candidate must have a meaningful training edge and retain it in 2025.
        if at < .55 or av < .58:
            continue
        preliminary.append((min(wilson(wt, nt), wilson(wv, nv)), av, at, nt, nv, rule))
    preliminary.sort(key=lambda item: (item[0], item[1], item[2], item[3]), reverse=True)
    evaluated = []
    for _, _, _, _, _, rule in preliminary[:top]:
        results = {name: metrics(data.loc[mask_rule(data, rule)], direction) for name, data in split.items()}
        dev = results["develop_2026"]
        if min(results["train_2024"]["events"], results["validate_2025"]["events"]) < 50:
            continue
        evaluated.append({
            "direction": direction,
            "conditions": [asdict(atom) for atom in rule],
            "condition_text": " AND ".join(atom.name() for atom in rule),
            "metrics": results,
            "meets_2026_65_observed": dev["events"] >= 100 and dev["entry_dates"] >= 30
            and dev["symbols"] >= 12 and dev["gross_direction_accuracy"] >= .65,
            "meets_2026_65_wilson": dev["events"] >= 100 and dev["entry_dates"] >= 30
            and dev["symbols"] >= 12 and dev["wilson95_lower"] >= .65,
        })
    evaluated.sort(key=lambda x: (
        x["meets_2026_65_wilson"], x["meets_2026_65_observed"],
        x["metrics"]["develop_2026"]["wilson95_lower"],
        x["metrics"]["validate_2025"]["wilson95_lower"],
        x["metrics"]["develop_2026"]["events"],
    ), reverse=True)
    return evaluated


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, default=ROOT / "research/one_day_20260930/ashare_daily_qfq.csv")
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("search_results.json"))
    parser.add_argument("--top", type=int, default=150)
    args = parser.parse_args()
    events = event_frame(args.csv)
    split = split_events(events)
    atom_list = atoms()
    rules = candidate_rules(atom_list)
    out = {
        "source_csv": str(args.csv),
        "source_sha256": hashlib.sha256(args.csv.read_bytes()).hexdigest(),
        "universe": NORMAL_UNIVERSE_ID,
        "execution": "T close signal; T+1 open entry; T+2 open exit; 30 bps round trip cost for hypothetical long",
        "bearish_interpretation": "avoid a long; no short trade or short PnL is assumed",
        "split": "2024 training after 252-bar warmup, 2025 validation, 2026 previously inspected development; external symbol sample required for independent proof",
        "candidate_count": len(rules),
        "atomic_condition_count": len(atom_list),
        "baseline": {direction: {name: metrics(data, direction) for name, data in split.items()}
                     for direction in ("up", "down")},
        "up": rank(split, "up", rules, args.top),
        "down": rank(split, "down", rules, args.top),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
    for direction in ("up", "down"):
        print(direction, "shortlist", len(out[direction]))
        for item in out[direction][:10]:
            m = item["metrics"]
            print(item["condition_text"], "|", *(f"{s}:{m[s]['gross_direction_accuracy']:.3f}/{m[s]['events']}" for s in m))
    print("wrote", args.output)


if __name__ == "__main__":
    main()
