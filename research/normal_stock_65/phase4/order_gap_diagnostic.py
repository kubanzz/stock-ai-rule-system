#!/usr/bin/env python3
"""Development-only T+1 opening gap diagnostic for one-day long execution.

Uses three already exposed stock cohorts. This looks for an execution-time
order gate, not a signal available at T close. Never accesses sealed stocks.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.phase2_search import development_events  # noqa: E402
from research.one_day_20260930.quant_research import nonoverlapping  # noqa: E402

HERE = Path(__file__).resolve().parent
BINS = [-np.inf, -.06, -.04, -.03, -.02, -.01, 0, .01, .02, .03, .04, .06, .095]


def summarize(frame: pd.DataFrame) -> dict:
    eligible = frame.loc[frame["entry_executable"]]
    chosen = nonoverlapping(eligible.copy())
    n = len(chosen)
    wins = int((chosen["net_return"].gt(0) & ~chosen["blocked_exit"]).sum())
    return {
        "signals_T_close": len(frame), "executed_nonoverlap": n,
        "net_wins": wins, "net_win_rate": round(wins / n, 5) if n else None,
        "mean_net_return": round(float(chosen["net_return"].mean()), 6) if n else None,
        "symbols": int(chosen["symbol"].nunique()),
        "entry_dates": int(chosen["entry_date"].nunique()),
        "top_date_share": round(float(chosen["entry_date"].value_counts(normalize=True).iloc[0]), 5) if n else None,
    }


def main() -> None:
    data = development_events()
    data = data.loc[data["trade_date"].dt.year.eq(data["exit_date"].dt.year)].copy()
    data["year"] = data["trade_date"].dt.year
    data["gap_bin"] = pd.cut(data["entry_gap"], BINS, include_lowest=True).astype(str)
    output = {
        "purpose": "development-only opening order filter diagnostic; no sealed stock read",
        "target": "T+1 open to T+2 open long net win, minus 30 bps, blocked exit loses",
        "bin_edges": [str(x) for x in BINS],
        "segments": {},
    }
    for year in range(2020, 2027):
        scope = data.loc[data["year"].eq(year)]
        output["segments"][str(year)] = {str(name): summarize(group)
                                         for name, group in scope.groupby("gap_bin", sort=True)}
        output["segments"][str(year)]["all"] = summarize(scope)
    (HERE / "order_gap_diagnostic.json").write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    for year, result in output["segments"].items():
        top = sorted(((x["net_win_rate"], k, x["executed_nonoverlap"])
                      for k, x in result.items() if k != "all" and x["executed_nonoverlap"] >= 30), reverse=True)
        print(year, "baseline", result["all"]["net_win_rate"], "best_bins", top[:3])


if __name__ == "__main__":
    main()
