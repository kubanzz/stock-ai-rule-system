#!/usr/bin/env python3
"""Development-only search for one-day downside warning groups.

Train selection uses only the 200-stock 2020-23 slice. The 2024-26 slices
of three previously exposed cohorts are diagnostics, never a sealed test.
Every predicate is known at signal-day close. A down signal means avoiding
a long position; it is not a short-sale profit claim.
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
from research.normal_stock_65.phase3.consensus_group_search import nonoverlap  # noqa: E402
from research.one_day_20260930.quant_research import wilson  # noqa: E402

HERE = Path(__file__).resolve().parent
INDEX = HERE / "hs300_unadjusted_baostock.csv"
SOURCES = {
    "old166": ROOT / "research/one_day_20260930/ashare_daily_qfq.csv",
    "first250": ROOT / "research/normal_stock_65/external_mainboard_qfq.csv",
    "phase2dev200": ROOT / "research/normal_stock_65/phase2/development_qfq.csv",
}
YEARS = (2024, 2025, 2026)


@dataclass(frozen=True)
class Atom:
    field: str
    op: str
    value: float | str
    family: str

    def mask(self, frame: pd.DataFrame) -> np.ndarray:
        col = frame[self.field]
        if self.op == "<=":
            return col.le(self.value).fillna(False).to_numpy(bool)
        if self.op == ">=":
            return col.ge(self.value).fillna(False).to_numpy(bool)
        return col.eq(self.value).fillna(False).to_numpy(bool)


def atoms() -> list[Atom]:
    specs = {
        "rsi14": ("momentum", [], [65, 70, 75, 80]),
        "return_1d": ("momentum", [-.03, -.02], [.01, .02, .03]),
        "change_pct_5d": ("momentum", [-.07], [.03, .05, .07, .10]),
        "return_20d": ("momentum", [-.10], [.05, .10, .15]),
        "open_gap": ("candle", [-.02], [.01, .02]),
        "intraday_return": ("candle", [-.03, -.02], [.01, .02, .03]),
        "close_position": ("candle", [.2], [.7, .8, .9]),
        "upper_shadow": ("candle", [], [.5, .7]),
        "volume_ratio_5d": ("volume", [.7], [1.3, 1.5, 2]),
        "amount_ratio20": ("volume", [.7], [1.3, 1.5, 2]),
        "normal_volatility_20d": ("volatility", [.02, .025], [.03, .035]),
        "index_return_1d": ("market", [-.02, -.01], [.01, .02]),
        "index_return_5d": ("market", [-.05, -.03], [.03, .05]),
        "index_distance_ma20": ("market", [-.04, -.02], [.02, .04]),
        "index_close_position": ("market", [.2], [.8, .9]),
        "index_volatility_20d": ("market", [.01], [.015, .02]),
        "amount": ("liquidity", [100_000_000], [300_000_000, 500_000_000]),
    }
    out = [Atom(f, op, value, family)
           for f, (family, lows, highs) in specs.items()
           for op, values in (("<=", lows), (">=", highs)) for value in values]
    out += [Atom("exchange", "=", exchange, "exchange") for exchange in ("SH", "SZ")]
    return out


def index_features() -> pd.DataFrame:
    ix = pd.read_csv(INDEX, parse_dates=["trade_date"]).sort_values("trade_date")
    close = ix["close"]
    for window in (1, 5):
        ix[f"index_return_{window}d"] = close / close.shift(window) - 1
    ix["index_distance_ma20"] = close / close.rolling(20).mean() - 1
    ix["index_close_position"] = (close - ix["low"]) / (ix["high"] - ix["low"]).replace(0, np.nan)
    ix["index_volatility_20d"] = close.pct_change().shift(1).rolling(20).std()
    return ix[["trade_date", "index_return_1d", "index_return_5d", "index_distance_ma20",
               "index_close_position", "index_volatility_20d"]]


def prepare() -> pd.DataFrame:
    index = index_features()
    frames = []
    for cohort, path in SOURCES.items():
        frame = event_frame(path)
        frame["cohort"] = cohort
        day_range = (frame["high_price"] - frame["low_price"]).replace(0, np.nan)
        frame["upper_shadow"] = (frame["high_price"] - frame[["open_price", "close_price"]].max(axis=1)) / day_range
        frames.append(frame.merge(index, on="trade_date", how="left", validate="many_to_one"))
    out = pd.concat(frames, ignore_index=True)
    out["year"] = out["trade_date"].dt.year
    out = out.loc[out["year"].between(2020, 2026)
                  & out["year"].eq(out["exit_date"].dt.year)].reset_index(drop=True)
    return out


def metric(frame: pd.DataFrame, eligible: int) -> dict:
    selected = nonoverlap(frame)
    n = len(selected)
    wins = int(selected["down_correct"].sum())
    return {
        "raw_signals": len(frame), "predictions": n, "correct": wins,
        "accuracy": round(wins / n, 5) if n else None,
        "wilson95_lower": round(wilson(wins, n), 5) if n else None,
        "coverage": round(len(frame) / eligible, 6) if eligible else None,
        "symbols": int(selected["symbol"].nunique()),
        "entry_dates": int(selected["entry_date"].nunique()),
        "top_date_share": round(float(selected["entry_date"].value_counts(normalize=True).iloc[0]), 5) if n else None,
        "top_symbol_share": round(float(selected["symbol"].value_counts(normalize=True).iloc[0]), 5) if n else None,
        "unobservable_counted_wrong": int((~selected["direction_observable"]).sum()),
    }


def calibration_score(mask: np.ndarray, correct: np.ndarray, dates: np.ndarray,
                      symbols: np.ndarray, min_n: int) -> tuple[float, int] | None:
    n = int(mask.sum())
    if n < min_n or len(np.unique(dates[mask])) < 40 or len(np.unique(symbols[mask])) < 20:
        return None
    wins = int(correct[mask].sum())
    date_counts = pd.Series(dates[mask]).value_counts()
    if int(date_counts.iloc[0]) / n > .15:
        return None
    return wilson(wins, n), n


def search(data: pd.DataFrame, rules: list[Atom]) -> dict:
    fit = data.loc[data["cohort"].eq("phase2dev200") & data["year"].between(2020, 2023)]
    dev = data.loc[data["year"].isin(YEARS)]
    matrix = np.column_stack([a.mask(fit) for a in rules])
    y = fit["down_correct"].to_numpy(np.uint8)
    dates = fit["entry_date"].to_numpy()
    symbols = fit["symbol"].to_numpy()
    atom_rank = []
    for i in range(len(rules)):
        s = calibration_score(matrix[:, i], y, dates, symbols, 200)
        if s:
            atom_rank.append((*s, (i,)))
    atom_rank.sort(reverse=True)
    # AND group: add only distinct factor families. Candidate ranking is
    # entirely before 2024; later cohorts cannot influence group selection.
    pair = []
    for i, j in itertools.combinations(range(len(rules)), 2):
        if rules[i].family == rules[j].family:
            continue
        s = calibration_score(matrix[:, i] & matrix[:, j], y, dates, symbols, 180)
        if s:
            pair.append((*s, (i, j)))
    pair.sort(reverse=True)
    all_and = atom_rank + pair
    beam = pair[:60]
    for depth, minimum, keep in ((3, 140, 40), (4, 100, 25)):
        extended = {}
        for _, _, indices in beam:
            used = {rules[i].family for i in indices}
            base = np.logical_and.reduce(matrix[:, list(indices)], axis=1)
            for j in range(len(rules)):
                if rules[j].family in used:
                    continue
                new_indices = tuple(sorted((*indices, j)))
                if new_indices in extended:
                    continue
                s = calibration_score(base & matrix[:, j], y, dates, symbols, minimum)
                if s:
                    extended[new_indices] = (*s, new_indices)
        beam = sorted(extended.values(), reverse=True)[:keep]
        all_and += beam
    # Count-based groups map to the backend WEIGHTED/minMatchedRules behavior.
    voting = []
    top_atoms = [rank[-1][0] for rank in atom_rank[:20]]
    for size, minimum in ((3, 2), (4, 3)):
        for indices in itertools.combinations(top_atoms, size):
            if len({rules[i].family for i in indices}) != size:
                continue
            mask = matrix[:, list(indices)].sum(axis=1) >= minimum
            s = calibration_score(mask, y, dates, symbols, 200)
            if s:
                voting.append((*s, tuple(sorted(indices)), minimum))
    voting.sort(reverse=True)

    def diagnose(ranked: list, kind: str) -> list[dict]:
        results = []
        # Top choices are fixed using only the calibration sample.
        for entry in ranked[:12]:
            score, raw_n, indices = entry[:3]
            minimum = entry[3] if kind == "WEIGHTED" else len(indices)
            hits = np.column_stack([rules[i].mask(dev) for i in indices]).sum(axis=1) >= minimum
            selected = dev.loc[hits]
            segments = {}
            for cohort in SOURCES:
                for year in YEARS:
                    scope = dev.loc[dev["cohort"].eq(cohort) & dev["year"].eq(year)]
                    chosen = selected.loc[selected["cohort"].eq(cohort) & selected["year"].eq(year)]
                    segments[f"{cohort}_{year}"] = metric(chosen, len(scope))
            pooled = metric(selected, len(dev))
            supported = all(s["predictions"] >= 25 and s["symbols"] >= 8 and s["entry_dates"] >= 10
                            for s in segments.values())
            results.append({"kind": kind, "min_matched_rules": minimum,
                            "members": [asdict(rules[i]) for i in indices],
                            "fit_2020_23_raw": raw_n,
                            "fit_2020_23_wilson95_lower": round(score, 5),
                            "diagnostic_2024_26": pooled, "segments": segments,
                            "all_nine_at_least_65": bool(supported and all(s["accuracy"] >= .65
                                                                            for s in segments.values()))})
        return results

    return {"fit_rows": len(fit), "fit_symbols": int(fit["symbol"].nunique()),
            "tested_and_pairs": len(pair), "tested_vote_supported": len(voting),
            "and_top12": diagnose(sorted(all_and, reverse=True), "AND"),
            "weighted_top12": diagnose(voting, "WEIGHTED")}


def main() -> None:
    data = prepare()
    output = {"scope": "development diagnostics only; sealed100 never read",
              "signal": "T close to T+1 open to T+2 open; strict downside direction",
              "down_signal_semantics": "avoid long exposure, not short profit",
              "source_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in SOURCES.items()},
              "index_sha256": hashlib.sha256(INDEX.read_bytes()).hexdigest(),
              "baseline": {f"{c}_{y}": metric(data.loc[data["cohort"].eq(c) & data["year"].eq(y)],
                                             len(data.loc[data["cohort"].eq(c) & data["year"].eq(y)]))
                           for c in SOURCES for y in YEARS}}
    output.update(search(data, atoms()))
    path = HERE / "short_direction_group_results.json"
    path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    for kind in ("and_top12", "weighted_top12"):
        print(kind)
        for item in output[kind][:5]:
            print(item["fit_2020_23_wilson95_lower"], item["diagnostic_2024_26"],
                  min((s["accuracy"] or 0) for s in item["segments"].values()),
                  [(m["field"], m["op"], m["value"]) for m in item["members"]])
    print("wrote", path)


if __name__ == "__main__":
    main()
