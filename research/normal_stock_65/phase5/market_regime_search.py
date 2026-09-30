#!/usr/bin/env python3
"""Chronological, development-only search for T-close bullish market regimes.

Only exposed stock cohorts are used. Fit/rank on development200 in 2020-23,
choose one from the fit shortlist on development200 in 2024, and diagnose the
fixed choice on the exposed 2025-26 cohorts. Sealed stock quotes are not read.
"""

from __future__ import annotations

import hashlib
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
INDEX = HERE.parent / "phase4/hs300_unadjusted_baostock.csv"
OUTPUT = HERE / "market_regime_results.json"
SOURCES = {
    "development200": ROOT / "research/normal_stock_65/phase2/development_qfq.csv",
    "old166": ROOT / "research/one_day_20260930/ashare_daily_qfq.csv",
    "external250": ROOT / "research/normal_stock_65/external_mainboard_qfq.csv",
}


@dataclass(frozen=True)
class Atom:
    field: str
    op: str
    value: float | str

    def mask(self, frame: pd.DataFrame) -> np.ndarray:
        x = frame[self.field]
        if self.op == "<=":
            return x.le(self.value).fillna(False).to_numpy(bool)
        if self.op == ">=":
            return x.ge(self.value).fillna(False).to_numpy(bool)
        return x.eq(self.value).fillna(False).to_numpy(bool)


def index_features() -> pd.DataFrame:
    ix = pd.read_csv(INDEX, parse_dates=["trade_date"]).sort_values("trade_date")
    if ix["trade_date"].duplicated().any():
        raise ValueError("duplicate index date")
    if not ix["trade_status"].eq(1).all():
        raise ValueError("index has untraded bars")
    close = ix["close"]
    daily = close.pct_change(fill_method=None)
    for n in (1, 5, 20):
        ix[f"index_return_{n}d"] = close / close.shift(n) - 1
    ix["index_intraday_return"] = close / ix["open"] - 1
    ix["index_open_gap"] = ix["open"] / ix["pre_close"] - 1
    ix["index_close_position"] = (close - ix["low"]) / (ix["high"] - ix["low"]).replace(0, np.nan)
    ix["index_range_pct"] = (ix["high"] - ix["low"]) / close
    for n in (5, 20):
        ix[f"index_volatility_{n}d"] = daily.rolling(n, min_periods=n).std()
        ix[f"index_vs_ma{n}"] = close / close.rolling(n, min_periods=n).mean() - 1
    ix["index_drawdown_20d"] = close / ix["high"].rolling(20, min_periods=20).max() - 1
    ix["index_amount_ratio20"] = ix["amount_yuan"] / ix["amount_yuan"].shift(1).rolling(20, min_periods=20).median()
    columns = ["trade_date"] + [c for c in ix if c.startswith("index_")]
    return ix[columns].copy()


def market_atoms() -> list[Atom]:
    grids = {
        "index_return_1d": ([-.02, -.01], [.01, .02]),
        "index_return_5d": ([-.05, -.03], [.03, .05]),
        "index_return_20d": ([-.10, -.05], [.05, .10]),
        "index_intraday_return": ([-.02, -.01], [.01, .02]),
        "index_open_gap": ([-.01, -.005], [.005, .01]),
        "index_close_position": ([.20, .40], [.60, .80]),
        "index_range_pct": ([.010, .015], [.025, .035]),
        "index_volatility_5d": ([.008, .012], [.018, .025]),
        "index_volatility_20d": ([.010, .014], [.018, .025]),
        "index_vs_ma5": ([-.03, -.01], [.01, .03]),
        "index_vs_ma20": ([-.05, -.02], [.02, .05]),
        "index_drawdown_20d": ([-.10, -.05], [-.02, -.005]),
        "index_amount_ratio20": ([.7, .9], [1.2, 1.5]),
    }
    return [Atom(field, op, value) for field, (lows, highs) in grids.items()
            for op, values in (("<=", lows), (">=", highs)) for value in values]


def stock_atoms() -> list[Atom | None]:
    # None is the regime-only baseline; all others use T-close stock data.
    return [None,
            Atom("return_1d", "<=", -.02), Atom("return_1d", "<=", -.03),
            Atom("change_pct_5d", "<=", -.05), Atom("close_position", "<=", .20),
            Atom("close_position", ">=", .80), Atom("open_gap", ">=", .01),
            Atom("open_gap", "<=", -.01), Atom("rsi14", "<=", 35),
            Atom("normal_volatility_20d", "<=", .025),
            Atom("exchange", "=", "SZ"), Atom("exchange", "=", "SH")]


def load_events() -> pd.DataFrame:
    frames = []
    member_sets = []
    for cohort, csv in SOURCES.items():
        frame = event_frame(csv)
        frame["cohort"] = cohort
        frames.append(frame)
        member_sets.append(set(frame["symbol"]))
    for i in range(len(member_sets)):
        for j in range(i + 1, len(member_sets)):
            if member_sets[i] & member_sets[j]:
                raise ValueError("exposed stock cohorts overlap")
    events = pd.concat(frames, ignore_index=True)
    events = events.merge(index_features(), on="trade_date", how="left", validate="many_to_one")
    events["year"] = events["trade_date"].dt.year
    events = events.loc[events["year"].between(2020, 2026)
                         & events["year"].eq(events["exit_date"].dt.year)].copy()
    if events["index_return_20d"].isna().any():
        raise ValueError("missing same-day index feature")
    return events.reset_index(drop=True)


def nonoverlap(frame: pd.DataFrame) -> pd.DataFrame:
    keep = []
    last_exit = {}
    for row in frame.sort_values(["symbol", "entry_date", "trade_date"]).itertuples():
        if row.symbol not in last_exit or row.entry_date > last_exit[row.symbol]:
            keep.append(row.Index)
            last_exit[row.symbol] = row.exit_date
    return frame.loc[keep].copy()


def measure(frame: pd.DataFrame, bootstrap: bool = True) -> dict:
    chosen = nonoverlap(frame)
    n = len(chosen)
    k = int(chosen["up_correct"].sum())
    if not n:
        return {"predictions": 0, "correct": 0, "accuracy": None,
                "wilson95_lower": None, "date_cluster95_lower": None,
                "symbols": 0, "entry_dates": 0, "top_date_share": None,
                "unobservable": 0, "entry_unexecutable": 0,
                "net_long_win_rate": None}
    by_date = chosen.groupby("entry_date")["up_correct"].agg(["sum", "count"])
    cluster = None
    if bootstrap and len(by_date) >= 10:
        rng = np.random.default_rng(20260930)
        draws = rng.integers(0, len(by_date), size=(2000, len(by_date)))
        correct = by_date["sum"].to_numpy()[draws].sum(axis=1)
        count = by_date["count"].to_numpy()[draws].sum(axis=1)
        cluster = float(np.quantile(correct / count, .025))
    executable = chosen.loc[chosen["entry_executable"]]
    net = executable["net_return"].gt(0) & ~executable["blocked_exit"]
    return {
        "predictions": n, "correct": k, "accuracy": round(k / n, 6),
        "wilson95_lower": round(wilson(k, n), 6),
        "date_cluster95_lower": round(cluster, 6) if cluster is not None else None,
        "symbols": int(chosen["symbol"].nunique()),
        "entry_dates": len(by_date),
        "top_date_share": round(float(by_date["count"].max() / n), 6),
        "unobservable": int((~chosen["direction_observable"]).sum()),
        "entry_unexecutable": int((~chosen["entry_executable"]).sum()),
        "net_long_win_rate": round(float(net.mean()), 6) if len(executable) else None,
    }


def rank_fit(mask: np.ndarray, y: np.ndarray, year: np.ndarray, date: np.ndarray,
             symbol: np.ndarray) -> tuple[float, float, int] | None:
    n = int(mask.sum())
    if n < 200:
        return None
    year_count = np.bincount(year[mask] - 2020, minlength=4)
    if int(year_count.min()) < 30:
        return None
    dates = np.bincount(date[mask])
    if np.count_nonzero(dates) < 60 or dates.max() / n > .15:
        return None
    if np.count_nonzero(np.bincount(symbol[mask])) < 25:
        return None
    wins = int(y[mask].sum())
    year_wins = np.bincount(year[mask] - 2020, weights=y[mask], minlength=4)
    year_rates = year_wins / year_count
    lower = wilson(wins, n)
    min_year = float(year_rates.min())
    return float(min(lower, .5 * lower + .5 * min_year)), min_year, n


def candidate_mask(frame: pd.DataFrame, markets: list[Atom], stock: Atom | None) -> np.ndarray:
    mask = np.logical_and.reduce([atom.mask(frame) for atom in markets])
    if stock is not None:
        mask &= stock.mask(frame)
    return mask


def supported(metric: dict) -> bool:
    return (metric["predictions"] >= 100 and metric["entry_dates"] >= 30
            and metric["symbols"] >= 20 and metric["top_date_share"] <= .15)


def main() -> None:
    events = load_events()
    fit = events.loc[events["cohort"].eq("development200") & events["year"].between(2020, 2023)]
    cal = events.loc[events["cohort"].eq("development200") & events["year"].eq(2024)]
    market = market_atoms()
    stock = stock_atoms()
    matrix = np.column_stack([atom.mask(fit) for atom in market]).astype(bool)
    stock_matrix = np.column_stack([np.ones(len(fit), bool) if atom is None else atom.mask(fit) for atom in stock])
    y = fit["up_correct"].to_numpy(np.uint8)
    years = fit["year"].to_numpy(np.int16)
    dates = pd.factorize(fit["entry_date"])[0]
    symbols = pd.factorize(fit["symbol"])[0]
    ranked = []
    examined = {"one_market": 0, "two_market": 0}

    def consider(market_indices: tuple[int, ...], stock_index: int) -> None:
        mask = matrix[:, market_indices[0]] & stock_matrix[:, stock_index]
        if len(market_indices) == 2:
            mask = mask & matrix[:, market_indices[1]]
        score = rank_fit(mask, y, years, dates, symbols)
        if score is not None:
            ranked.append((score, market_indices, stock_index))

    for mi in range(len(market)):
        for si in range(len(stock)):
            examined["one_market"] += 1
            consider((mi,), si)
    ranked.sort(key=lambda item: item[0], reverse=True)
    # Add a distinct index family to fit's best 80 combinations. Later outcomes
    # never enter this beam. Avoid searching all 52^2 * 12 combinations.
    initial = ranked[:80]
    for _, indices, si in initial:
        mi = indices[0]
        for extra in range(len(market)):
            if market[extra].field == market[mi].field:
                continue
            examined["two_market"] += 1
            consider((mi, extra), si)
    ranked.sort(key=lambda item: item[0], reverse=True)
    shortlist = []
    seen_masks = set()
    for score, indices, si in ranked:
        # Equivalent orderings of two market predicates are one configuration.
        key = (tuple(sorted(indices)), si)
        if key in seen_masks:
            continue
        seen_masks.add(key)
        mask = candidate_mask(cal, [market[i] for i in indices], stock[si])
        m = measure(cal.loc[mask])
        if not supported(m):
            continue
        shortlist.append({"market": [asdict(market[i]) for i in indices],
                          "stock": asdict(stock[si]) if stock[si] is not None else None,
                          "fit_score": round(score[0], 6), "fit_min_year": round(score[1], 6),
                          "fit_raw_predictions": score[2], "calibration": m})
        if len(shortlist) == 20:
            break
    selected = max(shortlist, key=lambda x: (x["calibration"]["wilson95_lower"],
                                             x["fit_score"]), default=None)
    report = {
        "protocol": "Fit/rank 2020-23 phase2 development200; top 20 fit-supported configurations reaching 2024 development200 coverage choose one by Wilson lower; fixed choice diagnosed on 2025-26 three exposed cohorts.",
        "target": "Strict T+1 open to T+2 open gross bullish direction; missing opens count incorrect; signals at T close.",
        "blind_validation_used": False,
        "exposed_2025_26_caveat": "All later cohorts/years have been used elsewhere in prior searches; diagnostics are not independent validation.",
        "source_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in SOURCES.items()},
        "index_sha256": hashlib.sha256(INDEX.read_bytes()).hexdigest(),
        "input_events": {"fit": len(fit), "calibration": len(cal),
                         "diagnostic_2025_26": int(events["year"].isin((2025, 2026)).sum())},
        "examined": examined, "fit_supported_configs": len(ranked),
        "calibration_shortlist": shortlist, "selected": selected,
        "diagnostics": {}, "baseline": {},
    }
    for cohort in SOURCES:
        for year in (2024, 2025, 2026):
            scope = events.loc[events["cohort"].eq(cohort) & events["year"].eq(year)]
            key = f"{cohort}_{year}"
            report["baseline"][key] = measure(scope)
            if selected:
                m = [Atom(**a) for a in selected["market"]]
                s = Atom(**selected["stock"]) if selected["stock"] is not None else None
                report["diagnostics"][key] = measure(scope.loc[candidate_mask(scope, m, s)])
    if selected:
        report["every_year_supported_and_65"] = all(
            supported(report["diagnostics"][f"{cohort}_{year}"])
            and report["diagnostics"][f"{cohort}_{year}"]["accuracy"] >= .65
            for cohort in SOURCES for year in (2025, 2026))
    OUTPUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"examined": examined, "shortlist": len(shortlist),
                      "selected": selected, "diagnostics": report["diagnostics"]},
                     ensure_ascii=False, indent=2))
    print("wrote", OUTPUT)


if __name__ == "__main__":
    main()
