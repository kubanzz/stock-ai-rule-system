#!/usr/bin/env python3
"""Chronological search for a T-day-defined normal-stock bullish subgroup.

Only phase2 development200, 2020-23, ranks configurations. The best two
per base signal enter 2024 calibration, where one is frozen. Later years and
other exposed cohorts are diagnostics. Sealed validation is never loaded.
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
INDEX = HERE / "hs300_unadjusted_baostock.csv"
OUT = HERE / "subgroup_results.json"
SOURCES = {
    "development200": ROOT / "research/normal_stock_65/phase2/development_qfq.csv",
    "old166": ROOT / "research/one_day_20260930/ashare_daily_qfq.csv",
    "external250": ROOT / "research/normal_stock_65/external_mainboard_qfq.csv",
}


@dataclass(frozen=True)
class Configuration:
    signal: str
    subgroup: str


def add_matured_stock_history(events: pd.DataFrame) -> pd.DataFrame:
    """Lagged same-stock up rate; an exit open enters history at its own date."""
    events = events.sort_values(["symbol", "trade_date"]).copy()
    events["stock_up_rate_252"] = np.nan
    events["stock_up_history_n_252"] = 0
    for _, group in events.groupby("symbol", sort=False):
        signal_dates = group["trade_date"].to_numpy(dtype="datetime64[ns]")
        exit_dates = group["exit_date"].to_numpy(dtype="datetime64[ns]")
        end = np.searchsorted(exit_dates, signal_dates, side="right")
        start = np.maximum(end - 252, 0)
        cumsum = np.r_[0, np.cumsum(group["up_correct"].to_numpy(np.int64))]
        n = end - start
        # Beta(25, 25) prior avoids selecting stocks on a few lucky outcomes.
        events.loc[group.index, "stock_up_rate_252"] = (cumsum[end] - cumsum[start] + 25) / (n + 50)
        events.loc[group.index, "stock_up_history_n_252"] = n
    return events.sort_index()


def load_events() -> pd.DataFrame:
    frames = []
    for cohort, path in SOURCES.items():
        frame = event_frame(path)
        frame["cohort"] = cohort
        frames.append(frame)
    events = pd.concat(frames, ignore_index=True)
    events = add_matured_stock_history(events)
    index = pd.read_csv(INDEX, parse_dates=["trade_date"])
    index["index_close_position"] = (index["close"] - index["low"]) / (index["high"] - index["low"]).replace(0, np.nan)
    events = events.merge(index[["trade_date", "index_close_position"]], on="trade_date", how="left", validate="many_to_one")
    events["year"] = events["trade_date"].dt.year
    return events.loc[events["year"].between(2020, 2026)
                      & events["year"].eq(events["exit_date"].dt.year)].reset_index(drop=True)


def signal_masks(frame: pd.DataFrame) -> dict[str, pd.Series]:
    return {
        "all_normal": pd.Series(True, index=frame.index),
        "gap_market_pullback": frame["open_gap"].ge(.01) & frame["index_close_position"].le(.2),
        "stock_reversal": frame["return_1d"].le(-.03) & frame["close_position"].le(.2),
        "oversold_close": frame["intraday_return"].le(-.02) & frame["rsi14"].le(35),
    }


def subgroup_masks(frame: pd.DataFrame) -> dict[str, pd.Series]:
    history = frame["stock_up_history_n_252"].ge(100)
    return {
        "all": pd.Series(True, index=frame.index),
        "SZ": frame["exchange"].eq("SZ"),
        "SH": frame["exchange"].eq("SH"),
        "low_vol_020": frame["normal_volatility_20d"].le(.02),
        "low_vol_025": frame["normal_volatility_20d"].le(.025),
        "high_vol_020": frame["normal_volatility_20d"].ge(.02),
        "high_vol_025": frame["normal_volatility_20d"].ge(.025),
        "amount_100m": frame["amount"].ge(100_000_000),
        "amount_300m": frame["amount"].ge(300_000_000),
        "price_low_10": frame["close_price"].le(10),
        "price_high_10": frame["close_price"].ge(10),
        "price_high_20": frame["close_price"].ge(20),
        "prior_up_052": history & frame["stock_up_rate_252"].ge(.52),
        "prior_up_055": history & frame["stock_up_rate_252"].ge(.55),
        "liquid_low_vol": frame["amount"].ge(100_000_000) & frame["normal_volatility_20d"].le(.02),
    }


def nonoverlap(frame: pd.DataFrame) -> pd.DataFrame:
    """Do not count simultaneous T+1-to-T+2 exposures twice per stock."""
    if frame.empty:
        return frame
    keep = []
    last_exit = {}
    for row in frame.sort_values(["symbol", "entry_date", "trade_date"]).itertuples():
        previous = last_exit.get(row.symbol)
        if previous is None or row.entry_date > previous:
            keep.append(row.Index)
            last_exit[row.symbol] = row.exit_date
    return frame.loc[keep].copy()


def basic(frame: pd.DataFrame) -> dict:
    chosen = nonoverlap(frame)
    n = len(chosen)
    k = int(chosen["up_correct"].sum())
    by_year = chosen.groupby("year")["up_correct"].agg(["sum", "count"])
    rates = {str(year): round(row["sum"] / row["count"], 6)
             for year, row in by_year.iterrows()}
    year_n = {str(year): int(row["count"]) for year, row in by_year.iterrows()}
    return {
        "predictions": n, "correct": k,
        "accuracy": round(k / n, 6) if n else None,
        "wilson95_lower": round(wilson(k, n), 6) if n else None,
        "symbols": int(chosen["symbol"].nunique()),
        "entry_dates": int(chosen["entry_date"].nunique()),
        "top_date_share": round(float(chosen["entry_date"].value_counts(normalize=True).iloc[0]), 6) if n else None,
        "top_symbol_share": round(float(chosen["symbol"].value_counts(normalize=True).iloc[0]), 6) if n else None,
        "year_n": year_n, "year_accuracy": rates,
    }


def detailed(frame: pd.DataFrame) -> dict:
    chosen = nonoverlap(frame)
    out = basic(frame)
    if chosen.empty:
        return out
    by_date = chosen.groupby("entry_date")["up_correct"].agg(["sum", "count"])
    if len(by_date) > 1:
        rng = np.random.default_rng(20260930)
        draws = rng.integers(0, len(by_date), size=(3000, len(by_date)))
        rate = by_date["sum"].to_numpy()[draws].sum(axis=1) / by_date["count"].to_numpy()[draws].sum(axis=1)
        out["date_cluster_bootstrap95"] = [round(float(x), 6) for x in np.quantile(rate, [.025, .975])]
    ranked_days = by_date.sort_values(["count", "sum"], ascending=False)
    rest = ranked_days.iloc[1:]
    out["remove_busiest_date"] = {
        "predictions": int(rest["count"].sum()),
        "correct": int(rest["sum"].sum()),
        "accuracy": round(float(rest["sum"].sum() / rest["count"].sum()), 6) if len(rest) else None,
    }
    executable = chosen.loc[chosen["entry_executable"]]
    out["unobservable"] = int((~chosen["direction_observable"]).sum())
    out["entry_unexecutable"] = int((~chosen["entry_executable"]).sum())
    out["long_net_win_rate"] = round(float((executable["net_return"].gt(0) & ~executable["blocked_exit"]).mean()), 6) if len(executable) else None
    return out


def main() -> None:
    events = load_events()
    signal = signal_masks(events)
    group = subgroup_masks(events)
    fit = events["cohort"].eq("development200") & events["year"].between(2020, 2023)
    cal = events["cohort"].eq("development200") & events["year"].eq(2024)
    ranked: dict[str, list] = {}
    for name, mask in signal.items():
        candidates = []
        for subgroup, stock_mask in group.items():
            config = Configuration(name, subgroup)
            selected = events.loc[fit & mask & stock_mask]
            m = basic(selected)
            counts = list(m["year_n"].values())
            if (m["predictions"] < 200 or m["entry_dates"] < 60 or m["symbols"] < 20
                    or sum(n >= 20 for n in counts) < 3 or m["top_date_share"] > .15):
                continue
            stable_years = [m["year_accuracy"][year] for year, n in m["year_n"].items() if n >= 20]
            m["selection_score"] = round(min(m["wilson95_lower"],
                                             .5 * m["wilson95_lower"] + .5 * min(stable_years)), 6)
            candidates.append({"configuration": asdict(config), "fit": m})
        ranked[name] = sorted(candidates, key=lambda x: (x["fit"]["selection_score"],
                                                         x["fit"]["predictions"]), reverse=True)

    # Only two subgroup choices from each fixed signal reach 2024 calibration.
    finalists = []
    for name in signal:
        for candidate in ranked[name][:2]:
            subgroup = candidate["configuration"]["subgroup"]
            calibration = basic(events.loc[cal & signal[name] & group[subgroup]])
            if (calibration["predictions"] < 100 or calibration["entry_dates"] < 30
                    or calibration["symbols"] < 15 or calibration["top_date_share"] > .15):
                continue
            finalists.append({**candidate, "calibration": calibration})
    selected = max(finalists, key=lambda x: (x["calibration"]["wilson95_lower"],
                                               x["fit"]["selection_score"]), default=None)
    report = {
        "protocol": "T-close normal-stock event; 2020-23 phase2dev200 fit ranks 15 point-in-time stock subgroups for each of four fixed bullish signal families. Top two per family reach 2024 calibration. One is frozen before 2025/26 exposed diagnostics.",
        "prediction": "T+2 open > T+1 open, with missing opens counted wrong",
        "blind_validation_used": False,
        "source_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in SOURCES.items()},
        "hs300_sha256": hashlib.sha256(INDEX.read_bytes()).hexdigest(),
        "configuration_count": len(signal) * len(group),
        "fit_ranked": {name: items[:5] for name, items in ranked.items()},
        "calibration_finalists": finalists,
        "selected": selected,
        "production_candidate": None,
        "production_candidate_reason": "No calibration finalist reaches 65% bullish gross accuracy, so no subgroup passes the requested threshold before later diagnostics.",
        "diagnostics": {},
    }
    if selected:
        name = selected["configuration"]["signal"]
        subgroup = selected["configuration"]["subgroup"]
        mask = signal[name] & group[subgroup]
        for cohort in SOURCES:
            for year in (2024, 2025, 2026):
                slice_ = events.loc[events["cohort"].eq(cohort) & events["year"].eq(year)]
                report["diagnostics"][f"{cohort}_{year}"] = detailed(slice_.loc[mask.loc[slice_.index]])
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"selected": selected, "diagnostics": report["diagnostics"]}, ensure_ascii=False, indent=2))
    print("wrote", OUT)


if __name__ == "__main__":
    main()
