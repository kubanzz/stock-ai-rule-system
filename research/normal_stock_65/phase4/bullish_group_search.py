#!/usr/bin/env python3
"""Development-only interpretable bullish rule-group search.

Selection uses the 200-stock 2020–23 calibration slice. The three exposed
2024–26 cohorts are diagnostics, never a blind holdout. This module does not
load the sealed 100-stock file. Signal predicates use only information through
the T close; a strict T+1-open to T+2-open rise is the primary label.
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
from research.normal_stock_65.phase3 import rare_signal_search as rare  # noqa: E402
from research.normal_stock_65.phase3.consensus_group_search import metric  # noqa: E402
from research.one_day_20260930.quant_research import wilson  # noqa: E402

HERE = Path(__file__).resolve().parent
INDEX = HERE / "hs300_unadjusted_baostock.csv"
OUT = HERE / "bullish_group_results.json"
YEARS = (2024, 2025, 2026)


@dataclass(frozen=True)
class Atom:
    field: str
    op: str
    value: float | str
    family: str

    def mask(self, data: pd.DataFrame) -> np.ndarray:
        col = data[self.field]
        if self.op == "<=":
            return col.le(self.value).fillna(False).to_numpy(bool)
        if self.op == ">=":
            return col.ge(self.value).fillna(False).to_numpy(bool)
        return col.eq(self.value).fillna(False).to_numpy(bool)


def atoms() -> list[Atom]:
    # Broad round thresholds chosen before checking the exposed later slices.
    grid = {
        "rsi14": ("momentum", [20, 25, 30, 35, 40], [60, 70, 75]),
        "return_1d": ("momentum", [-.05, -.03, -.02, -.01], [.01, .02, .03]),
        "change_pct_5d": ("momentum", [-.12, -.10, -.07, -.05], [.03, .05, .07]),
        "return_20d": ("momentum", [-.15, -.10, -.05], [.05, .10, .15]),
        "open_gap": ("candle", [-.03, -.02, -.01], [.01, .02, .03]),
        "intraday_return": ("candle", [-.03, -.02, -.01], [.01, .02, .03]),
        "close_position": ("candle", [.10, .20, .30], [.70, .80, .90]),
        "lower_shadow": ("candle", [.10, .20], [.50, .70]),
        "upper_shadow": ("candle", [.10, .20], [.50, .70]),
        "volume_ratio_5d": ("volume", [.5, .7], [1.3, 1.5, 2.]),
        "amount_ratio20": ("volume", [.5, .7], [1.3, 1.5, 2.]),
        "normal_volatility_20d": ("volatility", [.015, .02, .025], [.03, .035]),
        "index_return_1d": ("market", [-.02, -.01], [.01, .02]),
        "index_return_5d": ("market", [-.05, -.03], [.03, .05]),
        "index_vs_ma20": ("market", [-.05, -.02], [.02, .05]),
        "index_close_position": ("market", [.10, .20], [.80, .90]),
    }
    out = [Atom(field, op, value, family)
           for field, (family, lows, highs) in grid.items()
           for op, values in (("<=", lows), (">=", highs)) for value in values]
    out += [Atom("exchange", "=", x, "exchange") for x in ("SH", "SZ")]
    return out


def prepare() -> pd.DataFrame:
    # Reuse identical event/normal-stock execution as earlier work, but with
    # a fixed market index spanning the full 2020–23 calibration period.
    rare.INDEX = INDEX
    data = rare.prepare(True)
    return data.loc[data["year"].between(2020, 2026)].reset_index(drop=True)


def score_fit(mask: np.ndarray, y: np.ndarray, year: np.ndarray, dates: np.ndarray,
              symbols: np.ndarray, min_n: int) -> tuple[float, int, float] | None:
    n = int(mask.sum())
    if n < min_n:
        return None
    date_counts = np.bincount(dates[mask])
    if int(np.count_nonzero(date_counts)) < 40 or int(np.count_nonzero(np.bincount(symbols[mask]))) < 25:
        return None
    year_counts = np.bincount(year[mask] - 2020, minlength=4)
    if int((year_counts >= 20).sum()) < 3:
        return None
    if int(date_counts.max()) / n > .15:
        return None
    wins = int(y[mask].sum())
    year_correct = np.bincount(year[mask] - 2020, weights=y[mask], minlength=4)
    year_rate = year_correct / np.maximum(year_counts, 1)
    # Stable train years matter, while Wilson limits ranking by tiny samples.
    minimum_year = float(year_rate[year_counts >= 20].min())
    lower = float(wilson(wins, n))
    selection_score = min(lower, .5 * lower + .5 * minimum_year)
    return selection_score, n, minimum_year


def signal_mask(matrix: np.ndarray, indices: tuple[int, ...], minimum: int) -> np.ndarray:
    if minimum == len(indices):
        return np.logical_and.reduce(matrix[:, indices], axis=1)
    return matrix[:, indices].sum(axis=1) >= minimum


def select_groups(fit: pd.DataFrame, rules: list[Atom], matrix: np.ndarray) -> dict:
    y = fit["up_correct"].to_numpy(np.uint8)
    years = fit["year"].to_numpy(np.int16)
    dates = pd.factorize(fit["entry_date"], sort=False)[0]
    symbols = pd.factorize(fit["symbol"], sort=False)[0]
    ranked: dict[str, list] = {"single": [], "and": [], "vote": []}
    examined = {key: 0 for key in ranked}

    def offer(kind: str, idx: tuple[int, ...], minimum: int, min_n: int) -> None:
        examined[kind] += 1
        mask = signal_mask(matrix, idx, minimum)
        scored = score_fit(mask, y, years, dates, symbols, min_n)
        if scored:
            ranked[kind].append((*scored, idx, minimum))

    for i in range(len(rules)):
        offer("single", (i,), 1, 200)
    for i, j in itertools.combinations(range(len(rules)), 2):
        if rules[i].field == rules[j].field or rules[i].family == rules[j].family:
            continue
        offer("and", (i, j), 2, 160)
    ranked["single"].sort(reverse=True)
    ranked["and"].sort(reverse=True)
    # Three- and four-member conjunctions extend fit-ranked pairs; no later
    # cohort outcome participates in the beam.
    beam = ranked["and"][:50]
    for depth, min_n, width in ((3, 130, 35), (4, 100, 20)):
        extended = {}
        for _, _, _, idx, _ in beam:
            used = {rules[i].family for i in idx}
            for j in range(len(rules)):
                if rules[j].family in used:
                    continue
                idx2 = tuple(sorted((*idx, j)))
                if idx2 in extended:
                    continue
                examined["and"] += 1
                mask = signal_mask(matrix, idx2, depth)
                scored = score_fit(mask, y, years, dates, symbols, min_n)
                if scored:
                    extended[idx2] = (*scored, idx2, depth)
        beam = sorted(extended.values(), reverse=True)[:width]
        ranked["and"].extend(beam)
    # Count-based voting maps directly to the current backend's WEIGHTED
    # minMatchedRules semantics. Choose member pool from fit-ranked atoms.
    top = [entry[3][0] for entry in ranked["single"][:30]]
    for size, minimum in ((3, 2), (4, 3)):
        for idx in itertools.combinations(top, size):
            if len({rules[i].field for i in idx}) != size or len({rules[i].family for i in idx}) != size:
                continue
            offer("vote", tuple(sorted(idx)), minimum, 200)
    for kind in ranked:
        ranked[kind].sort(reverse=True)
    return {"ranked": ranked, "examined": examined}


def diagnose(data: pd.DataFrame, indices: tuple[int, ...], minimum: int,
             rules: list[Atom], fit_score: float, fit_n: int, fit_min_year: float) -> dict:
    hits = sum(rules[i].mask(data).astype(np.uint8) for i in indices) >= minimum
    selected = data.loc[hits]
    all_years = {}
    for year in YEARS:
        scope = data.loc[data["year"].eq(year)]
        all_years[str(year)] = metric(selected.loc[selected["year"].eq(year)], "up", len(scope))
    segments = {}
    for cohort in rare.SOURCES:
        for year in YEARS:
            scoped = data.loc[data["cohort"].eq(cohort) & data["year"].eq(year)]
            selected_scoped = selected.loc[selected["cohort"].eq(cohort) & selected["year"].eq(year)]
            segments[f"{cohort}_{year}"] = metric(selected_scoped, "up", len(scoped))
    pooled = metric(selected, "up", len(data))
    return {"members": [asdict(rules[i]) for i in indices], "min_matched_rules": minimum,
            "group_kind": "AND" if minimum == len(indices) else "WEIGHTED",
            "fit_score": round(fit_score, 5), "fit_raw_predictions": fit_n,
            "fit_min_year_accuracy": round(fit_min_year, 5),
            "development_2024_26": pooled, "years": all_years, "segments": segments,
            "supported_every_year": all(all_years[str(y)]["predictions_nonoverlap"] >= 100
                                        and all_years[str(y)]["entry_dates"] >= 30
                                        and all_years[str(y)]["symbols"] >= 20 for y in YEARS),
            "each_year_at_least_65": all(all_years[str(y)]["direction_accuracy"] is not None
                                         and all_years[str(y)]["direction_accuracy"] >= .65 for y in YEARS)}


def main() -> None:
    data = prepare()
    rules = atoms()
    fit = data.loc[data["cohort"].eq("development200") & data["year"].between(2020, 2023)].copy()
    diag = data.loc[data["year"].isin(YEARS)].copy()
    matrix = np.column_stack([rule.mask(fit) for rule in rules])
    chosen = select_groups(fit, rules, matrix)
    output = {"purpose": "development-only bullish strict gross direction accuracy; sealed100 never read",
              "normal_universe": "mainboard_liquid_stable_v1 and risk_status=normal",
              "execution": "T close signal; T+1 market open to T+2 market open; missing direction counts wrong",
              "train": "phase2 development200, 2020-23", "diagnostic": "three exposed cohorts, 2024-26",
              "source_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest()
                                for name, path in rare.SOURCES.items()},
              "index_sha256": hashlib.sha256(INDEX.read_bytes()).hexdigest(),
              "eligible_rows": {"fit": len(fit), "diagnostic": len(diag)},
              "atom_count": len(rules), "examined": chosen["examined"], "results": {}}
    for kind, ranked in chosen["ranked"].items():
        # A fixed train-ranked shortlist. Exposed diagnostic outcomes do not
        # pick replacement members or alter group conditions.
        output["results"][kind] = [diagnose(diag, indices, minimum, rules, score, count, min_year)
                                    for score, count, min_year, indices, minimum in ranked[:20]]
    OUT.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for kind, reports in output["results"].items():
        print(kind, "examined", chosen["examined"][kind], "ranked", len(chosen["ranked"][kind]))
        for item in reports[:5]:
            pooled = item["development_2024_26"]
            print(item["fit_score"], pooled["direction_accuracy"], pooled["predictions_nonoverlap"],
                  [item["years"][str(y)]["direction_accuracy"] for y in YEARS],
                  [(m["field"], m["op"], m["value"]) for m in item["members"]])
    print("wrote", OUT)


if __name__ == "__main__":
    main()
