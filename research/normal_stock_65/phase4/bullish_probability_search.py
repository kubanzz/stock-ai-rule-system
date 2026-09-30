#!/usr/bin/env python3
"""Chronological development search for a selective T-close bullish signal.

The model learns whether T+2 open exceeds T+1 open. A quote that is absent at
either open counts as an incorrect bullish prediction. The sealed 100-stock
validation file is deliberately absent from the source list.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.experimental import enable_hist_gradient_boosting  # noqa: F401
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

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
BASE_FEATURES = [
    "rsi14", "return_1d", "return_2d", "change_pct_5d", "return_10d",
    "return_20d", "intraday_return", "open_gap", "close_position", "range_pct",
    "distance_ma5", "distance_ma20", "volume_ratio_5d", "amount_ratio20",
    "normal_volatility_20d", "macd_histogram",
]
HISTORY_FEATURES = ["lagged_stock_bullish_accuracy_252", "lagged_stock_history_n_252"]


def add_matured_bullish_history(events: pd.DataFrame) -> pd.DataFrame:
    """Compute past-stock accuracy using only outcomes matured by T close."""
    out = events.sort_values(["symbol", "trade_date"]).copy()
    out[HISTORY_FEATURES[0]] = np.nan
    out[HISTORY_FEATURES[1]] = 0.0
    for _, group in out.groupby("symbol", sort=False):
        signal_dates = group["trade_date"].to_numpy(dtype="datetime64[ns]")
        exit_dates = group["exit_date"].to_numpy(dtype="datetime64[ns]")
        mature_end = np.searchsorted(exit_dates, signal_dates, side="right")
        mature_start = np.maximum(mature_end - 252, 0)
        hits = group["up_correct"].to_numpy(dtype=np.int64)
        cumulative = np.r_[0, np.cumsum(hits)]
        n = mature_end - mature_start
        k = cumulative[mature_end] - cumulative[mature_start]
        accuracy = np.full(len(group), np.nan)
        eligible = n >= 25
        accuracy[eligible] = (k[eligible] + 25.0) / (n[eligible] + 50.0)
        out.loc[group.index, HISTORY_FEATURES[0]] = accuracy
        out.loc[group.index, HISTORY_FEATURES[1]] = n
    return out.sort_index()


def nonoverlapping(frame: pd.DataFrame) -> pd.DataFrame:
    """Allow a new signal for a stock only after the prior selected exit open."""
    keep = []
    last_exit = {}
    for row in frame.sort_values(["symbol", "entry_date", "trade_date"]).itertuples():
        previous = last_exit.get(row.symbol)
        if previous is None or row.entry_date > previous:
            keep.append(row.Index)
            last_exit[row.symbol] = row.exit_date
    return frame.loc[keep].copy()


def measure(frame: pd.DataFrame) -> dict:
    if frame.empty:
        return {
            "predictions": 0, "correct": 0, "accuracy": None,
            "wilson95_lower": None, "date_cluster_bootstrap95_lower": None,
            "symbols": 0, "entry_dates": 0, "top_date_share": None,
            "top_symbol_share": None, "unobservable_predictions": 0,
            "entry_unexecutable": 0, "long_net_win_rate": None,
        }
    n = len(frame)
    k = int(frame["up_correct"].sum())
    by_date = frame.groupby("entry_date")["up_correct"].agg(["sum", "count"])
    if len(by_date) > 1:
        rng = np.random.RandomState(20260930)
        sampled = rng.randint(0, len(by_date), size=(2000, len(by_date)))
        wins = by_date["sum"].to_numpy()[sampled].sum(axis=1)
        sizes = by_date["count"].to_numpy()[sampled].sum(axis=1)
        clustered = float(np.quantile(wins / sizes, .025))
    else:
        clustered = None
    executable = frame.loc[frame["entry_executable"]]
    net_win = executable["net_return"].gt(0) & ~executable["blocked_exit"]
    return {
        "predictions": n,
        "correct": k,
        "accuracy": round(k / n, 6),
        "wilson95_lower": round(wilson(k, n), 6),
        "date_cluster_bootstrap95_lower": round(clustered, 6) if clustered is not None else None,
        "symbols": int(frame["symbol"].nunique()),
        "entry_dates": int(frame["entry_date"].nunique()),
        "top_date_share": round(float(frame["entry_date"].value_counts(normalize=True).iloc[0]), 6),
        "top_symbol_share": round(float(frame["symbol"].value_counts(normalize=True).iloc[0]), 6),
        "unobservable_predictions": int((~frame["direction_observable"]).sum()),
        "entry_unexecutable": int((~frame["entry_executable"]).sum()),
        "long_net_win_rate": round(float(net_win.mean()), 6) if len(executable) else None,
    }


def summary(frame: pd.DataFrame) -> dict:
    return {"all": measure(frame), "nonoverlap": measure(nonoverlapping(frame))}


def eligible_calibration(result: dict) -> bool:
    metric = result["nonoverlap"]
    return (metric["predictions"] >= 100 and metric["entry_dates"] >= 30
            and metric["symbols"] >= 20 and metric["top_date_share"] <= .15)


def make_models() -> dict:
    return {
        "logistic_l2_1": make_pipeline(
            SimpleImputer(strategy="median"), StandardScaler(),
            LogisticRegression(C=1.0, max_iter=300, solver="lbfgs")),
        "hgb_leaf_7": make_pipeline(
            SimpleImputer(strategy="median"),
            HistGradientBoostingClassifier(
                max_iter=80, learning_rate=.05, max_leaf_nodes=7,
                min_samples_leaf=150, l2_regularization=5,
                random_state=20260930)),
        "hgb_leaf_15": make_pipeline(
            SimpleImputer(strategy="median"),
            HistGradientBoostingClassifier(
                max_iter=80, learning_rate=.05, max_leaf_nodes=15,
                min_samples_leaf=300, l2_regularization=10,
                random_state=20260930)),
    }


def main() -> None:
    frames = []
    for cohort, path in SOURCES.items():
        frame = event_frame(path)
        frame["cohort"] = cohort
        frames.append(frame)
    events = pd.concat(frames, ignore_index=True)
    events = add_matured_bullish_history(events)
    events = events.loc[events[BASE_FEATURES].notna().all(axis=1)].reset_index(drop=True)
    train = (events["cohort"].eq("phase2dev200")
             & events["trade_date"].ge("2020-01-01")
             & events["exit_date"].le("2023-12-31"))
    cal = (events["cohort"].eq("phase2dev200")
           & events["trade_date"].ge("2024-01-01")
           & events["exit_date"].le("2024-12-31"))
    y = events["up_correct"].to_numpy(dtype=np.uint8)
    search = []
    trained = {}
    for history in (False, True):
        features = BASE_FEATURES + (HISTORY_FEATURES if history else [])
        x_train = events.loc[train, features].to_numpy(dtype=np.float32)
        x_cal = events.loc[cal, features].to_numpy(dtype=np.float32)
        for model_name, model in make_models().items():
            name = model_name + ("_history" if history else "")
            model.fit(x_train, y[train.to_numpy()])
            score = model.predict_proba(x_cal)[:, 1]
            trained[name] = (model, features)
            for quantile in (.90, .95, .98, .99):
                cutoff = float(np.quantile(score, quantile))
                picked = events.loc[cal].loc[score >= cutoff]
                m = summary(picked)
                search.append({
                    "model": name, "quantile": quantile,
                    "probability_cutoff": cutoff,
                    "calibration": m,
                    "calibration_eligible": eligible_calibration(m),
                })
    valid = [s for s in search if s["calibration_eligible"]]
    selected = max(valid, key=lambda item: (
        item["calibration"]["nonoverlap"]["wilson95_lower"],
        item["calibration"]["nonoverlap"]["accuracy"],
        item["calibration"]["nonoverlap"]["predictions"],
    )) if valid else None
    report = {
        "protocol": "Fit phase2dev200 2020-23; choose one of 24 model/threshold configurations on phase2dev200 2024; freeze before 2025/26 diagnostics.",
        "outcome": "T+2 open > T+1 open; unobservable opens count as incorrect; no future tradeability filter in prediction denominator.",
        "caveat": "All three cohorts and 2025/26 are previously exposed by other searches; this is a chronological diagnostic, not independent validation.",
        "source_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in SOURCES.items()},
        "training_events": int(train.sum()),
        "calibration_events": int(cal.sum()),
        "model_features": {name: features for name, (_, features) in trained.items()},
        "search": search,
        "selected": {k: v for k, v in selected.items() if k != "calibration"} if selected else None,
        "baseline": {},
        "diagnostics": {},
    }
    if selected:
        model, features = trained[selected["model"]]
    for year in (2024, 2025, 2026):
        for cohort in SOURCES:
            key = f"{cohort}_{year}"
            mask = (events["cohort"].eq(cohort)
                    & events["trade_date"].dt.year.eq(year)
                    & events["exit_date"].dt.year.eq(year))
            part = events.loc[mask]
            report["baseline"][key] = summary(part)
            if selected:
                probabilities = model.predict_proba(part[features].to_numpy(dtype=np.float32))[:, 1]
                report["diagnostics"][key] = summary(part.loc[probabilities >= selected["probability_cutoff"]])
    output = HERE / "bullish_probability_results.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"selected": report["selected"], "diagnostics": report["diagnostics"]},
                     ensure_ascii=False, indent=2))
    print("wrote", output)


if __name__ == "__main__":
    main()
