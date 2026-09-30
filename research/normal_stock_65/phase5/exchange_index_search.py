#!/usr/bin/env python3
"""Development-only bullish search using each stock's home-exchange index.

The exchange index is chosen from the stock code, without looking at labels:
SH -> SSE Composite and SZ -> SZSE Component. All index predicates are
computed with data available at the signal-day close. Neither the sealed
100-stock quotes nor their outcomes are loaded here.
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
INDEX = HERE / "exchange_indices_unadjusted_baostock.csv"
INDEX_MANIFEST = HERE / "exchange_indices_manifest.json"
OUTPUT = HERE / "exchange_index_results.json"
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
        if self.op == "=":
            return x.eq(self.value).fillna(False).to_numpy(bool)
        raise ValueError(self.op)


def exchange_features() -> pd.DataFrame:
    manifest = json.loads(INDEX_MANIFEST.read_text(encoding="utf-8"))
    sha = hashlib.sha256(INDEX.read_bytes()).hexdigest()
    if sha != manifest["source_sha256"]:
        raise ValueError("exchange index source hash differs from frozen manifest")
    ix = pd.read_csv(INDEX, parse_dates=["trade_date"])
    code_to_exchange = {"sh.000001": "SH", "sz.399001": "SZ"}
    if set(ix["code"]) != set(code_to_exchange):
        raise ValueError("unexpected index codes")
    ix["exchange"] = ix["code"].map(code_to_exchange)
    if ix.duplicated(["exchange", "trade_date"]).any():
        raise ValueError("duplicate exchange index date")
    if not ix["trade_status"].eq(1).all():
        raise ValueError("exchange index has untraded bars")
    ix = ix.sort_values(["exchange", "trade_date"]).copy()
    by = ix.groupby("exchange", sort=False)
    previous = by["close"].shift(1)
    ix["local_return_1d"] = ix["close"] / previous - 1
    ix["local_return_5d"] = ix["close"] / by["close"].shift(5) - 1
    ix["local_intraday"] = ix["close"] / ix["open"] - 1
    ix["local_close_position"] = (ix["close"] - ix["low"]) / (ix["high"] - ix["low"]).replace(0, np.nan)
    ix["local_open_gap"] = ix["open"] / previous - 1
    ix["local_vs_ma20"] = ix["close"] / by["close"].transform(
        lambda x: x.rolling(20, min_periods=20).mean()) - 1
    ix["local_volatility20"] = ix.groupby("exchange", sort=False)["local_return_1d"].transform(
        lambda x: x.rolling(20, min_periods=20).std())
    ix["local_amount_ratio20"] = ix["amount_yuan"] / by["amount_yuan"].transform(
        lambda x: x.shift(1).rolling(20, min_periods=20).median())
    cols = ["trade_date", "exchange"] + [c for c in ix if c.startswith("local_")]
    return ix[cols].copy()


def index_atoms() -> list[Atom]:
    # Frozen, round-number thresholds; exactly two weak and two strong states
    # per family. The local index is always determined by exchange above.
    grid = {
        "local_return_1d": ([-.02, -.01], [.01, .02]),
        "local_return_5d": ([-.05, -.03], [.03, .05]),
        "local_intraday": ([-.02, -.01], [.01, .02]),
        "local_close_position": ([.20, .40], [.60, .80]),
        "local_open_gap": ([-.01, -.005], [.005, .01]),
        "local_vs_ma20": ([-.05, -.02], [.02, .05]),
        "local_volatility20": ([.010, .014], [.018, .025]),
        "local_amount_ratio20": ([.7, .9], [1.2, 1.5]),
    }
    return [Atom(field, op, value) for field, (lows, highs) in grid.items()
            for op, values in (("<=", lows), (">=", highs)) for value in values]


def stock_atoms() -> list[Atom | None]:
    return [
        None,
        Atom("return_1d", "<=", -.02), Atom("return_1d", ">=", .02),
        Atom("close_position", "<=", .20), Atom("close_position", ">=", .80),
        Atom("open_gap", "<=", -.01), Atom("open_gap", ">=", .01),
        Atom("normal_volatility_20d", "<=", .025),
        Atom("exchange", "=", "SH"), Atom("exchange", "=", "SZ"),
    ]


def load_events() -> pd.DataFrame:
    frames = []
    memberships = []
    for cohort, path in SOURCES.items():
        frame = event_frame(path)
        frame["cohort"] = cohort
        memberships.append(set(frame["symbol"]))
        frames.append(frame)
    for i in range(len(memberships)):
        for j in range(i + 1, len(memberships)):
            if memberships[i] & memberships[j]:
                raise ValueError("exposed stock cohorts overlap")
    events = pd.concat(frames, ignore_index=True)
    if not events["exchange"].isin(("SH", "SZ")).all():
        raise ValueError("unexpected stock exchange")
    events = events.merge(exchange_features(), on=["trade_date", "exchange"],
                           how="left", validate="many_to_one")
    events["year"] = events["trade_date"].dt.year
    events = events.loc[events["year"].between(2020, 2026)
                         & events["year"].eq(events["exit_date"].dt.year)].copy()
    if events["local_volatility20"].isna().any():
        raise ValueError("missing same-day local index feature")
    return events.reset_index(drop=True)


def nonoverlap(frame: pd.DataFrame) -> pd.DataFrame:
    keep = []
    previous_exit = {}
    for row in frame.sort_values(["symbol", "entry_date", "trade_date"]).itertuples():
        if row.symbol not in previous_exit or row.entry_date > previous_exit[row.symbol]:
            keep.append(row.Index)
            previous_exit[row.symbol] = row.exit_date
    return frame.loc[keep].copy()


def measure(frame: pd.DataFrame, *, bootstrap: bool = False) -> dict:
    chosen = nonoverlap(frame)
    n = len(chosen)
    k = int(chosen["up_correct"].sum())
    if not n:
        return {"predictions": 0, "correct": 0, "accuracy": None,
                "wilson95_lower": None, "date_cluster95_lower": None,
                "symbols": 0, "entry_dates": 0, "top_date_share": None,
                "unobservable": 0, "entry_unexecutable": 0,
                "net_long_win_rate": None}
    daily = chosen.groupby("entry_date")["up_correct"].agg(["sum", "count"])
    cluster = None
    if bootstrap and len(daily) >= 10:
        rng = np.random.default_rng(20260930)
        draws = rng.integers(0, len(daily), size=(3000, len(daily)))
        rates = daily["sum"].to_numpy()[draws].sum(axis=1) / daily["count"].to_numpy()[draws].sum(axis=1)
        cluster = float(np.quantile(rates, .025))
    executable = chosen.loc[chosen["entry_executable"]]
    net_wins = executable["net_return"].gt(0) & ~executable["blocked_exit"]
    return {
        "predictions": n, "correct": k, "accuracy": round(k / n, 6),
        "wilson95_lower": round(wilson(k, n), 6),
        "date_cluster95_lower": round(cluster, 6) if cluster is not None else None,
        "symbols": int(chosen["symbol"].nunique()), "entry_dates": len(daily),
        "top_date_share": round(float(daily["count"].max() / n), 6),
        "unobservable": int((~chosen["direction_observable"]).sum()),
        "entry_unexecutable": int((~chosen["entry_executable"]).sum()),
        "net_long_win_rate": round(float(net_wins.mean()), 6) if len(executable) else None,
    }


def supported(m: dict) -> bool:
    return (m["predictions"] >= 100 and m["entry_dates"] >= 30
            and m["symbols"] >= 20 and m["top_date_share"] <= .15)


def fit_rank(mask: np.ndarray, y: np.ndarray, years: np.ndarray,
             dates: np.ndarray, symbols: np.ndarray) -> tuple[float, float, int] | None:
    n = int(mask.sum())
    if n < 200:
        return None
    annual = np.bincount(years[mask] - 2020, minlength=4)
    if int(annual.min()) < 30:
        return None
    daily = np.bincount(dates[mask])
    if np.count_nonzero(daily) < 60 or daily.max() / n > .15:
        return None
    if np.count_nonzero(np.bincount(symbols[mask])) < 25:
        return None
    yearly_wins = np.bincount(years[mask] - 2020, weights=y[mask], minlength=4)
    worst_year = float((yearly_wins / annual).min())
    lower = wilson(int(y[mask].sum()), n)
    return float(min(lower, .5 * (lower + worst_year))), worst_year, n


def candidate_mask(frame: pd.DataFrame, local: list[Atom], stock: Atom | None) -> np.ndarray:
    selected = np.logical_and.reduce([atom.mask(frame) for atom in local])
    if stock is not None:
        selected &= stock.mask(frame)
    return selected


def main() -> None:
    events = load_events()
    fit = events.loc[events["cohort"].eq("development200") & events["year"].between(2020, 2023)]
    calibration = events.loc[events["cohort"].eq("development200") & events["year"].eq(2024)]
    local, stock = index_atoms(), stock_atoms()
    local_fit = np.column_stack([a.mask(fit) for a in local]).astype(bool)
    stock_fit = np.column_stack([np.ones(len(fit), bool) if a is None else a.mask(fit) for a in stock])
    y = fit["up_correct"].to_numpy(np.uint8)
    years = fit["year"].to_numpy(np.int16)
    dates = pd.factorize(fit["entry_date"])[0]
    symbols = pd.factorize(fit["symbol"])[0]
    ranked = []
    examined = {"one_local": 0, "two_local": 0}

    def consider(indices: tuple[int, ...], si: int) -> None:
        mask = local_fit[:, indices[0]] & stock_fit[:, si]
        if len(indices) == 2:
            mask &= local_fit[:, indices[1]]
        score = fit_rank(mask, y, years, dates, symbols)
        if score is not None:
            ranked.append((score, indices, si))

    for li in range(len(local)):
        for si in range(len(stock)):
            examined["one_local"] += 1
            consider((li,), si)
    ranked.sort(key=lambda row: row[0], reverse=True)
    # Fit-only beam: one extra local-index family for the best 40 singles.
    for _, (li,), si in ranked[:40]:
        for other in range(len(local)):
            if local[other].field == local[li].field:
                continue
            examined["two_local"] += 1
            consider((li, other), si)
    ranked.sort(key=lambda row: row[0], reverse=True)
    shortlist = []
    seen = set()
    for score, indices, si in ranked:
        key = (tuple(sorted(indices)), si)
        if key in seen:
            continue
        seen.add(key)
        chosen = calibration.loc[candidate_mask(calibration, [local[i] for i in indices], stock[si])]
        m = measure(chosen)
        if not supported(m):
            continue
        shortlist.append({
            "local_index": [asdict(local[i]) for i in indices],
            "stock": asdict(stock[si]) if stock[si] is not None else None,
            "fit_score": round(score[0], 6), "fit_worst_year": round(score[1], 6),
            "fit_raw_predictions": score[2], "calibration": m,
        })
        if len(shortlist) == 20:
            break
    selected = max(shortlist, key=lambda row: (row["calibration"]["wilson95_lower"],
                                                row["fit_score"]), default=None)
    result = {
        "protocol": "Home-exchange index set by stock code. Fit/rank development200 2020-23; best 20 fit-supported configurations with 2024 support choose one by 2024 Wilson lower; fixed rule on exposed 2025-26 cohorts.",
        "target": "Strict T+1 open to T+2 open bullish gross direction, signal at T close; missing exit wrong.",
        "index_mapping": {"SH": "sh.000001 SSE Composite", "SZ": "sz.399001 SZSE Component"},
        "sealed_validation_used": False,
        "exposed_caveat": "Later cohorts and 2025-26 have previously been researched; diagnostics are not untouched confirmation.",
        "source_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in SOURCES.items()},
        "index_sha256": hashlib.sha256(INDEX.read_bytes()).hexdigest(),
        "input_events": {"fit": len(fit), "calibration": len(calibration),
                         "diagnostic_2025_26": int(events["year"].isin((2025, 2026)).sum())},
        "examined": examined, "fit_supported_configs": len(ranked),
        "calibration_shortlist": shortlist, "selected": selected,
        "baseline": {}, "diagnostics": {},
    }
    for cohort in SOURCES:
        for year in (2024, 2025, 2026):
            scope = events.loc[events["cohort"].eq(cohort) & events["year"].eq(year)]
            key = f"{cohort}_{year}"
            result["baseline"][key] = measure(scope)
            if selected is not None:
                conditions = [Atom(**x) for x in selected["local_index"]]
                stock_condition = Atom(**selected["stock"]) if selected["stock"] else None
                result["diagnostics"][key] = measure(
                    scope.loc[candidate_mask(scope, conditions, stock_condition)], bootstrap=True)
    if selected is not None:
        result["every_year_supported_and_65"] = all(
            supported(result["diagnostics"][f"{cohort}_{year}"])
            and result["diagnostics"][f"{cohort}_{year}"]["accuracy"] >= .65
            for cohort in SOURCES for year in (2025, 2026))
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"examined": examined, "shortlist": len(shortlist),
                      "selected": selected, "diagnostics": result["diagnostics"]},
                     ensure_ascii=False, indent=2))
    print("wrote", OUTPUT)


if __name__ == "__main__":
    main()
