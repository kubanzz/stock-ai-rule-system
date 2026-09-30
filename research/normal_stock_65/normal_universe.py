#!/usr/bin/env python3
"""Fixed, point-in-time normal-stock eligibility for one-day signal research.

The output is a per-symbol, per-signal-day flag. No outcome or future quote is
read to decide eligibility. The source universe itself is a current snapshot,
so the flag does not remove its survivorship or historical ST-status bias.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import pandas as pd


NORMAL_UNIVERSE_ID = "mainboard_liquid_stable_v1"
MAINBOARD_PATTERN = r"^(?:(?:000|001|002|003)\d{3}\.SZ|(?:600|601|603|605)\d{3}\.SH)$"
MIN_HISTORY_BARS = 252
ROLLING_DAYS = 20
MIN_T_AMOUNT = 20_000_000
MIN_MEDIAN_AMOUNT_20D = 50_000_000
MAX_STD_DAILY_RETURN_20D = 0.04
MAX_ABS_DAILY_RETURN_20D = 0.08


def add_normal_universe_eligibility(frame: pd.DataFrame) -> pd.DataFrame:
    """Add a fixed flag using only a symbol's close/amount through signal day T.

    Required columns: ``symbol``, ``trade_date``, ``close_price``, ``amount``.
    Rows must be unique by symbol and trade date. Bars, including signal day T,
    are counted per symbol; this avoids reading a future listing-age snapshot.
    The 20 daily returns include T and require 21 consecutive *observed* bars;
    separate execution checks must still reject market-calendar gaps.
    """
    required = {"symbol", "trade_date", "close_price", "amount"}
    missing = required.difference(frame.columns)
    if missing:
        raise ValueError(f"Missing normal-universe columns: {sorted(missing)}")
    if frame.duplicated(["symbol", "trade_date"]).any():
        raise ValueError("Duplicate symbol/trade_date rows in normal-universe input")

    output = frame.sort_values(["symbol", "trade_date"]).copy()
    close = pd.to_numeric(output["close_price"], errors="coerce")
    amount = pd.to_numeric(output["amount"], errors="coerce")
    if close.le(0).any() or amount.le(0).any():
        raise ValueError("Normal-universe input contains nonpositive close/amount")
    output["normal_history_bars"] = output.groupby("symbol", sort=False).cumcount() + 1
    daily_return = close.groupby(output["symbol"], sort=False).pct_change(fill_method=None)
    rolling_return = daily_return.groupby(output["symbol"], sort=False).rolling(
        ROLLING_DAYS, min_periods=ROLLING_DAYS
    )
    output["normal_volatility_20d"] = rolling_return.std(ddof=0).reset_index(level=0, drop=True)
    output["normal_max_abs_return_20d"] = (
        daily_return.abs().groupby(output["symbol"], sort=False)
        .rolling(ROLLING_DAYS, min_periods=ROLLING_DAYS).max()
        .reset_index(level=0, drop=True)
    )
    output["normal_median_amount_20d"] = (
        amount.groupby(output["symbol"], sort=False)
        .rolling(ROLLING_DAYS, min_periods=ROLLING_DAYS).median()
        .reset_index(level=0, drop=True)
    )
    output["normal_universe_eligible"] = (
        output["symbol"].str.match(MAINBOARD_PATTERN)
        & output["normal_history_bars"].ge(MIN_HISTORY_BARS)
        & amount.ge(MIN_T_AMOUNT)
        & output["normal_median_amount_20d"].ge(MIN_MEDIAN_AMOUNT_20D)
        & output["normal_volatility_20d"].le(MAX_STD_DAILY_RETURN_20D)
        & output["normal_max_abs_return_20d"].lt(MAX_ABS_DAILY_RETURN_20D)
    )
    return output.sort_index()


def summarize_coverage(frame: pd.DataFrame) -> dict:
    eligible = frame.loc[frame["normal_universe_eligible"]].copy()
    latest_date = frame["trade_date"].max()
    latest = eligible.loc[eligible["trade_date"].eq(latest_date)]
    mainboard = frame["symbol"].str.match(MAINBOARD_PATTERN)
    old_enough = frame["normal_history_bars"].ge(MIN_HISTORY_BARS)
    sufficiently_liquid = (
        frame["amount"].ge(MIN_T_AMOUNT)
        & frame["normal_median_amount_20d"].ge(MIN_MEDIAN_AMOUNT_20D)
    )
    stable = (
        frame["normal_volatility_20d"].le(MAX_STD_DAILY_RETURN_20D)
        & frame["normal_max_abs_return_20d"].lt(MAX_ABS_DAILY_RETURN_20D)
    )
    data = {
        "id": NORMAL_UNIVERSE_ID,
        "definition": {
            "board": "SZ 000/001/002/003 or SH 600/601/603/605",
            "min_observed_bars_through_T": MIN_HISTORY_BARS,
            "rolling_observed_days": ROLLING_DAYS,
            "min_T_amount_yuan": MIN_T_AMOUNT,
            "min_20d_median_amount_yuan": MIN_MEDIAN_AMOUNT_20D,
            "max_20d_daily_close_return_std": MAX_STD_DAILY_RETURN_20D,
            "max_20d_abs_daily_close_return_exclusive": MAX_ABS_DAILY_RETURN_20D,
        },
        "input_rows": len(frame),
        "input_symbols": int(frame["symbol"].nunique()),
        "eligible_rows": len(eligible),
        "ever_eligible_symbols": int(eligible["symbol"].nunique()),
        "latest_signal_date": latest_date.strftime("%Y-%m-%d"),
        "latest_eligible_symbols": sorted(latest["symbol"].tolist()),
        "sequential_filter_counts": {
            "mainboard_rows": int(mainboard.sum()),
            "plus_history_rows": int((mainboard & old_enough).sum()),
            "plus_liquidity_rows": int((mainboard & old_enough & sufficiently_liquid).sum()),
            "plus_stability_rows": int((mainboard & old_enough & sufficiently_liquid & stable).sum()),
        },
        "by_year": {},
        "limitations": [
            "Input symbols were sampled from the 2026-09-30 listed non-ST snapshot; historical delisted/ST names are missing.",
            "Historical per-day ST labels are absent, so the board price-limit assumption may be wrong for historical ST days.",
            "QFQ historical closes are retrospectively adjusted and require an as-of-date adjustment check before production use.",
            "20 observed bars need not span 20 market sessions; execution must separately reject suspension gaps.",
            "Eligibility does not use returns after T or symbol-specific 2026 performance; 2026 outcomes are not reported here.",
        ],
    }
    for year, year_frame in frame.groupby(frame["trade_date"].dt.year, sort=True):
        chosen = year_frame.loc[year_frame["normal_universe_eligible"]]
        data["by_year"][str(year)] = {
            "input_rows": len(year_frame),
            "eligible_rows": len(chosen),
            "eligible_row_share": round(len(chosen) / len(year_frame), 4),
            "input_symbols": int(year_frame["symbol"].nunique()),
            "ever_eligible_symbols": int(chosen["symbol"].nunique()),
            "eligible_dates": int(chosen["trade_date"].nunique()),
        }
    return data


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, default=Path(__file__).parents[1] / "one_day_20260930/ashare_daily_qfq.csv")
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("normal_universe_coverage.json"))
    args = parser.parse_args()
    source = pd.read_csv(args.csv).rename(columns={"close": "close_price"})
    source["trade_date"] = pd.to_datetime(source["trade_date"])
    source["close_price"] = pd.to_numeric(source["close_price"], errors="coerce")
    source["amount"] = pd.to_numeric(source["amount"], errors="coerce")
    source = source.dropna(subset=["symbol", "trade_date", "close_price", "amount"])
    source = source.loc[source["close_price"].gt(0) & source["amount"].gt(0)].copy()
    flagged = add_normal_universe_eligibility(source)
    report = summarize_coverage(flagged)
    report["source_csv"] = str(args.csv)
    report["source_sha256"] = hashlib.sha256(args.csv.read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: report[k] for k in ("id", "input_rows", "input_symbols", "eligible_rows", "ever_eligible_symbols", "by_year")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
