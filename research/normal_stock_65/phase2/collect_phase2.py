"""Collect two preassigned, disjoint mainboard OHLCV cohorts without labels.

Use only quote quality checks. Do not calculate factors, signal outcomes,
returns, accuracies or trade results here.
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
ROOT = OUT.parents[2]
OLD_UNIVERSES = [
    ROOT / "research/one_day_20260930/universe.csv",
    ROOT / "research/normal_stock_65/universe.csv",
]
BASE = "http://127.0.0.1:8090"
START, END = "20190102", "20260929"
SEED = 20261002
PER_EXCHANGE = {"development": 100, "validation_sealed": 50}
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
            response = requests.get(BASE + path, params=params, timeout=(5, 120))
            response.raise_for_status()
            rows = response.json()
            if not isinstance(rows, list):
                raise ValueError(f"response is {type(rows).__name__}, expected list")
            return rows
        except (requests.RequestException, ValueError) as error:
            last_error = error
            time.sleep(attempt + 1)
    raise RuntimeError(f"{path} {params}: {last_error}")


def choose_universe() -> tuple[list[dict], dict]:
    excluded = set()
    prior_hashes = {}
    for path in OLD_UNIVERSES:
        prior_hashes[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
        with path.open(newline="", encoding="utf-8") as fh:
            excluded.update(row["symbol"] for row in csv.DictReader(fh))
    rows = get_json("/api/public/stock_info_a_code_name")
    pools = {"SZ-main": [], "SH-main": []}
    for row in rows:
        code, name = str(row.get("code", "")), str(row.get("name", ""))
        meta = mainboard(code)
        if meta is None or "ST" in name.upper() or "退" in name:
            continue
        stratum, exchange = meta
        symbol = f"{code}.{exchange}"
        if symbol not in excluded:
            pools[stratum].append({"symbol": symbol, "name": name, "stratum": stratum})
    rng = random.Random(SEED)
    chosen = []
    for stratum in ("SZ-main", "SH-main"):
        pool = sorted(pools[stratum], key=lambda item: item["symbol"])
        need = sum(PER_EXCHANGE.values())
        if len(pool) < need:
            raise RuntimeError(f"{stratum}: {len(pool)} available, need {need}")
        sampled = rng.sample(pool, need)
        cursor = 0
        for cohort, count in PER_EXCHANGE.items():
            for item in sampled[cursor:cursor + count]:
                chosen.append({**item, "cohort": cohort})
            cursor += count
    chosen.sort(key=lambda item: (item["cohort"], item["symbol"]))
    assert len(chosen) == 300 and len({item["symbol"] for item in chosen}) == 300
    assert not {item["symbol"] for item in chosen}.intersection(excluded)
    return chosen, prior_hashes


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
            if date < "2019-01-02" or date > "2026-09-29":
                continue
            if date in by_date:
                raise ValueError(f"duplicate {symbol} {date}")
            vals = {field: row.get(field) for field in ("open", "high", "low", "close", "volume", "amount")}
            if any(value is None for value in vals.values()):
                skipped.append(f"{date}: null OHLCV")
                continue
            o, h, l, c = (float(vals[field]) for field in ("open", "high", "low", "close"))
            v, a = float(vals["volume"]), float(vals["amount"])
            if min(o, h, l, c, v, a) <= 0:
                skipped.append(f"{date}: nonpositive OHLCV, possible suspension")
                continue
            if l > min(o, c) + .011 or h < max(o, c) - .011 or l > h:
                skipped.append(f"{date}: inconsistent OHLC")
                continue
            by_date[date] = {"symbol": symbol, "trade_date": date, **vals,
                             "source": "aktools/akshare:stock_zh_a_daily:qfq"}
        data = [by_date[date] for date in sorted(by_date)]
        if not data:
            raise ValueError("no valid historical rows")
        for index, row in enumerate(data):
            row["pre_close"] = data[index - 1]["close"] if index else None
        return item, data, None, skipped
    except Exception as error:
        return item, [], str(error), []


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> str:
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    universe, old_hashes = choose_universe()
    # Persist cohort assignment before any quote fetch or quality inspection.
    assignment_hash = write_csv(OUT / "cohort_assignment.csv", universe,
                                ["symbol", "name", "stratum", "cohort"])
    quotes = {}
    failures = {}
    skipped = {}
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(fetch_quotes, item): item for item in universe}
        for number, future in enumerate(as_completed(futures), 1):
            item, data, error, dropped = future.result()
            symbol = item["symbol"]
            if error:
                failures[symbol] = error
            else:
                quotes[symbol] = data
            if dropped:
                skipped[symbol] = dropped
            if number % 25 == 0 or number == len(universe):
                print(f"{number}/{len(universe)}; {sum(map(len, quotes.values()))} quote rows; {len(failures)} failures", flush=True)
    enriched = []
    for item in universe:
        data = quotes.get(item["symbol"], [])
        enriched.append({**item, "bars": len(data),
                         "first_date": data[0]["trade_date"] if data else "",
                         "last_date": data[-1]["trade_date"] if data else "",
                         "fetch_error": failures.get(item["symbol"], "")})
    universe_hash = write_csv(OUT / "universe.csv", enriched,
                              ["symbol", "name", "stratum", "cohort", "bars", "first_date", "last_date", "fetch_error"])
    manifest = {
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "source": BASE + "/api/public/stock_zh_a_daily", "adjust": "qfq",
        "request_period": [START, END],
        "universe_source": BASE + "/api/public/stock_info_a_code_name",
        "universe_method": "Current listed non-ST SH/SZ mainboard; exclude previous 166+250 symbols; independent Python random.Random seed 20261002; 100 development and 50 sealed per exchange assigned before quote fetch.",
        "seed": SEED, "prior_universe_sha256": old_hashes,
        "cohort_assignment_sha256": assignment_hash, "universe_sha256": universe_hash,
        "requested_symbols": len(universe), "successful_symbols": len(quotes),
        "failed_symbols": failures, "skipped_anomalous_rows": skipped,
        "cohorts": {},
        "biases": [
            "Current listed/current non-ST only: survivorship and historical ST-status selection bias remain.",
            "QFQ prices can be retrospectively revised; this is not an as-of-date adjustment snapshot.",
            "Zero volume or invalid OHLCV rows are skipped and listed; missing market dates may indicate suspension.",
            "The two cohorts share historical market dates despite disjoint stocks, so they do not independently validate market regimes.",
        ],
        "outcome_analysis": "none: no factors, labels, returns, accuracy or trading results computed",
    }
    for cohort in PER_EXCHANGE:
        items = [item for item in universe if item["cohort"] == cohort]
        data = [row for item in items for row in quotes.get(item["symbol"], [])]
        data.sort(key=lambda row: (row["symbol"], row["trade_date"]))
        path = OUT / f"{cohort}_qfq.csv"
        digest = write_csv(path, data, FIELDS)
        bars = [len(quotes[item["symbol"]]) for item in items if item["symbol"] in quotes]
        dates = [row["trade_date"] for row in data]
        manifest["cohorts"][cohort] = {
            "csv": path.name, "sha256": digest,
            "assigned_symbols": len(items), "successful_symbols": len(bars),
            "rows": len(data), "first_date": min(dates) if dates else None,
            "last_date": max(dates) if dates else None,
            "unique_market_dates": len(Counter(dates)),
            "min_bars_per_symbol": min(bars) if bars else 0,
            "median_bars_per_symbol": sorted(bars)[len(bars) // 2] if bars else 0,
        }
    (OUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest["cohorts"], ensure_ascii=False))


if __name__ == "__main__":
    main()
