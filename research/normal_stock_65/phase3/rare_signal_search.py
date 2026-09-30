#!/usr/bin/env python3
"""Development-only search for rare, interpretable one-day direction rules.

The second-round sealed stock cohort is deliberately absent from every input.
All predicates are observed by the signal day's close. The 200-stock 2020-23
period selects candidates; 2024-26 in three already exposed stock cohorts is
diagnostic development evidence, not an independent test.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.search_directional_rules import add_features, event_frame  # noqa: E402
from research.one_day_20260930.quant_research import load_quotes, wilson  # noqa: E402

HERE = Path(__file__).resolve().parent
SOURCES = {
    "old166": ROOT / "research/one_day_20260930/ashare_daily_qfq.csv",
    "external250": ROOT / "research/normal_stock_65/external_mainboard_qfq.csv",
    "development200": ROOT / "research/normal_stock_65/phase2/development_qfq.csv",
}
INDEX = ROOT / "research/normal_stock_65/phase2/hs300_daily.csv"
COST = .003


@dataclass(frozen=True)
class Atom:
    field: str
    op: str
    value: float | int | str

    def mask(self, data: pd.DataFrame) -> np.ndarray:
        x = data[self.field]
        if self.op == "<=":
            return x.le(self.value).fillna(False).to_numpy(dtype=bool)
        if self.op == ">=":
            return x.ge(self.value).fillna(False).to_numpy(dtype=bool)
        return x.eq(self.value).fillna(False).to_numpy(dtype=bool)

    def name(self) -> str:
        return f"{self.field} {self.op} {self.value}"

    def json(self) -> dict:
        return {"field": self.field, "op": self.op, "value": self.value}


def expanded_events(csv: Path) -> pd.DataFrame:
    """Same market-calendar execution as event_frame, without its risk-status gate."""
    quotes = load_quotes(str(csv))
    events = add_features(quotes)
    counts = quotes.groupby("trade_date")["symbol"].nunique()
    calendar = counts.loc[counts.ge(max(5, math.ceil(counts.max() * .5)))].index.sort_values()
    mapping = {d: i for i, d in enumerate(calendar)}
    events["calendar_index"] = events["trade_date"].map(mapping)
    events["entry_date"] = events["calendar_index"].map({i: calendar[i + 1] for i in range(len(calendar) - 1)})
    events["exit_date"] = events["calendar_index"].map({i: calendar[i + 2] for i in range(len(calendar) - 2)})
    events = events.loc[
        events["normal_universe_eligible"] & events["entry_date"].notna() & events["exit_date"].notna()
    ].copy()
    bars = quotes[["symbol", "trade_date", "open_price", "close_price", "volume"]]
    events = events.merge(bars.rename(columns={"trade_date": "entry_date", "open_price": "entry_open",
                                                 "close_price": "entry_close", "volume": "entry_volume"}),
                          on=["symbol", "entry_date"], how="left", validate="many_to_one")
    events = events.merge(bars.rename(columns={"trade_date": "exit_date", "open_price": "exit_open",
                                                 "close_price": "exit_close", "volume": "exit_volume"}),
                          on=["symbol", "exit_date"], how="left", validate="many_to_one")
    events["entry_price_known"] = events["entry_open"].gt(0) & events["entry_volume"].gt(0)
    events["exit_price_known"] = events["exit_open"].gt(0) & events["exit_volume"].gt(0)
    events["direction_observable"] = events["entry_price_known"] & events["exit_price_known"]
    events["entry_gap"] = events["entry_open"] / events["close_price"] - 1
    events["exit_gap"] = events["exit_open"] / events["entry_close"] - 1
    events["entry_executable"] = events["entry_price_known"] & events["entry_gap"].lt(.095)
    events["blocked_exit"] = ~events["exit_price_known"] | events["exit_gap"].le(-.095)
    events["gross_return"] = events["exit_open"] / events["entry_open"] - 1
    events["up_correct"] = events["gross_return"].gt(0) & events["direction_observable"]
    events["down_correct"] = events["gross_return"].lt(0) & events["direction_observable"]
    events["net_return"] = events["gross_return"] - COST
    events.loc[events["blocked_exit"], "net_return"] = -.10 - COST
    return events.reset_index(drop=True)


def prepare(risk_gate: bool) -> pd.DataFrame:
    frames = []
    for label, path in SOURCES.items():
        frame = event_frame(path) if risk_gate else expanded_events(path)
        frame["cohort"] = label
        frames.append(frame)
    data = pd.concat(frames, ignore_index=True)
    data["year"] = data["trade_date"].dt.year
    data = data.loc[data["year"].between(2020, 2026) & data["year"].eq(data["exit_date"].dt.year)].copy()
    data = data.sort_values(["symbol", "trade_date"]).reset_index(drop=True)
    group = data.groupby("symbol", sort=False)
    # These lagged features are based on already eligible signal rows, whose
    # consecutive dates may skip sessions; use the actual previous quote below.
    quotes = pd.concat([load_quotes(str(path)) for path in SOURCES.values()], ignore_index=True)
    quotes = quotes.sort_values(["symbol", "trade_date"])
    by = quotes.groupby("symbol", sort=False)
    daily = quotes["close_price"] / by["close_price"].shift(1) - 1
    signs_up = daily.gt(0).astype(int)
    signs_down = daily.lt(0).astype(int)
    quotes["prior_return_1d"] = daily.groupby(quotes["symbol"], sort=False).shift(1)
    for n in (2, 3, 4):
        quotes[f"up_streak_{n}"] = signs_up.groupby(quotes["symbol"], sort=False).transform(
            lambda x, count=n: x.rolling(count).sum().eq(count))
        quotes[f"down_streak_{n}"] = signs_down.groupby(quotes["symbol"], sort=False).transform(
            lambda x, count=n: x.rolling(count).sum().eq(count))
    range_ = (quotes["high_price"] - quotes["low_price"]).replace(0, np.nan)
    quotes["upper_shadow"] = (quotes["high_price"] - quotes[["open_price", "close_price"]].max(axis=1)) / range_
    quotes["lower_shadow"] = (quotes[["open_price", "close_price"]].min(axis=1) - quotes["low_price"]) / range_
    quotes["body_share"] = (quotes["close_price"] - quotes["open_price"]).abs() / range_
    quotes["turn_of_month"] = quotes["trade_date"].dt.day.le(5) | quotes["trade_date"].dt.day.ge(27)
    quotes["weekday"] = quotes["trade_date"].dt.dayofweek
    extra = ["prior_return_1d", "up_streak_2", "up_streak_3", "up_streak_4",
             "down_streak_2", "down_streak_3", "down_streak_4", "upper_shadow",
             "lower_shadow", "body_share", "turn_of_month", "weekday"]
    data = data.merge(quotes[["symbol", "trade_date", *extra]], on=["symbol", "trade_date"],
                      how="left", validate="one_to_one")
    # Ranks in the three sampled cohorts are discovery-only. They cannot be
    # deployed without an as-of-date full-stock universe and matching ranks.
    for field in ("return_1d", "change_pct_5d", "return_20d", "rsi14", "volume_ratio_5d", "close_position"):
        data[f"{field}_cross_rank"] = data.groupby("trade_date")[field].rank(pct=True)
    ix = pd.read_csv(INDEX, parse_dates=["trade_date"]).sort_values("trade_date")
    close = ix["close"]
    for n in (1, 5, 20):
        ix[f"index_return_{n}d"] = close / close.shift(n) - 1
    ix["index_vs_ma20"] = close / close.rolling(20).mean() - 1
    ix["index_close_position"] = (ix["close"] - ix["low"]) / (ix["high"] - ix["low"]).replace(0, np.nan)
    index_cols = ["trade_date", "index_return_1d", "index_return_5d", "index_return_20d",
                  "index_vs_ma20", "index_close_position"]
    data = data.merge(ix[index_cols], on="trade_date", how="left", validate="many_to_one")
    return data


def atoms() -> list[Atom]:
    specs = {
        "rsi14": ([20, 25, 30, 35, 40], [60, 65, 70, 75, 80]),
        "return_1d": ([-.07, -.05, -.04, -.03, -.02], [.02, .03, .04, .05, .07]),
        "prior_return_1d": ([-.05, -.03, -.02], [.02, .03, .05]),
        "change_pct_5d": ([-.12, -.10, -.07, -.05], [.05, .07, .10, .12]),
        "return_20d": ([-.20, -.15, -.10], [.10, .15, .20]),
        "intraday_return": ([-.05, -.03, -.02], [.02, .03, .05]),
        "open_gap": ([-.03, -.02, -.01], [.01, .02, .03]),
        "close_position": ([.05, .10, .20], [.80, .90, .95]),
        "upper_shadow": ([.10, .20], [.60, .70]),
        "lower_shadow": ([.10, .20], [.60, .70]),
        "body_share": ([.10, .20], [.60, .70]),
        "volume_ratio_5d": ([.4, .6, .8], [1.5, 2, 3]),
        "amount_ratio20": ([.4, .6, .8], [1.5, 2, 3]),
        "normal_volatility_20d": ([.015, .02], [.03, .035]),
        "return_1d_cross_rank": ([.05, .10, .20], [.80, .90, .95]),
        "change_pct_5d_cross_rank": ([.05, .10, .20], [.80, .90, .95]),
        "return_20d_cross_rank": ([.05, .10, .20], [.80, .90, .95]),
        "rsi14_cross_rank": ([.05, .10, .20], [.80, .90, .95]),
        "volume_ratio_5d_cross_rank": ([.05, .10, .20], [.80, .90, .95]),
        "close_position_cross_rank": ([.05, .10, .20], [.80, .90, .95]),
        "index_return_1d": ([-.02, -.015, -.01], [.01, .015, .02]),
        "index_return_5d": ([-.05, -.03], [.03, .05]),
        "index_return_20d": ([-.10, -.05], [.05, .10]),
        "index_vs_ma20": ([-.05, 0], [0, .05]),
        "index_close_position": ([.10, .20], [.80, .90]),
    }
    out = []
    for field, (low, high) in specs.items():
        out += [Atom(field, "<=", x) for x in low]
        out += [Atom(field, ">=", x) for x in high]
    for field, values in {
        "up_streak_2": [True], "up_streak_3": [True], "up_streak_4": [True],
        "down_streak_2": [True], "down_streak_3": [True], "down_streak_4": [True],
        "weekday": [0, 4], "turn_of_month": [True, False],
        "exchange": ["SH", "SZ"], "risk_status": ["normal", "caution", "high_risk"],
    }.items():
        out += [Atom(field, "=", x) for x in values]
    return out


def nonoverlap(frame: pd.DataFrame) -> pd.DataFrame:
    ordered = frame[["symbol", "entry_date", "exit_date", "trade_date"]].sort_values(
        ["symbol", "entry_date", "trade_date"])
    keep = []
    for _, group in ordered.groupby("symbol", sort=False):
        previous_exit = pd.Timestamp.min
        for row in group.itertuples():
            if row.entry_date > previous_exit:
                keep.append(row.Index)
                previous_exit = row.exit_date
    return frame.loc[keep]


def metric(frame: pd.DataFrame, target: str) -> dict:
    chosen = nonoverlap(frame)
    n = len(chosen)
    wins = int(chosen[f"{target}_correct"].sum())
    return {
        "predictions": n, "correct": wins, "accuracy": round(wins / n, 5) if n else None,
        "wilson95_lower": round(wilson(wins, n), 5) if n else None,
        "symbols": int(chosen["symbol"].nunique()), "entry_dates": int(chosen["entry_date"].nunique()),
        "top_symbol_share": round(float(chosen["symbol"].value_counts(normalize=True).iloc[0]), 5) if n else None,
        "top_date_share": round(float(chosen["entry_date"].value_counts(normalize=True).iloc[0]), 5) if n else None,
        "unobservable": int((~chosen["direction_observable"]).sum()),
        "net_long_win_rate": round(float(chosen.loc[chosen["entry_executable"], "net_return"].gt(0).mean()), 5)
        if int(chosen["entry_executable"].sum()) else None,
    }


def period_metrics(data: pd.DataFrame, mask: np.ndarray, target: str) -> dict:
    selected = data.loc[mask]
    result = {str(year): metric(selected.loc[selected["year"].eq(year)], target)
              for year in range(2020, 2027)}
    result["2024_2026_pooled"] = metric(selected.loc[selected["year"].between(2024, 2026)], target)
    for cohort in SOURCES:
        result[f"{cohort}_2024_2026"] = metric(selected.loc[selected["cohort"].eq(cohort)
                                                      & selected["year"].between(2024, 2026)], target)
    result["all"] = metric(selected, target)
    return result


def search(data: pd.DataFrame, beam_width: int = 100) -> dict:
    # Training uses 2020-23 only, so its only contributing stock cohort is
    # development200; older two cohorts start in 2023 but their earlier
    # inspected 2023 data is excluded from search by the cohort requirement.
    fit = data["cohort"].eq("development200") & data["year"].between(2020, 2023)
    dev = data["year"].between(2024, 2026)
    atom_list = atoms()
    mfit = np.column_stack([a.mask(data.loc[fit]) for a in atom_list])
    mdev = np.column_stack([a.mask(data.loc[dev]) for a in atom_list])
    fit_data = data.loc[fit]
    dev_data = data.loc[dev]
    fit_dates = fit_data["entry_date"].to_numpy(dtype="datetime64[D]")
    fit_symbols = fit_data["symbol"].to_numpy()
    out = {}
    for target in ("up", "down"):
        y = fit_data[f"{target}_correct"].to_numpy(dtype=np.uint8)
        candidates = []
        evaluated = 0
        for i in range(len(atom_list)):
            first = mfit[:, i]
            for j in range(i + 1, len(atom_list)):
                if atom_list[i].field == atom_list[j].field:
                    continue
                mask = first & mfit[:, j]
                n = int(mask.sum())
                if n < 100:
                    continue
                evaluated += 1
                wins = int(y[mask].sum())
                if np.unique(fit_dates[mask]).size < 35 or np.unique(fit_symbols[mask]).size < 20:
                    continue
                score = wilson(wins, n)
                candidates.append((score, wins / n, n, (i, j)))
        candidates.sort(reverse=True)
        # Beam extension adds one or two independent fields; every expansion
        # is scored only on 2020-23 fitting data.
        beam = candidates[:beam_width]
        all_rules = list(beam)
        for depth in (3, 4):
            expanded = []
            for _, _, _, idx in beam:
                used = {atom_list[i].field for i in idx}
                base = np.logical_and.reduce([mfit[:, i] for i in idx])
                last = idx[-1]
                for k in range(last + 1, len(atom_list)):
                    if atom_list[k].field in used:
                        continue
                    mask = base & mfit[:, k]
                    n = int(mask.sum())
                    if n < 100:
                        continue
                    evaluated += 1
                    wins = int(y[mask].sum())
                    if np.unique(fit_dates[mask]).size < 35 or np.unique(fit_symbols[mask]).size < 20:
                        continue
                    score = wilson(wins, n)
                    expanded.append((score, wins / n, n, (*idx, k)))
            expanded.sort(reverse=True)
            beam = expanded[:beam_width]
            all_rules.extend(beam)
            if not beam:
                break
        # Evaluate only the strongest 2020-23 rules in the exposed later data.
        all_rules = sorted(set(all_rules), reverse=True)[:100]
        reports = []
        for score, fit_acc, fit_n, idx in all_rules:
            md = np.logical_and.reduce([mdev[:, i] for i in idx])
            if int(md.sum()) < 80:
                continue
            selected = dev_data.loc[md]
            diag = period_metrics(selected, np.ones(len(selected), dtype=bool), target)
            pooled = diag["2024_2026_pooled"]
            if pooled["predictions"] < 80 or pooled["symbols"] < 20 or pooled["entry_dates"] < 30:
                continue
            reports.append({"conditions": [atom_list[i].json() for i in idx],
                            "text": " AND ".join(atom_list[i].name() for i in idx),
                            "fit_2020_23": {"predictions": fit_n, "accuracy": round(fit_acc, 5),
                                            "wilson95_lower": round(score, 5)},
                            "development": diag})
        reports.sort(key=lambda r: (r["development"]["2024_2026_pooled"]["accuracy"],
                                    r["development"]["2024_2026_pooled"]["predictions"]), reverse=True)
        out[target] = {"tested_fit_combinations": evaluated, "reported": reports[:100],
                       "passing_pooled_65": [r for r in reports if r["development"]["2024_2026_pooled"]["accuracy"] >= .65]}
    return out


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--risk-gate", choices=("normal", "all"), default="normal")
    parser.add_argument("--beam-width", type=int, default=100)
    args = parser.parse_args()
    data = prepare(args.risk_gate == "normal")
    out = {"purpose": "development search only; sealed 100 stocks are not read",
           "risk_gate": args.risk_gate, "entry_exit": "T+1 market open to T+2 market open",
           "target": "strict gross open-to-open direction; missing quotes count incorrect",
           "source_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in SOURCES.items()},
           "hs300_sha256": hashlib.sha256(INDEX.read_bytes()).hexdigest(),
           "eligible_rows": len(data), "eligible_symbols": int(data["symbol"].nunique()),
           "baseline": {target: period_metrics(data, np.ones(len(data), dtype=bool), target)
                        for target in ("up", "down")}}
    out.update(search(data, args.beam_width))
    filename = HERE / f"rare_signal_{args.risk_gate}_results.json"
    filename.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
    for target in ("up", "down"):
        print(args.risk_gate, target, "tested", out[target]["tested_fit_combinations"],
              "reported", len(out[target]["reported"]), "pooled65", len(out[target]["passing_pooled_65"]))
        for item in out[target]["reported"][:5]:
            print(" ", item["text"], item["fit_2020_23"], item["development"]["2024_2026_pooled"])
    print("wrote", filename)


if __name__ == "__main__":
    main()
