"""Collect a development cohort missing from current non-ST stock snapshots.

This script never reads the sealed validation cohort or computes outcomes. It
freezes the symbol assignment before downloading quotes. AKTools Tencent
history is used as a fallback source for names no longer trading; its `amount`
field is trading volume in lots, not monetary turnover.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path

import requests


OUT = Path(__file__).resolve().parent
BASE = "http://127.0.0.1:8090/api/public/"
START, END = "20190101", "20260929"
MAINBOARD = re.compile(r"(?:000|001|002|003|600|601|603|605)\d{3}\Z")
FIELDS = [
    "symbol", "trade_date", "open", "high", "low", "close", "volume_shares",
    "source", "source_amount_lots", "cohort",
]
LOCAL = threading.local()


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def get_json(name: str, params: dict | None = None) -> list[dict]:
    if not hasattr(LOCAL, "session"):
        LOCAL.session = requests.Session()
    last = None
    for attempt in range(4):
        try:
            resp = LOCAL.session.get(BASE + name, params=params, timeout=(5, 90))
            resp.raise_for_status()
            data = resp.json()
            if not isinstance(data, list):
                raise ValueError(f"{type(data).__name__} response")
            return data
        except (requests.RequestException, ValueError) as exc:
            last = exc
            time.sleep(min(2 ** attempt, 8))
    raise RuntimeError(f"{name} {params}: {last}")


def exchange(code: str) -> str:
    return "SH" if code.startswith("6") else "SZ"


def assignment() -> tuple[list[dict], dict]:
    sources = [
        ("stock_info_sh_delist", "公司代码", "公司简称", "上市日期", "暂停上市日期"),
        ("stock_info_sz_delist", "证券代码", "证券简称", "上市日期", "终止上市日期"),
    ]
    selected: dict[str, dict] = {}
    source_counts = {}
    for api, code_key, name_key, listing_key, terminal_key in sources:
        rows = get_json(api)
        source_counts[api] = len(rows)
        for row in rows:
            code = str(row.get(code_key, ""))
            terminal = str(row.get(terminal_key, ""))[:10]
            if not MAINBOARD.fullmatch(code) or not ("2019-01-01" <= terminal <= "2026-09-29"):
                continue
            symbol = f"{code}.{exchange(code)}"
            item = {
                "symbol": symbol,
                "name_at_list_snapshot": str(row.get(name_key, "")),
                "listed_date": str(row.get(listing_key, ""))[:10],
                "delisting_or_suspension_date": terminal,
                "terminal_date_field": terminal_key,
                "cohort": "delisted_2019_2026_development",
            }
            if symbol in selected:
                # Duplicate source records are common; only metadata is compared.
                previous = selected[symbol]
                if previous["delisting_or_suspension_date"] != terminal:
                    raise ValueError(f"conflicting terminal dates for {symbol}")
            else:
                selected[symbol] = item
    return sorted(selected.values(), key=lambda x: x["symbol"]), source_counts


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def fetch(item: dict) -> tuple[str, list[dict], str | None, int]:
    symbol = item["symbol"]
    code, market = symbol.split(".")
    try:
        raw = get_json("stock_zh_a_hist_tx", {
            "symbol": market.lower() + code,
            "start_date": START,
            "end_date": END,
            "adjust": "",
        })
        records = {}
        skipped = 0
        for row in raw:
            date = str(row.get("date", ""))[:10]
            if not ("2019-01-01" <= date <= "2026-09-29"):
                continue
            if date in records:
                raise ValueError(f"duplicate date {date}")
            try:
                vals = {key: float(row[key]) for key in ("open", "high", "low", "close", "amount")}
            except (TypeError, ValueError, KeyError):
                skipped += 1
                continue
            if min(vals.values()) <= 0 or vals["low"] > min(vals["open"], vals["close"]) + .011 or vals["high"] < max(vals["open"], vals["close"]) - .011:
                skipped += 1
                continue
            records[date] = {
                "symbol": symbol, "trade_date": date,
                "open": vals["open"], "high": vals["high"],
                "low": vals["low"], "close": vals["close"],
                "volume_shares": vals["amount"] * 100,
                "source": "AKTools/AkShare:stock_zh_a_hist_tx:unadjusted",
                "source_amount_lots": vals["amount"],
                "cohort": item["cohort"],
            }
        return symbol, [records[day] for day in sorted(records)], None, skipped
    except Exception as exc:
        return symbol, [], str(exc), 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--assignment-only", action="store_true", help="freeze symbol list without fetching Tencent quotes")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    members, source_counts = assignment()
    assignment_path = OUT / "delisted_assignment.csv"
    assignment_fields = ["symbol", "name_at_list_snapshot", "listed_date", "delisting_or_suspension_date", "terminal_date_field", "cohort"]
    write_csv(assignment_path, members, assignment_fields)
    assignment_hash = sha256(assignment_path)
    print(f"Frozen {len(members)} symbol assignments: {assignment_hash}", flush=True)
    if args.assignment_only:
        return

    quotes: dict[str, list[dict]] = {}
    errors: dict[str, str] = {}
    skipped = Counter()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(fetch, member): member["symbol"] for member in members}
        for index, future in enumerate(as_completed(futures), 1):
            symbol, rows, error, dropped = future.result()
            quotes[symbol] = rows
            if error:
                errors[symbol] = error
            if dropped:
                skipped[symbol] = dropped
            if index % 25 == 0 or index == len(members):
                print(f"{index}/{len(members)}; {sum(map(len, quotes.values()))} rows; {len(errors)} errors", flush=True)

    data = [row for member in members for row in quotes[member["symbol"]]]
    data.sort(key=lambda row: (row["symbol"], row["trade_date"]))
    data_path = OUT / "delisted_unadjusted_tx.csv"
    write_csv(data_path, data, FIELDS)
    coverage = [{**member, "bars": len(quotes[member["symbol"]]),
                 "first_quote_date": quotes[member["symbol"]][0]["trade_date"] if quotes[member["symbol"]] else "",
                 "last_quote_date": quotes[member["symbol"]][-1]["trade_date"] if quotes[member["symbol"]] else "",
                 "fetch_error": errors.get(member["symbol"], "")}
                for member in members]
    coverage_path = OUT / "delisted_coverage.csv"
    write_csv(coverage_path, coverage, assignment_fields + ["bars", "first_quote_date", "last_quote_date", "fetch_error"])
    manifest = {
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "development data integrity and survivorship-bias audit only",
        "source_api": BASE + "stock_zh_a_hist_tx",
        "universe_sources": {BASE + key: count for key, count in source_counts.items()},
        "requested_range": [START, END],
        "selection": "all unique SH/SZ mainboard codes with a listed terminal/suspension date in 2019-01-01 through 2026-09-29; no outcome-based filtering",
        "assigned_symbols": len(members),
        "symbols_with_quotes": sum(bool(rows) for rows in quotes.values()),
        "rows": len(data),
        "first_quote_date": min((x["trade_date"] for x in data), default=None),
        "last_quote_date": max((x["trade_date"] for x in data), default=None),
        "fetch_errors": errors,
        "skipped_invalid_ohlcv_rows": dict(skipped),
        "sha256": {path.name: sha256(path) for path in (assignment_path, data_path, coverage_path)},
        "field_caveat": "Tencent `amount` is trading lots; volume_shares=amount*100. No monetary turnover, per-day ST or tradestatus fields are available from this endpoint.",
        "universe_caveat": "SH terminal field is 暂停上市日期 whereas SZ is 终止上市日期; these cannot be treated as interchangeable last-trading dates.",
        "outcome_analysis": "none",
        "sealed_validation_access": "none",
    }
    (OUT / "delisted_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: manifest[key] for key in ("assigned_symbols", "symbols_with_quotes", "rows", "first_quote_date", "last_quote_date")}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
