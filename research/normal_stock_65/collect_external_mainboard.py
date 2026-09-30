"""Collect a disjoint external A-share mainboard OHLCV sample, without labels.

Run from the repository root:
    python3 research/normal_stock_65/collect_external_mainboard.py

The output is for external validation after a candidate has been frozen. This
collector intentionally does not calculate factors, trades or rule outcomes.
"""

from __future__ import annotations

import csv
import hashlib
import json
import random
import re
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path

import requests


OUT = Path(__file__).resolve().parent
OLD_UNIVERSE = OUT.parent / "one_day_20260930" / "universe.csv"
BASE = "http://127.0.0.1:8090"
START, END = "20230103", "20260929"
SEED = 20261001
TARGET_PER_EXCHANGE = 125
FIELDS = ["symbol", "trade_date", "open", "high", "low", "close", "pre_close", "volume", "amount", "source"]


def mainboard(code: str) -> tuple[str, str] | None:
    if re.fullmatch(r"(?:000|001|002|003)\d{3}", code):
        return "SZ-main", "SZ"
    if re.fullmatch(r"(?:600|601|603|605)\d{3}", code):
        return "SH-main", "SH"
    return None


def get_json(path: str, params: dict | None = None) -> list[dict]:
    last_error = None
    for attempt in range(4):
        try:
            response = requests.get(BASE + path, params=params, timeout=(5, 90))
            response.raise_for_status()
            rows = response.json()
            if not isinstance(rows, list):
                raise ValueError(f"response is {type(rows).__name__}, expected list")
            return rows
        except (requests.RequestException, ValueError) as error:
            last_error = error
            time.sleep(attempt + 1)
    raise RuntimeError(f"{path} {params}: {last_error}")


def choose_universe() -> tuple[list[dict], str]:
    old_bytes = OLD_UNIVERSE.read_bytes()
    with OLD_UNIVERSE.open(newline="", encoding="utf-8") as fh:
        excluded = {row["symbol"] for row in csv.DictReader(fh)}
    rows = get_json("/api/public/stock_info_a_code_name")
    pools: dict[str, list[dict]] = {"SZ-main": [], "SH-main": []}
    for row in rows:
        code, name = str(row.get("code", "")), str(row.get("name", ""))
        meta = mainboard(code)
        if meta is None or "ST" in name.upper() or "退" in name:
            continue
        stratum, exchange = meta
        symbol = f"{code}.{exchange}"
        if symbol in excluded:
            continue
        pools[stratum].append({"symbol": symbol, "name": name, "stratum": stratum})
    rng = random.Random(SEED)
    selected = []
    for stratum in ("SZ-main", "SH-main"):
        pool = sorted(pools[stratum], key=lambda item: item["symbol"])
        if len(pool) < TARGET_PER_EXCHANGE:
            raise RuntimeError(f"{stratum}: only {len(pool)} stocks after exclusion")
        selected.extend(rng.sample(pool, TARGET_PER_EXCHANGE))
    selected.sort(key=lambda item: item["symbol"])
    assert all(row["symbol"] not in excluded for row in selected)
    return selected, hashlib.sha256(old_bytes).hexdigest()


def fetch_quotes(item: dict) -> tuple[dict, list[dict], str | None, list[str]]:
    symbol = item["symbol"]
    code, exchange = symbol.split(".")
    try:
        raw = get_json("/api/public/stock_zh_a_daily", {
            "symbol": exchange.lower() + code,
            "start_date": START, "end_date": END, "adjust": "qfq",
        })
        by_date = {}
        skipped = []
        for row in raw:
            date = str(row.get("date", ""))[:10]
            if date < "2023-01-03" or date > "2026-09-29":
                continue
            if date in by_date:
                raise ValueError(f"duplicate {symbol} {date}")
            raw_values = {field: row.get(field) for field in ("open", "high", "low", "close", "volume", "amount")}
            if any(value is None for value in raw_values.values()):
                skipped.append(f"{date}: null OHLCV")
                continue
            values = {field: float(value) for field, value in raw_values.items()}
            o, h, l, c = (values[field] for field in ("open", "high", "low", "close"))
            if min(values.values()) <= 0:
                skipped.append(f"{date}: nonpositive OHLCV, possible suspension")
                continue
            if l > min(o, c) + 0.011 or h < max(o, c) - 0.011 or l > h:
                skipped.append(f"{date}: inconsistent OHLC")
                continue
            by_date[date] = {"symbol": symbol, "trade_date": date, **raw_values,
                             "source": "aktools/akshare:stock_zh_a_daily:qfq"}
        data = [by_date[date] for date in sorted(by_date)]
        if not data:
            raise ValueError("no valid historical rows")
        for index, row in enumerate(data):
            row["pre_close"] = data[index - 1]["close"] if index else None
        return item, data, None, skipped
    except Exception as error:
        return item, [], str(error), []


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    universe, old_universe_hash = choose_universe()
    all_quotes = {}
    failures = {}
    skipped_rows = {}
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(fetch_quotes, item): item for item in universe}
        for number, future in enumerate(as_completed(futures), 1):
            item, data, error, skipped = future.result()
            symbol = item["symbol"]
            if error:
                failures[symbol] = error
            else:
                all_quotes[symbol] = data
            if skipped:
                skipped_rows[symbol] = skipped
            if number % 25 == 0 or number == len(universe):
                print(f"{number}/{len(universe)} stocks; {sum(map(len, all_quotes.values()))} rows; {len(failures)} failures", flush=True)

    with (OUT / "universe.csv").open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["symbol", "name", "stratum", "bars", "first_date", "last_date", "fetch_error"])
        writer.writeheader()
        for item in universe:
            bars = all_quotes.get(item["symbol"], [])
            writer.writerow({**item, "bars": len(bars),
                             "first_date": bars[0]["trade_date"] if bars else "",
                             "last_date": bars[-1]["trade_date"] if bars else "",
                             "fetch_error": failures.get(item["symbol"], "")})

    csv_path = OUT / "external_mainboard_qfq.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDS)
        writer.writeheader()
        for symbol in sorted(all_quotes):
            writer.writerows(all_quotes[symbol])
    lengths = [len(data) for data in all_quotes.values()]
    dates = [row["trade_date"] for data in all_quotes.values() for row in data]
    manifest = {
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "source": BASE + "/api/public/stock_zh_a_daily",
        "adjust": "qfq", "request_period": [START, END],
        "universe_source": BASE + "/api/public/stock_info_a_code_name",
        "universe_method": "Current non-ST/non-delisting SH/SZ mainboard snapshot; exclude every symbol in previous universe.csv; 125 each exchange via Python random.Random seed 20261001.",
        "universe_seed": SEED, "old_universe_sha256": old_universe_hash,
        "requested_symbols": len(universe), "successful_symbols": len(all_quotes),
        "failed_symbols": failures, "rows": len(dates),
        "first_date": min(dates) if dates else None,
        "last_date": max(dates) if dates else None,
        "min_bars_per_symbol": min(lengths) if lengths else 0,
        "median_bars_per_symbol": sorted(lengths)[len(lengths) // 2] if lengths else 0,
        "symbols_with_at_least_750_bars": sum(bars >= 750 for bars in lengths),
        "unique_dates": len(Counter(dates)), "duplicate_symbol_dates": 0,
        "skipped_anomalous_rows": skipped_rows,
        "data_sha256": hashlib.sha256(csv_path.read_bytes()).hexdigest(),
        "biases": [
            "Current listed/current non-ST stocks only: survivorship and status-selection bias; this is not a historical point-in-time universe.",
            "QFQ prices can be retrospectively revised after corporate actions; not as-of-date adjustment snapshots.",
            "Zero-volume and invalid OHLCV rows are skipped and reported; missing per-stock market dates may indicate suspension.",
        ],
        "outcome_analysis": "none; collector computes no factors, trades, returns, win rates or model selection metrics",
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: manifest[key] for key in (
        "requested_symbols", "successful_symbols", "rows", "first_date", "last_date",
        "median_bars_per_symbol", "unique_dates", "data_sha256")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
