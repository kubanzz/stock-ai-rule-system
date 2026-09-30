"""Build an isolated, reproducible A-share daily OHLCV research sample.

Run: python3 research/one_day_20260930/collect_ashare_daily.py

The current AKTools stock list is only a present-day snapshot. This sample is
for exploratory research and must not be represented as a survivorship-free
point-in-time universe or written directly into production quote tables.
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
BASE = "http://127.0.0.1:8090"
START = "20230101"
END = "20260929"
SEED = 20260930
SAMPLE_COUNTS = {
    "SZ-main": 40,
    "SH-main": 40,
    "ChiNext": 30,
    "STAR": 30,
    "BJ": 20,
}
ADDITIONAL_LOCAL = ["000001.SZ", "000002.SZ", "600519.SH", "600009.SH", "300454.SZ", "002714.SZ", "002063.SZ"]
FIELDS = ["symbol", "trade_date", "open", "high", "low", "close", "pre_close", "volume", "amount", "source"]


def stratum(code: str) -> tuple[str, str] | None:
    if re.fullmatch(r"(?:000|001|002|003)\d{3}", code):
        return "SZ-main", "SZ"
    if re.fullmatch(r"(?:300|301)\d{3}", code):
        return "ChiNext", "SZ"
    if re.fullmatch(r"(?:600|601|603|605)\d{3}", code):
        return "SH-main", "SH"
    if re.fullmatch(r"(?:688|689)\d{3}", code):
        return "STAR", "SH"
    if re.fullmatch(r"[489]\d{5}", code):
        return "BJ", "BJ"
    return None


def get_json(path: str, params: dict | None = None) -> list[dict]:
    last_error = None
    for attempt in range(3):
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


def choose_universe() -> list[dict]:
    rows = get_json("/api/public/stock_info_a_code_name")
    strata: dict[str, list[dict]] = {name: [] for name in SAMPLE_COUNTS}
    for row in rows:
        code = str(row.get("code", ""))
        name = str(row.get("name", ""))
        meta = stratum(code)
        if meta is None or "ST" in name.upper() or "退" in name:
            continue
        group, exchange = meta
        strata[group].append({"symbol": f"{code}.{exchange}", "name": name, "stratum": group})
    rng = random.Random(SEED)
    selected = []
    for group, count in SAMPLE_COUNTS.items():
        pool = sorted(strata[group], key=lambda row: row["symbol"])
        if len(pool) < count:
            raise RuntimeError(f"{group}: {len(pool)} stocks, need {count}")
        selected.extend(rng.sample(pool, count))
    by_symbol = {row["symbol"]: row for row in selected}
    all_by_symbol = {
        f"{str(row.get('code'))}.{stratum(str(row.get('code')))[1]}": row
        for row in rows
        if stratum(str(row.get("code"))) is not None
    }
    for symbol in ADDITIONAL_LOCAL:
        if symbol not in by_symbol:
            code = symbol[:6]
            row = all_by_symbol.get(symbol, {})
            by_symbol[symbol] = {"symbol": symbol, "name": str(row.get("name", "")), "stratum": stratum(code)[0]}
    return sorted(by_symbol.values(), key=lambda row: row["symbol"])


def fetch_quotes(item: dict) -> tuple[dict, list[dict], str | None, list[str]]:
    symbol = item["symbol"]
    code, exchange = symbol.split(".")
    try:
        raw = get_json(
            "/api/public/stock_zh_a_daily",
            {"symbol": exchange.lower() + code, "start_date": START, "end_date": END, "adjust": "qfq"},
        )
        by_date = {}
        skipped = []
        for row in raw:
            date = str(row.get("date", ""))[:10]
            if date < "2023-01-01" or date > "2026-09-29":
                continue
            if date in by_date:
                raise ValueError(f"duplicate {symbol} {date}")
            vals = {field: row.get(field) for field in ("open", "high", "low", "close", "volume", "amount")}
            if any(value is None for value in vals.values()):
                raise ValueError(f"null OHLCV {symbol} {date}: {vals}")
            o, h, l, c = (float(vals[field]) for field in ("open", "high", "low", "close"))
            v, a = float(vals["volume"]), float(vals["amount"])
            if min(o, h, l, c) <= 0 or v <= 0 or a <= 0:
                skipped.append(f"{date}: nonpositive OHLCV, likely suspension if volume=amount=0")
                continue
            if l > min(o, c) + 0.011 or h < max(o, c) - 0.011 or l > h:
                skipped.append(f"{date}: inconsistent OHLC")
                continue
            by_date[date] = {"symbol": symbol, "trade_date": date, **vals, "source": "aktools/akshare:stock_zh_a_daily:qfq"}
        data = [by_date[date] for date in sorted(by_date)]
        for index, row in enumerate(data):
            row["pre_close"] = data[index - 1]["close"] if index > 0 else None
        if not data:
            raise ValueError("no historical rows")
        return item, data, None, skipped
    except Exception as error:
        return item, [], str(error), []


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    universe = choose_universe()
    all_quotes = {}
    failures = {}
    skipped_rows = {}
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(fetch_quotes, item): item for item in universe}
        for number, future in enumerate(as_completed(futures), 1):
            item, rows, error, skipped = future.result()
            symbol = item["symbol"]
            if error:
                failures[symbol] = error
            else:
                all_quotes[symbol] = rows
            if skipped:
                skipped_rows[symbol] = skipped
            if number % 20 == 0 or number == len(universe):
                print(f"{number}/{len(universe)} stocks; {sum(map(len, all_quotes.values()))} rows; {len(failures)} failures", flush=True)

    universe_file = OUT / "universe.csv"
    with universe_file.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["symbol", "name", "stratum", "bars", "first_date", "last_date", "fetch_error"])
        writer.writeheader()
        for item in universe:
            rows = all_quotes.get(item["symbol"], [])
            writer.writerow({**item, "bars": len(rows), "first_date": rows[0]["trade_date"] if rows else "", "last_date": rows[-1]["trade_date"] if rows else "", "fetch_error": failures.get(item["symbol"], "")})
    quotes_file = OUT / "ashare_daily_qfq.csv"
    with quotes_file.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDS)
        writer.writeheader()
        for symbol in sorted(all_quotes):
            writer.writerows(all_quotes[symbol])

    lengths = [len(rows) for rows in all_quotes.values()]
    dates = [row["trade_date"] for rows in all_quotes.values() for row in rows]
    date_counts = Counter(dates)
    manifest = {
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "source": BASE + "/api/public/stock_zh_a_daily",
        "adjust": "qfq",
        "request_period": [START, END],
        "universe_source": BASE + "/api/public/stock_info_a_code_name",
        "universe_method": "Current non-ST/non-delisting A-share snapshot; stratified by five listing boards; Python random.Random seed 20260930; seven locally-covered stocks forcibly included.",
        "universe_seed": SEED,
        "stratum_targets": SAMPLE_COUNTS,
        "requested_symbols": len(universe),
        "successful_symbols": len(all_quotes),
        "failed_symbols": failures,
        "rows": len(dates),
        "first_date": min(dates) if dates else None,
        "last_date": max(dates) if dates else None,
        "min_bars_per_symbol": min(lengths) if lengths else 0,
        "median_bars_per_symbol": sorted(lengths)[len(lengths) // 2] if lengths else 0,
        "symbols_with_at_least_750_bars": sum(bars >= 750 for bars in lengths),
        "unique_dates": len(date_counts),
        "duplicate_symbol_dates": 0,
        "skipped_anomalous_rows": skipped_rows,
        "data_sha256": hashlib.sha256(quotes_file.read_bytes()).hexdigest(),
        "biases": [
            "2026-09-30 current listed and current non-ST names only; delisted and current ST stocks excluded, so survivorship and status-selection bias remain.",
            "The list is not a historical constituent snapshot. The same fixed sample is used for all dates; validate results on a true point-in-time universe before production activation.",
            "QFQ prices can be retrospectively revised after corporate actions. Whole-history adjustment used here is research-only; production feature generation needs as-of-date adjustment snapshots.",
            "Untradeable next opens from suspensions/limit-up must be excluded in execution analysis, not treated as filled orders.",
            "Rows with zero volume/amount are skipped and reported; a gap in one stock's dates can indicate suspension. Execution analysis must use the market calendar to avoid treating the next listed bar as the next trading day.",
        ],
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: manifest[key] for key in ("requested_symbols", "successful_symbols", "rows", "first_date", "last_date", "median_bars_per_symbol", "symbols_with_at_least_750_bars", "unique_dates")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
