#!/usr/bin/env python3
"""Development-only chronological probability search for a T-close long signal.

The sealed phase2/validation_sealed_qfq.csv is deliberately absent from SOURCES.
Fit uses phase2 development 2020-23. The entire 2024 phase2 development year
chooses one model/threshold, which is then held fixed for 2025/26 diagnostics on
all three previously exposed cohorts. All figures are research evidence only.
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


def add_lagged_stock_win_feature(events: pd.DataFrame) -> pd.DataFrame:
    """Use only outcomes whose scheduled exit opening is no later than signal T.

    For each current T, the prior 252 eligible signals for the same stock are
    considered; executable attempts alone enter the historical rate. This is
    a streaming/live-available statistic, including validation-era outcomes
    only after they have matured. Cohorts have disjoint symbols by protocol.
    """
    out = events.sort_values(["symbol", "trade_date"]).copy()
    out["lagged_stock_net_win_252"] = np.nan
    out["lagged_stock_executable_n_252"] = 0.0
    for _, group in out.groupby("symbol", sort=False):
        t = group["trade_date"].values.astype("datetime64[ns]")
        exit_t = group["exit_date"].values.astype("datetime64[ns]")
        end = np.searchsorted(exit_t, t, side="right")
        start = np.maximum(end - 252, 0)
        exe = group["entry_executable"].to_numpy(dtype=np.int64)
        win = (exe.astype(bool) & group["net_return"].gt(0).to_numpy() &
               ~group["blocked_exit"].to_numpy()).astype(np.int64)
        cum_e = np.r_[0, np.cumsum(exe)]
        cum_w = np.r_[0, np.cumsum(win)]
        n = cum_e[end] - cum_e[start]
        w = cum_w[end] - cum_w[start]
        # Fixed prior is intentionally conservative and independent of
        # validation outcomes. A stock needs 25 matured attempts before use.
        rate = np.where(n >= 25, (w + 20.0) / (n + 50.0), np.nan)
        out.loc[group.index, "lagged_stock_net_win_252"] = rate
        out.loc[group.index, "lagged_stock_executable_n_252"] = n
    return out.sort_index()


def nonoverlapping(frame: pd.DataFrame) -> pd.DataFrame:
    """Keep first event per stock while prior selected position is open."""
    chosen = []
    last_exit = {}
    for row in frame.sort_values(["symbol", "entry_date", "trade_date"]).itertuples():
        previous = last_exit.get(row.symbol)
        if previous is None or row.entry_date > previous:
            chosen.append(row.Index)
            last_exit[row.symbol] = row.exit_date
    return frame.loc[chosen].copy()


def metrics(frame: pd.DataFrame) -> dict:
    chosen = nonoverlapping(frame)
    executable = chosen.loc[chosen["entry_executable"]]
    win = executable["net_return"].gt(0) & ~executable["blocked_exit"]
    n = len(executable)
    k = int(win.sum())
    by_date = executable.assign(_win=win.to_numpy()).groupby("entry_date")["_win"].agg(["sum", "count"])
    # Fixed-seed date-cluster resampling. This addresses common market days,
    # not two-way stock/date dependence and does not make a selected result
    # independent of the searched configurations.
    if len(by_date) >= 2:
        rng = np.random.RandomState(20260930)
        sampled = rng.randint(0, len(by_date), size=(2000, len(by_date)))
        wins = by_date["sum"].to_numpy()[sampled].sum(axis=1)
        counts = by_date["count"].to_numpy()[sampled].sum(axis=1)
        cluster_lower = float(np.quantile(wins / counts, .025))
    else:
        cluster_lower = None
    return {
        "signals": len(chosen), "executable_trades": n, "net_wins": k,
        "net_win_rate": round(k / n, 6) if n else None,
        "wilson95_lower": round(wilson(k, n), 6),
        "date_cluster_bootstrap95_lower": round(cluster_lower, 6) if cluster_lower is not None else None,
        "mean_net_return": round(float(executable["net_return"].mean()), 6) if n else None,
        "symbols": int(chosen["symbol"].nunique()),
        "entry_dates": int(chosen["entry_date"].nunique()),
        "top_date_share": round(float(chosen["entry_date"].value_counts(normalize=True).iloc[0]), 6) if len(chosen) else None,
        "unexecutable_signals": int((~chosen["entry_executable"]).sum()),
        "blocked_exits": int(executable["blocked_exit"].sum()),
    }


def qualifies(m: dict) -> bool:
    return m["executable_trades"] >= 100 and m["entry_dates"] >= 30 and m["symbols"] >= 20


def make_models() -> dict:
    return {
        "logistic_l2_1": make_pipeline(SimpleImputer(strategy="median"), StandardScaler(),
                                        LogisticRegression(C=1.0, max_iter=300, solver="lbfgs")),
        "hgb_leaf_7": make_pipeline(SimpleImputer(strategy="median"),
                                    HistGradientBoostingClassifier(max_iter=80, learning_rate=.05,
                                                                   max_leaf_nodes=7, min_samples_leaf=150,
                                                                   l2_regularization=5, random_state=20260930)),
        "hgb_leaf_15": make_pipeline(SimpleImputer(strategy="median"),
                                     HistGradientBoostingClassifier(max_iter=80, learning_rate=.05,
                                                                    max_leaf_nodes=15, min_samples_leaf=300,
                                                                    l2_regularization=10, random_state=20260930)),
    }


def main() -> None:
    frames = []
    for cohort, path in SOURCES.items():
        frame = event_frame(path)
        frame["cohort"] = cohort
        frames.append(frame)
    events = pd.concat(frames, ignore_index=True)
    events = add_lagged_stock_win_feature(events)
    events = events.loc[events[BASE_FEATURES].notna().all(axis=1)].reset_index(drop=True)
    train = events["cohort"].eq("phase2dev200") & events["exit_date"].le("2023-12-31")
    # Training requires signal no earlier than 2020; 2019 is a warmup year.
    train &= events["trade_date"].ge("2020-01-01")
    cal = events["cohort"].eq("phase2dev200") & events["trade_date"].ge("2024-01-01") & events["exit_date"].le("2024-12-31")
    y = (events["entry_executable"] & events["net_return"].gt(0) & ~events["blocked_exit"]).to_numpy(dtype=np.uint8)
    search = []
    predictions = {}
    for add_history in (False, True):
        features = BASE_FEATURES + (["lagged_stock_net_win_252", "lagged_stock_executable_n_252"] if add_history else [])
        x_train = events.loc[train, features].to_numpy(dtype=np.float32)
        x_cal = events.loc[cal, features].to_numpy(dtype=np.float32)
        for name, model in make_models().items():
            model.fit(x_train, y[train.to_numpy()])
            cal_score = model.predict_proba(x_cal)[:, 1]
            key = f"{name}{'_history' if add_history else ''}"
            predictions[key] = (model, features)
            for quantile in (.90, .95, .98, .99):
                cutoff = float(np.quantile(cal_score, quantile))
                chosen = events.loc[cal].loc[cal_score >= cutoff]
                m = metrics(chosen)
                search.append({"model": key, "quantile": quantile, "probability_cutoff": cutoff,
                               "calibration": m, "calibration_eligible": qualifies(m)})
    # One selection, frozen before any 2025/26 outcome inspection below.
    eligible = [s for s in search if s["calibration_eligible"]]
    selected = max(eligible, key=lambda s: (s["calibration"]["wilson95_lower"],
                                            s["calibration"]["net_win_rate"],
                                            s["calibration"]["executable_trades"])) if eligible else None
    report = {
        "protocol": "fit phase2dev200 2020-23; choose one of 24 model/threshold configurations on phase2dev200 2024; freeze before 2025/26 diagnostics",
        "caveat": "All three cohorts and 2025/26 were previously exposed in other research. Diagnostics are chronological holdouts for this algorithm, not independent sealed validation.",
        "sources_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in SOURCES.items()},
        "training_events": int(train.sum()), "calibration_events": int(cal.sum()),
        "candidate_features": {key: item[1] for key, item in predictions.items()},
        "search_count": len(search), "search": search,
        "selected": {k: v for k, v in selected.items() if k != "calibration"} if selected else None,
        "baseline": {}, "diagnostics": {},
    }
    if selected is not None:
        model, features = predictions[selected["model"]]
    for year in (2024, 2025, 2026):
        for cohort in SOURCES:
            key = f"{cohort}_{year}"
            segment = events["cohort"].eq(cohort) & events["trade_date"].dt.year.eq(year) & events["exit_date"].dt.year.eq(year)
            part = events.loc[segment]
            report["baseline"][key] = metrics(part)
            if selected is not None:
                probability = model.predict_proba(part[features].to_numpy(dtype=np.float32))[:, 1]
                selected_part = part.loc[probability >= selected["probability_cutoff"]]
                report["diagnostics"][key] = metrics(selected_part)
    HERE.mkdir(parents=True, exist_ok=True)
    output = HERE / "probabilistic_search_results.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"selected": report["selected"], "diagnostics": report["diagnostics"]}, ensure_ascii=False, indent=2))
    print("wrote", output)


if __name__ == "__main__":
    main()
