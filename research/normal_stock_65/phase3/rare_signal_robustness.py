#!/usr/bin/env python3
"""Development-only date-cluster audit of the rare-signal search leader."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np

from rare_signal_search import HERE, SOURCES, INDEX, nonoverlap, prepare


def main() -> None:
    frame = prepare(True)
    candidate = (
        frame["open_gap"].ge(.01)
        & frame["index_close_position"].le(.2)
        & frame["exchange"].eq("SZ")
    )
    chosen = nonoverlap(frame.loc[candidate & frame["year"].between(2024, 2026)])
    by_date = chosen.groupby("entry_date")["up_correct"].agg(["sum", "count"])
    by_date = by_date.sort_values(["count", "sum"], ascending=False)
    top = []
    for date, row in by_date.head(10).iterrows():
        top.append({"entry_date": date.strftime("%Y-%m-%d"),
                    "correct": int(row["sum"]), "predictions": int(row["count"])})
    ablation = {}
    for n in (0, 1, 2, 3, 5, 10):
        remaining = by_date.iloc[n:]
        wins, count = int(remaining["sum"].sum()), int(remaining["count"].sum())
        ablation[str(n)] = {"correct": wins, "predictions": count,
                            "accuracy": round(wins / count, 5) if count else None}
    wins = by_date["sum"].to_numpy(dtype=np.int64)
    counts = by_date["count"].to_numpy(dtype=np.int64)
    rng = np.random.default_rng(20260930)
    indices = rng.integers(0, len(by_date), size=(10000, len(by_date)))
    rates = wins[indices].sum(axis=1) / counts[indices].sum(axis=1)
    ci = np.quantile(rates, [.025, .5, .975]).tolist()
    fit = frame.loc[candidate & frame["cohort"].eq("development200")
                    & frame["year"].between(2020, 2023)]
    fit = nonoverlap(fit)
    out = {
        "development_only": True,
        "sealed_validation_read": False,
        "conditions": ["normal_universe_eligible", "risk_status = normal", "exchange = SZ",
                       "open_gap >= 0.01", "index_close_position <= 0.2"],
        "fit_years_observed": sorted(fit["year"].unique().astype(int).tolist()),
        "fit_correct": int(fit["up_correct"].sum()), "fit_predictions": len(fit),
        "exposed_diagnostic_years": [2024, 2025, 2026],
        "top_dates": top,
        "remove_n_busiest_dates": ablation,
        "date_cluster_bootstrap": {"resamples": 10000, "seed": 20260930,
                                   "percentile_95": [round(x, 5) for x in ci],
                                   "fraction_at_least_65_percent": round(float(np.mean(rates >= .65)), 5)},
        "source_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest()
                          for name, path in SOURCES.items()},
        "hs300_sha256": hashlib.sha256(INDEX.read_bytes()).hexdigest(),
    }
    output = HERE / "rare_signal_robustness.json"
    output.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
    print(output)
    print(json.dumps({"fit": [out["fit_correct"], out["fit_predictions"]],
                      "ablation": ablation, "date_cluster_bootstrap": out["date_cluster_bootstrap"]},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
