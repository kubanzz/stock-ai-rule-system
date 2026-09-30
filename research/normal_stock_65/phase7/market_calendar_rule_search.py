"""Pre-specified market-calendar states for the normal-stock bullish study.

This is a bounded diagnostic, not a parameter search.  It reads only the
already frozen phase-6 (2010--2018) and phase-5 (2019--2026) event frames and
the corresponding historical HS300 daily index files.  No sealed100 quote or
outcome file is imported.  Every state below is defined before reading the
event outcomes and uses only the index values available on signal day T (or
earlier rolling values).

The report deliberately keeps candidates that are close to 65 percent so that
they can be reviewed, while classifying unstable candidates as research-only.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import pandas as pd

from research.normal_stock_65.phase3.consensus_group_search import nonoverlap
from research.normal_stock_65.phase5.stress_frozen_vote import event_frame as recent_events
from research.normal_stock_65.phase6.stress_older_fixed_vote import event_frame as older_events
from research.one_day_20260930.quant_research import wilson

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PHASE5 = ROOT / "research/normal_stock_65/phase5"
PHASE6 = ROOT / "research/normal_stock_65/phase6"

# The periods are fixed before looking at the result table.  They provide a
# development-era view and two later regime checks; 2019 is reported with the
# recent PIT cohort but is not used to call a candidate production ready.
PERIODS: dict[str, tuple[int, int]] = {
    "2011-2016_development": (2011, 2016),
    "2017-2018_validation": (2017, 2018),
    "2020-2023_validation": (2020, 2023),
    "2024-2026_validation": (2024, 2026),
    "2011-2026_combined": (2011, 2026),
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_index() -> pd.DataFrame:
    """Load the two frozen HS300 files and derive T-available states."""
    older_path = PHASE6 / "hs300_older_unadjusted_baostock.csv"
    recent_path = ROOT / "research/normal_stock_65/phase4/hs300_unadjusted_baostock.csv"
    older = pd.read_csv(older_path, parse_dates=["trade_date"])
    recent = pd.read_csv(recent_path, parse_dates=["trade_date"])
    index = pd.concat([older, recent], ignore_index=True).drop_duplicates("trade_date")
    index = index.sort_values("trade_date").reset_index(drop=True)
    if index.duplicated("trade_date").any():
        raise ValueError("duplicate index dates")
    if not index["trade_status"].eq(1).all():
        raise ValueError("non-trading index row in frozen calendar")
    if (index[["open", "high", "low", "close"]].le(0).any().any()
            or index["low"].gt(index["high"]).any()
            or index["low"].gt(index[["open", "close"]].min(axis=1)).any()
            or index["high"].lt(index[["open", "close"]].max(axis=1)).any()):
        raise ValueError("invalid frozen index OHLC")

    close = index["close"]
    index["index_1d_return"] = close / index["pre_close"] - 1
    index["index_3d_return"] = close / close.shift(3) - 1
    index["index_5d_return"] = close / close.shift(5) - 1
    index["index_20d_return"] = close / close.shift(20) - 1
    index["index_close_position"] = ((close - index["low"])
                                      / (index["high"] - index["low"]).replace(0, np.nan))
    index["index_20d_ma_gap"] = close / close.rolling(20).mean() - 1
    index["index_20d_volatility"] = index["index_1d_return"].rolling(20).std()
    return index


def load_events() -> pd.DataFrame:
    """Use frozen event builders; they validate their own source manifests."""
    older = older_events()
    recent = recent_events()
    data = pd.concat([older, recent], ignore_index=True)
    if data.duplicated(["symbol", "trade_date"]).any():
        raise ValueError("duplicate event rows")
    data["fixed_vote_2of3"] = (
        data["change_pct_5d"].le(-0.10).fillna(False).astype(int)
        + data["open_gap"].ge(0.02).fillna(False).astype(int)
        + data["index_close_position"].le(0.20).fillna(False).astype(int)
        >= 2
    )
    return data


def date_cluster_lower(frame: pd.DataFrame) -> float | None:
    """Bootstrap complete entry dates so same-day stock returns stay linked."""
    if frame.empty or frame["entry_date"].nunique() < 10:
        return None
    daily = frame.groupby("entry_date")["up_correct"].agg(["sum", "size"]).to_numpy()
    rng = np.random.default_rng(20260930)
    draws = rng.integers(0, len(daily), size=(2_000, len(daily)))
    sampled = daily[draws]
    rates = sampled[:, :, 0].sum(axis=1) / sampled[:, :, 1].sum(axis=1)
    return round(float(np.quantile(rates, 0.025)), 5)


def metric(frame: pd.DataFrame, denominator: int) -> dict:
    raw_n = len(frame)
    chosen = nonoverlap(frame)
    n = len(chosen)
    wins = int(chosen["up_correct"].sum()) if n else 0
    executable = chosen.loc[chosen["entry_executable"]]
    net_wins = int((executable["net_return"].gt(0) & ~executable["blocked_exit"]).sum())
    return {
        "raw_predictions": raw_n,
        "predictions_nonoverlap": n,
        "correct": wins,
        "direction_accuracy": round(wins / n, 5) if n else None,
        "wilson95_lower": round(wilson(wins, n), 5) if n else None,
        "date_cluster95_lower": date_cluster_lower(chosen),
        "eligible_denominator": int(denominator),
        "coverage": round(raw_n / denominator, 6) if denominator else None,
        "symbols": int(chosen["symbol"].nunique()) if n else 0,
        "entry_dates": int(chosen["entry_date"].nunique()) if n else 0,
        "top_entry_date_share": round(float(chosen["entry_date"].value_counts(normalize=True).iloc[0]), 5)
        if n else None,
        "unobservable_counted_wrong": int((~chosen["direction_observable"]).sum()) if n else 0,
        "executable_net_predictions": len(executable),
        "executable_net_wins": net_wins,
        "executable_net_win_rate": round(net_wins / len(executable), 5) if len(executable) else None,
        "mean_net_return": round(float(executable["net_return"].mean()), 6) if len(executable) else None,
    }


def matched_date_comparison(data: pd.DataFrame, selected: pd.DataFrame) -> dict:
    """Compare each selected stock with normal stocks on the same entry date.

    The all-stock baseline is descriptive and includes the signal stock.  The
    other-stock comparison removes every selected stock on that date.  The
    latter is unavailable for market-only conditions that select all stocks
    on the triggering date, and is reported as such rather than fabricated.
    """
    # The main result uses non-overlapping holdings.  For controls, remove all
    # raw candidate symbols on each entry date, including signals dropped by
    # the no-overlap convention; otherwise dropped signals would be counted as
    # controls and make the same-date comparison optimistic.
    chosen = nonoverlap(selected)
    if chosen.empty:
        return {"matched_signal_count": 0, "matched_entry_dates": 0,
                "all_stock_baseline": None, "all_stock_lift_pp": None,
                "other_stock_comparable_signals": 0,
                "other_stock_baseline": None, "other_stock_lift_pp": None}
    daily = data.groupby("entry_date")["up_correct"].agg(["sum", "size"])
    chosen = chosen[["symbol", "entry_date", "up_correct"]].copy()
    chosen["day_wins"] = chosen["entry_date"].map(daily["sum"])
    chosen["day_size"] = chosen["entry_date"].map(daily["size"])
    chosen["baseline"] = chosen["day_wins"] / chosen["day_size"]
    raw_signal_pairs = selected[["symbol", "entry_date"]].drop_duplicates()
    signal_daily = raw_signal_pairs.groupby("entry_date")["symbol"].size().rename("size")
    # Raw candidate wins are needed to remove the candidate stocks from the
    # all-stock numerator.  Reindexing preserves dates with no raw hit.
    raw_wins = selected.groupby("entry_date")["up_correct"].sum()
    chosen["signal_day_wins"] = chosen["entry_date"].map(raw_wins).fillna(0)
    chosen["signal_day_size"] = chosen["entry_date"].map(signal_daily).fillna(0)
    chosen["other_size"] = chosen["day_size"] - chosen["signal_day_size"]
    comparable = chosen.loc[chosen["other_size"].gt(0)].copy()
    if len(comparable):
        comparable["other_baseline"] = (
            (comparable["day_wins"] - comparable["signal_day_wins"])
            / comparable["other_size"]
        )
    return {
        "matched_signal_count": len(chosen),
        "matched_entry_dates": int(chosen["entry_date"].nunique()),
        "all_stock_baseline": round(float(chosen["baseline"].mean()), 5),
        "all_stock_lift_pp": round(float((chosen["up_correct"] - chosen["baseline"]).mean() * 100), 3),
        "other_stock_comparable_signals": len(comparable),
        "other_stock_baseline": round(float(comparable["other_baseline"].mean()), 5)
        if len(comparable) else None,
        "other_stock_lift_pp": round(float((comparable["up_correct"] - comparable["other_baseline"])
                                     .mean() * 100), 3) if len(comparable) else None,
    }


def candidate_masks(data: pd.DataFrame) -> dict[str, tuple[str, pd.Series]]:
    """Return the small, round-threshold candidate set fixed in this file."""
    # State names and thresholds are intentionally not generated from outcomes.
    # Each condition is observable by the T close, and rolling denominators end
    # at T.  The stock vote itself was fixed in phase 4 and is only a comparator.
    state = {
        "market_close_position_le_20pct": data["market_close_position"].le(0.20),
        "market_1d_return_le_minus_1pct": data["market_1d_return"].le(-0.01),
        "market_3d_return_le_minus_3pct": data["market_3d_return"].le(-0.03),
        "market_5d_return_le_minus_5pct": data["market_5d_return"].le(-0.05),
        "market_20d_return_le_minus_10pct": data["market_20d_return"].le(-0.10),
        "market_below_20d_ma_by_3pct": data["market_20d_ma_gap"].le(-0.03),
        "market_5d_down_and_close_position_low": (
            data["market_5d_return"].le(-0.05) & data["market_close_position"].le(0.20)
        ),
        "market_5d_down_and_below_20d_ma": (
            data["market_5d_return"].le(-0.05) & data["market_20d_ma_gap"].le(-0.03)
        ),
        "market_5d_down_and_20d_vol_le_2pct": (
            data["market_5d_return"].le(-0.05) & data["market_20d_volatility"].le(0.02)
        ),
    }
    masks: dict[str, tuple[str, pd.Series]] = {}
    for name, mask in state.items():
        masks[f"state_only__{name}"] = (f"{name}", mask)
        masks[f"fixed_vote_plus__{name}"] = (
            f"fixed_vote_2of3 AND {name}", mask & data["fixed_vote_2of3"]
        )
    masks["fixed_vote_2of3"] = (
        "fixed_vote_2of3: change_pct_5d<=-10%, open_gap>=2%, HS300 close position<=20%; at least 2",
        data["fixed_vote_2of3"],
    )
    return masks


def classify(period_results: dict[str, dict]) -> str:
    combined = period_results["2011-2026_combined"]
    dev = period_results["2011-2016_development"]
    validations = [period_results[key] for key in (
        "2017-2018_validation", "2020-2023_validation", "2024-2026_validation"
    )]
    if combined["direction_accuracy"] is None:
        return "insufficient_support"
    if all(item["direction_accuracy"] is not None and item["direction_accuracy"] >= 0.65
           and item["entry_dates"] >= 30 for item in validations):
        return "research_candidate_requires_independent_forward_test"
    if (dev["direction_accuracy"] is not None and dev["direction_accuracy"] >= 0.65
            and combined["predictions_nonoverlap"] >= 100):
        return "development_high_only_do_not_deploy"
    if combined["direction_accuracy"] >= 0.60:
        return "near_65_but_unstable_do_not_deploy"
    return "below_target_do_not_deploy"


def main() -> None:
    index = load_index()
    data = load_events()
    index = index.rename(columns={
        "index_close_position": "market_close_position",
        "index_1d_return": "market_1d_return",
        "index_3d_return": "market_3d_return",
        "index_5d_return": "market_5d_return",
        "index_20d_return": "market_20d_return",
        "index_20d_ma_gap": "market_20d_ma_gap",
        "index_20d_volatility": "market_20d_volatility",
    })
    data = data.merge(
        index[["trade_date", "market_close_position", "market_1d_return", "market_3d_return",
               "market_5d_return", "market_20d_return", "market_20d_ma_gap", "market_20d_volatility"]],
        on="trade_date", how="left", validate="many_to_one",
    )
    masks = candidate_masks(data)
    report: dict = {
        "purpose": "bounded market-calendar state diagnostics for one-day normal-stock bullish signals",
        "prediction": "T close signal, strict T+1 open to T+2 open rise; missing/suspended price counts wrong",
        "universe": "existing mainboard_liquid_stable_v1 and risk_status=normal event frames",
        "cost": "0.30% round-trip is reflected only in executable net metrics",
        "selection_policy": "all states and round thresholds were fixed in source before reading outcomes; no candidate was chosen from validation results",
        "sealed_validation_access": "none; phase2 sealed100 quotes, factors, labels and outcomes are not read",
        "input_sha256": {
            "phase6_events": sha256(PHASE6 / "pit_older_unadjusted_baostock.csv"),
            "phase6_index": sha256(PHASE6 / "hs300_older_unadjusted_baostock.csv"),
            "phase5_events": sha256(PHASE5 / "pit_unadjusted_baostock.csv"),
            "phase4_index": sha256(ROOT / "research/normal_stock_65/phase4/hs300_unadjusted_baostock.csv"),
        },
        "periods": PERIODS,
        "candidates": {},
    }

    for name, (description, mask) in masks.items():
        period_results: dict[str, dict] = {}
        for period, (start, end) in PERIODS.items():
            scope = data["trade_date"].dt.year.between(start, end)
            segment = data.loc[scope]
            chosen = data.loc[scope & mask.fillna(False)]
            period_results[period] = metric(chosen, len(segment))
            period_results[period]["matched_date"] = matched_date_comparison(segment, chosen)
        report["candidates"][name] = {
            "condition": description,
            "results": period_results,
            "classification": classify(period_results),
        }

    output = HERE / "market_calendar_rule_results.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps({
        name: {
            "classification": item["classification"],
            "combined": item["results"]["2011-2026_combined"],
        }
        for name, item in report["candidates"].items()
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
