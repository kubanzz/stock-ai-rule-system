#!/usr/bin/env python3
"""Audit retrospective QFQ prices against raw execution prices on development stocks.

This script reads only the phase2 development cohort. It never opens the sealed
validation CSV. Raw bars are requested from the same local AKTools endpoint as
the QFQ source, then used solely to inspect simulated opening-limit gates.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import numpy as np
import pandas as pd
import requests


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.search_directional_rules import event_frame  # noqa: E402
from research.normal_stock_65.phase2.calibrated_group import nonoverlapping_predictions  # noqa: E402


SOURCE = ROOT / "research/normal_stock_65/phase2/development_qfq.csv"
BASE = "http://127.0.0.1:8090/api/public/stock_zh_a_daily"


def fetch_raw(symbol: str) -> pd.DataFrame:
    code, exchange = symbol.split(".")
    error = None
    for attempt in range(5):
        try:
            response = requests.get(BASE, params={
                "symbol": exchange.lower() + code,
                "start_date": "20190102", "end_date": "20260929", "adjust": "",
            }, timeout=(5, 120))
            response.raise_for_status()
            frame = pd.DataFrame(response.json())
            break
        except (requests.RequestException, ValueError) as exc:
            error = exc
            time.sleep(1 + attempt)
    else:
        raise RuntimeError(f"raw bars unavailable for {symbol}: {error}")
    if frame.empty:
        raise RuntimeError(f"raw bars unavailable for {symbol}")
    frame = frame.rename(columns={"date": "trade_date", "open": "open_raw", "close": "close_raw"})
    frame["trade_date"] = pd.to_datetime(frame["trade_date"])
    frame["symbol"] = symbol
    return frame[["symbol", "trade_date", "open_raw", "close_raw"]]


def count(frame: pd.DataFrame, name: str) -> int:
    return int(frame[name].fillna(False).sum())


def compare(events: pd.DataFrame) -> dict:
    events = events.copy()
    events["entry_raw_gap"] = events["entry_open_raw"] / events["close_raw"] - 1
    events["exit_raw_gap"] = events["exit_open_raw"] / events["entry_close_raw"] - 1
    events["entry_raw_allowed"] = events["entry_price_known"] & events["entry_raw_gap"].lt(.095)
    events["exit_raw_blocked_proxy"] = ~events["exit_price_known"] | events["exit_raw_gap"].le(-.095)
    events["raw_gross_return"] = events["exit_open_raw"] / events["entry_open_raw"] - 1
    qfq_sign = np.sign(events["gross_return"])
    raw_sign = np.sign(events["raw_gross_return"])
    comparable = events["gross_return"].notna() & events["raw_gross_return"].notna()
    changed_adjustment = (
        ((events["entry_open_raw"] / events["entry_open"])
         / (events["exit_open_raw"] / events["exit_open"]) - 1).abs().gt(.002)
    )
    events["qfq_raw_direction_disagree"] = comparable & qfq_sign.ne(raw_sign)
    events["corporate_action_factor_change_gt20bp"] = changed_adjustment
    events["corrected_net_return"] = events["net_return"]
    events.loc[events["exit_raw_blocked_proxy"], "corrected_net_return"] = -.103
    qfq_executable = nonoverlapping_predictions(events.loc[events["entry_executable"]])
    raw_executable = nonoverlapping_predictions(events.loc[events["entry_raw_allowed"]])
    return {
        "eligible_T_close_predictions": len(events),
        "symbols": int(events["symbol"].nunique()),
        "entry_dates": int(events["entry_date"].nunique()),
        "raw_bar_missing_at_T": int(events["close_raw"].isna().sum()),
        "raw_bar_missing_at_entry": int(events["entry_open_raw"].isna().sum()),
        "raw_bar_missing_at_exit": int(events["exit_open_raw"].isna().sum()),
        "qfq_entry_executable_proxy": count(events, "entry_executable"),
        "raw_entry_executable_proxy": count(events, "entry_raw_allowed"),
        "entry_qfq_false_allowed_proxy": int((events["entry_executable"] & ~events["entry_raw_allowed"]).sum()),
        "entry_qfq_false_rejected_proxy": int((~events["entry_executable"] & events["entry_raw_allowed"]).sum()),
        "qfq_exit_blocked_proxy": count(events, "blocked_exit"),
        "raw_exit_blocked_proxy": count(events, "exit_raw_blocked_proxy"),
        "exit_qfq_false_blocked_proxy": int((events["blocked_exit"] & ~events["exit_raw_blocked_proxy"]).sum()),
        "exit_qfq_false_allowed_proxy": int((~events["blocked_exit"] & events["exit_raw_blocked_proxy"]).sum()),
        "qfq_vs_raw_open_direction_comparable": int(comparable.sum()),
        "qfq_vs_raw_open_direction_disagree": count(events, "qfq_raw_direction_disagree"),
        "qfq_factor_change_between_entry_exit_gt20bp": count(events, "corporate_action_factor_change_gt20bp"),
        "qfq_gate_nonoverlapping_positions": len(qfq_executable),
        "qfq_gate_net_win_rate": float(qfq_executable["net_return"].gt(0).mean()) if len(qfq_executable) else None,
        "qfq_gate_mean_net_return": float(qfq_executable["net_return"].mean()) if len(qfq_executable) else None,
        "raw_gate_nonoverlapping_positions": len(raw_executable),
        "raw_gate_qfq_net_win_rate": float(raw_executable["corrected_net_return"].gt(0).mean()) if len(raw_executable) else None,
        "raw_gate_qfq_mean_net_return": float(raw_executable["corrected_net_return"].mean()) if len(raw_executable) else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbols", type=int, default=200, help="first N sorted development symbols, never sealed")
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("audit_qfq_execution.json"))
    args = parser.parse_args()
    symbols = sorted(pd.read_csv(SOURCE, usecols=["symbol"])["symbol"].unique())[:args.symbols]
    if not symbols:
        raise ValueError("empty development selection")
    raw = []
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = {pool.submit(fetch_raw, symbol): symbol for symbol in symbols}
        for future in as_completed(futures):
            raw.append(future.result())
    raw = pd.concat(raw, ignore_index=True)
    if raw.duplicated(["symbol", "trade_date"]).any():
        raise ValueError("duplicate raw symbol and date")
    events = event_frame(SOURCE)
    events = events.loc[events["symbol"].isin(symbols)].copy()
    events = events.loc[
        events["trade_date"].dt.year.between(2024, 2026)
        & events["exit_date"].dt.year.eq(events["trade_date"].dt.year)
    ].copy()
    events = events.merge(raw.rename(columns={"close_raw": "entry_close_raw", "open_raw": "entry_open_raw", "trade_date": "entry_date"}),
                          on=["symbol", "entry_date"], how="left", validate="many_to_one")
    events = events.merge(raw.rename(columns={"close_raw": "exit_close_raw", "open_raw": "exit_open_raw", "trade_date": "exit_date"}),
                          on=["symbol", "exit_date"], how="left", validate="many_to_one")
    events = events.merge(raw[["symbol", "trade_date", "close_raw"]],
                          on=["symbol", "trade_date"], how="left", validate="many_to_one")
    output = {
        "source_qfq_sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        "raw_source": BASE + "?adjust=(empty)",
        "development_symbols_requested": len(symbols),
        "raw_rows_received": len(raw),
        "all_2024_2026": compare(events),
        "annual": {str(year): compare(group) for year, group in events.groupby(events["trade_date"].dt.year)},
        "limitations": [
            "Only phase2 development stocks are read; the sealed validation cohort remains unopened.",
            "The 9.5% opening gap remains only a proxy for exchange price limits; no order book, queue, ST status, or limit-price rounding is available.",
            "Raw open-to-open direction omits dividends, splits and other corporate-action value; disagreement with QFQ is a diagnostic, not proof that either return is economically correct.",
            "QFQ adjusted return is used for economic outcome proxy even when raw opening gap determines executable/blocked proxies; QFQ adjustment itself may be retrospectively revised.",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
