#!/usr/bin/env python3
"""Development-only opening-gap conditional bullish direction experiment.

The T-close watchlist is causal. An order conditional on the *final* T+1 stock
or HS300 opening price cannot be guaranteed a fill at that same opening price;
results are a conditional-price upper bound, not an executable strategy.
The sealed 100-stock file is never loaded.
"""

from __future__ import annotations

import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.phase3 import rare_signal_search as rare  # noqa: E402
from research.one_day_20260930.quant_research import wilson  # noqa: E402

HERE = Path(__file__).resolve().parent
INDEX = ROOT / "research/normal_stock_65/phase4/hs300_unadjusted_baostock.csv"
OUT = HERE / "opening_auction_results.json"


@dataclass(frozen=True)
class Candidate:
    watchlist: str
    opening_filter: str


def prepare() -> pd.DataFrame:
    rare.INDEX = INDEX
    events = rare.prepare(True)
    index = pd.read_csv(INDEX, parse_dates=["trade_date"])
    index["market_entry_gap"] = index["open"] / index["pre_close"] - 1
    events = events.merge(
        index[["trade_date", "market_entry_gap"]].rename(columns={"trade_date": "entry_date"}),
        on="entry_date", how="left", validate="many_to_one",
    )
    events["relative_entry_gap"] = events["entry_gap"] - events["market_entry_gap"]
    return events.loc[events["year"].between(2020, 2026)].reset_index(drop=True)


def watchlist_masks(data: pd.DataFrame) -> dict[str, np.ndarray]:
    """Everything here is available at T close."""
    return {
        "all_normal": np.ones(len(data), dtype=bool),
        "t_down_2pct": data["return_1d"].le(-.02).to_numpy(bool),
        "t_down_3pct_weak_close": (data["return_1d"].le(-.03) & data["close_position"].le(.2)).to_numpy(bool),
        "t_up_2pct": data["return_1d"].ge(.02).to_numpy(bool),
        "t_market_down_1pct": data["index_return_1d"].le(-.01).to_numpy(bool),
        "t_market_weak_close": data["index_close_position"].le(.2).to_numpy(bool),
        "t_gap_up_market_weak_sz": (
            data["open_gap"].ge(.01) & data["index_close_position"].le(.2)
            & data["exchange"].eq("SZ")
        ).to_numpy(bool),
        "t_low_volatility": data["normal_volatility_20d"].le(.02).to_numpy(bool),
    }


def opening_masks(data: pd.DataFrame) -> dict[str, np.ndarray]:
    """These use *final* T+1 opening prints and are not same-open tradable."""
    g = data["entry_gap"]
    m = data["market_entry_gap"]
    r = data["relative_entry_gap"]
    masks = {
        "open_any": pd.Series(True, index=data.index),
        "stock_gap_-2_to_0pct": g.ge(-.02) & g.lt(0),
        "stock_gap_0_to_2pct": g.ge(0) & g.lt(.02),
        "stock_gap_2_to_4pct": g.ge(.02) & g.lt(.04),
        "stock_gap_-4_to_-2pct": g.ge(-.04) & g.lt(-.02),
        "market_gap_negative": m.lt(0),
        "market_gap_positive": m.ge(0),
        "stock_up_market_down": g.ge(.01) & m.lt(0),
        "stock_down_market_up": g.le(-.01) & m.gt(0),
        "stock_up_2_market_nonpositive": g.ge(.02) & m.le(0),
        "stock_down_2_market_nonnegative": g.le(-.02) & m.ge(0),
        "stock_gap_0_to_2_market_down": g.ge(0) & g.lt(.02) & m.lt(0),
    }
    for threshold in (-.04, -.03, -.02, -.01):
        masks[f"stock_gap_le_{threshold:.2f}"] = g.le(threshold)
        masks[f"relative_gap_le_{threshold:.2f}"] = r.le(threshold)
    for threshold in (.01, .02, .03, .04):
        masks[f"stock_gap_ge_{threshold:.2f}"] = g.ge(threshold)
        masks[f"relative_gap_ge_{threshold:.2f}"] = r.ge(threshold)
    return {name: mask.fillna(False).to_numpy(bool) for name, mask in masks.items()}


def nonoverlap(frame: pd.DataFrame) -> pd.DataFrame:
    return rare.nonoverlap(frame)


def measure(frame: pd.DataFrame, bootstrap: bool = False) -> dict:
    chosen = nonoverlap(frame)
    n = len(chosen)
    k = int(chosen["up_correct"].sum())
    date_counts = chosen["entry_date"].value_counts() if n else pd.Series(dtype=int)
    net_wins = int((chosen["net_return"].gt(0) & ~chosen["blocked_exit"]).sum())
    out = {
        "predictions": n, "correct": k, "gross_direction_accuracy": round(k / n, 6) if n else None,
        "wilson95_lower": round(wilson(k, n), 6) if n else None,
        "symbols": int(chosen["symbol"].nunique()),
        "entry_dates": int(chosen["entry_date"].nunique()),
        "top_date_share": round(float(date_counts.iloc[0] / n), 6) if n else None,
        "top_symbol_share": round(float(chosen["symbol"].value_counts().iloc[0] / n), 6) if n else None,
        "unobservable_exit": int((~chosen["exit_price_known"]).sum()),
        "net_long_wins_30bps_proxy": net_wins,
        "net_long_win_rate_30bps_proxy": round(net_wins / n, 6) if n else None,
        "mean_net_return_30bps_proxy": round(float(chosen["net_return"].mean()), 6) if n else None,
        "year_counts": {str(y): int(v) for y, v in chosen.groupby("year").size().items()},
        "year_accuracy": {str(y): round(float(group["up_correct"].mean()), 6)
                          for y, group in chosen.groupby("year")},
    }
    if bootstrap and n:
        grouped = chosen.groupby("entry_date")["up_correct"].agg(["sum", "count"])
        if len(grouped) > 1:
            rng = np.random.default_rng(20260930)
            sampled = rng.integers(0, len(grouped), size=(3000, len(grouped)))
            ratio = grouped["sum"].to_numpy()[sampled].sum(axis=1) / grouped["count"].to_numpy()[sampled].sum(axis=1)
            out["date_cluster_bootstrap95"] = [round(float(x), 6) for x in np.quantile(ratio, [.025, .975])]
        busiest = date_counts.index[0]
        remaining = chosen.loc[chosen["entry_date"].ne(busiest)]
        out["remove_busiest_date"] = {"predictions": len(remaining),
                                      "gross_direction_accuracy": round(float(remaining["up_correct"].mean()), 6)
                                      if len(remaining) else None}
    return out


def fit_score(frame: pd.DataFrame) -> tuple[float, dict] | None:
    m = measure(frame)
    if (m["predictions"] < 200 or m["entry_dates"] < 60 or m["symbols"] < 25
            or m["top_date_share"] > .15):
        return None
    annual = [(m["year_accuracy"].get(str(y)), m["year_counts"].get(str(y), 0)) for y in range(2020, 2024)]
    if sum(n >= 20 for _, n in annual) < 3:
        return None
    stable = min(float(rate) for rate, n in annual if n >= 20)
    score = min(m["wilson95_lower"], .5 * (m["wilson95_lower"] + stable))
    return score, m


def search_precommitted_limits(events: pd.DataFrame, watchlists: dict[str, np.ndarray]) -> dict:
    """T-close buy limits; next-open fill is only a conservative daily proxy."""
    cohort = events["cohort"].eq("development200").to_numpy(bool)
    fit = cohort & events["year"].between(2020, 2023).to_numpy(bool)
    cal = cohort & events["year"].eq(2024).to_numpy(bool)
    gap = events["entry_gap"]
    valid_open = events["entry_price_known"].to_numpy(bool)
    limits = [-.04, -.03, -.02, -.01, 0, .01, .02]
    gates = {f"limit_at_T_close_{pct:+.0%}":
             (gap.le(pct).fillna(False).to_numpy(bool) & valid_open)
             for pct in limits}
    ranked = []
    for watch_name, watch in watchlists.items():
        for gate_name, gate in gates.items():
            result = fit_score(events.loc[fit & watch & gate])
            if result:
                score, metric = result
                ranked.append({"candidate": Candidate(watch_name, gate_name).__dict__,
                               "fit_score": round(score, 6), "fit": metric})
    ranked.sort(key=lambda row: (row["fit_score"], row["fit"]["predictions"]), reverse=True)
    finalists = []
    for watch_name in watchlists:
        for row in [r for r in ranked if r["candidate"]["watchlist"] == watch_name][:3]:
            c = row["candidate"]
            m = measure(events.loc[cal & watchlists[c["watchlist"]] & gates[c["opening_filter"]]])
            if (m["predictions"] >= 100 and m["entry_dates"] >= 30
                    and m["symbols"] >= 20 and m["top_date_share"] <= .15):
                finalists.append({**row, "calibration": m})
    finalists.sort(key=lambda row: (row["calibration"]["wilson95_lower"], row["fit_score"]), reverse=True)
    chosen = finalists[0] if finalists else None
    diagnostics = {}
    if chosen:
        c = chosen["candidate"]
        mask = watchlists[c["watchlist"]] & gates[c["opening_filter"]]
        for label in rare.SOURCES:
            for year in (2024, 2025, 2026):
                scope = events["cohort"].eq(label).to_numpy(bool) & events["year"].eq(year).to_numpy(bool)
                diagnostics[f"{label}_{year}"] = measure(events.loc[scope & mask], bootstrap=True)
        for year in (2025, 2026):
            scope = events["year"].eq(year).to_numpy(bool)
            diagnostics[f"pooled_{year}"] = measure(events.loc[scope & mask], bootstrap=True)
        diagnostics["pooled_2025_26"] = measure(events.loc[events["year"].between(2025, 2026).to_numpy(bool) & mask], bootstrap=True)
    return {
        "causal_order": "After T close, commit buy limit at T close times (1 + threshold) for every T-close-watchlist stock. Assume a buy fills at T+1 clearing open only if observed open <= limit and positive volume; actual auction priority/fill unverified.",
        "fit_ranked_top15": ranked[:15], "calibration_finalists": finalists,
        "selected": chosen, "diagnostics": diagnostics,
        "production_candidate": None,
    }


def main() -> None:
    events = prepare()
    watchlists = watchlist_masks(events)
    gates = opening_masks(events)
    # The observable open is necessary to define the gate. Gap < +9.5% is
    # only a crude mainboard limit-up exclusion, not proof of a market fill.
    opening_price_proxy = events["entry_price_known"].to_numpy(bool) & events["entry_gap"].lt(.095).fillna(False).to_numpy(bool)
    cohort = events["cohort"].eq("development200").to_numpy(bool)
    fit = cohort & events["year"].between(2020, 2023).to_numpy(bool)
    cal = cohort & events["year"].eq(2024).to_numpy(bool)
    leaderboard = []
    examined = 0
    for watch_name, watch in watchlists.items():
        for gate_name, gate in gates.items():
            examined += 1
            chosen = fit & watch & gate & opening_price_proxy
            ranked = fit_score(events.loc[chosen])
            if ranked is not None:
                score, metric = ranked
                leaderboard.append({"candidate": Candidate(watch_name, gate_name).__dict__,
                                    "fit_score": round(score, 6), "fit": metric})
    leaderboard.sort(key=lambda row: (row["fit_score"], row["fit"]["predictions"]), reverse=True)
    finalists = []
    for watch_name in watchlists:
        # A maximum of three fit-ranked opening gates from each T-close
        # watchlist reach calibration; no later cohort selects the gate.
        watch_top = [row for row in leaderboard if row["candidate"]["watchlist"] == watch_name][:3]
        for row in watch_top:
            c = row["candidate"]
            mask = cal & watchlists[c["watchlist"]] & gates[c["opening_filter"]] & opening_price_proxy
            m = measure(events.loc[mask])
            if (m["predictions"] >= 100 and m["entry_dates"] >= 30
                    and m["symbols"] >= 20 and m["top_date_share"] <= .15):
                finalists.append({**row, "calibration": m})
    finalists.sort(key=lambda row: (row["calibration"]["wilson95_lower"],
                                     row["fit_score"]), reverse=True)
    selected = finalists[0] if finalists else None
    diagnostics = {}
    if selected:
        c = selected["candidate"]
        chosen = watchlists[c["watchlist"]] & gates[c["opening_filter"]] & opening_price_proxy
        for label in rare.SOURCES:
            for year in (2024, 2025, 2026):
                scope = events["cohort"].eq(label).to_numpy(bool) & events["year"].eq(year).to_numpy(bool)
                diagnostics[f"{label}_{year}"] = measure(events.loc[scope & chosen], bootstrap=True)
        for year in (2025, 2026):
            scope = events["year"].eq(year).to_numpy(bool)
            diagnostics[f"pooled_{year}"] = measure(events.loc[scope & chosen], bootstrap=True)
        diagnostics["pooled_2025_26"] = measure(events.loc[events["year"].between(2025, 2026).to_numpy(bool) & chosen], bootstrap=True)
    report = {
        "purpose": "development-only T+1 final-opening-price conditional upper bound for bullish gross direction",
        "causal_warning": "The final T+1 stock/HS300 open is observable after auction clearing. Conditioning on it then claiming a fill at that same opening price is not causally executable. All such accuracy numbers are theoretical diagnostics; 30bps cost and limit proxy do not repair timing.",
        "label": "strict T+2 stock open > T+1 stock open; missing exit counted wrong",
        "watchlist_timing": "T close", "opening_filter_timing": "T+1 final opening prints",
        "normal_filter": "mainboard_liquid_stable_v1 plus T risk_status=normal",
        "source_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in rare.SOURCES.items()},
        "hs300_sha256": hashlib.sha256(INDEX.read_bytes()).hexdigest(),
        "sealed_validation_access": "none", "candidate_count": examined,
        "fit": "phase2 development200, 2020-23", "calibration": "phase2 development200, 2024",
        "diagnostic": "three previously exposed cohorts, 2025-26",
        "opening_price_proxy_at_fit": int((fit & opening_price_proxy).sum()),
        "fit_ranked_top15": leaderboard[:15],
        "calibration_finalists": finalists,
        "selected": selected, "diagnostics": diagnostics,
        "production_candidate": None,
        "production_candidate_reason": "Same-open execution is not proven for a final-open-conditioned filter, regardless of gross directional accuracy.",
        "precommitted_limit_search": search_precommitted_limits(events, watchlists),
    }
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("examined", examined, "fit_eligible", len(leaderboard), "calibration_finalists", len(finalists))
    if selected:
        print("selected", selected["candidate"], selected["fit"]["gross_direction_accuracy"],
              selected["calibration"]["gross_direction_accuracy"])
        for key, value in diagnostics.items():
            print(key, value["correct"], "/", value["predictions"], value["gross_direction_accuracy"])
    limit_selected = report["precommitted_limit_search"]["selected"]
    if limit_selected:
        print("precommitted_limit_selected", limit_selected["candidate"],
              limit_selected["calibration"]["gross_direction_accuracy"])
        for key, value in report["precommitted_limit_search"]["diagnostics"].items():
            print("limit", key, value["correct"], "/", value["predictions"], value["gross_direction_accuracy"])
    print("wrote", OUT)


if __name__ == "__main__":
    main()
