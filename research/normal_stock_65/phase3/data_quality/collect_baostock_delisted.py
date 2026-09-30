"""Download raw, unadjusted BaoStock history for the frozen delisted cohort.

Run with BaoStock 0.9.4 available on PYTHONPATH. This is a quality dataset:
it does not calculate predictions, labels, returns or win rates, and never
reads the sealed validation cohort.
"""

from __future__ import annotations

import csv
import hashlib
import json
import socket
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import baostock as bs


OUT = Path(__file__).resolve().parent
ASSIGNMENT = OUT / "delisted_assignment.csv"
START, END = "2019-01-01", "2026-09-29"
QUERY_FIELDS = "date,code,open,high,low,close,preclose,volume,amount,adjustflag,tradestatus,isST"
OUTPUT_FIELDS = [
    "symbol", "trade_date", "open", "high", "low", "close", "pre_close",
    "volume_shares", "amount_yuan", "adjust_flag", "trade_status", "is_st",
    "source",
]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def get_rows(symbol: str) -> list[dict]:
    code, market = symbol.split(".")
    result = bs.query_history_k_data_plus(
        f"{market.lower()}.{code}", QUERY_FIELDS,
        start_date=START, end_date=END, frequency="d", adjustflag="3",
    )
    if result.error_code != "0":
        raise RuntimeError(f"query: {result.error_code} {result.error_msg}")
    records = []
    seen = set()
    while result.next():
        raw = dict(zip(result.fields, result.get_row_data()))
        date = raw["date"]
        if date in seen:
            raise ValueError(f"duplicate {symbol} {date}")
        seen.add(date)
        if raw["code"] != f"{market.lower()}.{code}" or raw["adjustflag"] != "3":
            raise ValueError(f"unexpected code or adjustment flag for {symbol} {date}")
        records.append({
            "symbol": symbol,
            "trade_date": date,
            "open": raw["open"], "high": raw["high"], "low": raw["low"],
            "close": raw["close"], "pre_close": raw["preclose"],
            "volume_shares": raw["volume"], "amount_yuan": raw["amount"],
            "adjust_flag": raw["adjustflag"],
            "trade_status": raw["tradestatus"], "is_st": raw["isST"],
            "source": "BaoStock:query_history_k_data_plus:adjustflag=3",
        })
    if result.error_code != "0":
        raise RuntimeError(f"iterate: {result.error_code} {result.error_msg}")
    return records


def login() -> None:
    response = bs.login()
    if response.error_code != "0":
        raise RuntimeError(f"login: {response.error_code} {response.error_msg}")


def main() -> None:
    socket.setdefaulttimeout(20)
    if not ASSIGNMENT.exists():
        raise FileNotFoundError("Freeze delisted_assignment.csv before quote download")
    with ASSIGNMENT.open(encoding="utf-8", newline="") as fh:
        members = list(csv.DictReader(fh))
    symbols = [row["symbol"] for row in members]
    if len(symbols) != len(set(symbols)):
        raise ValueError("duplicate assignment")
    assignment_hash = digest(ASSIGNMENT)
    print(f"Frozen {len(symbols)} symbols; assignment SHA256 {assignment_hash}", flush=True)

    all_rows: list[dict] = []
    counts: dict[str, int] = {}
    errors: dict[str, str] = {}
    login()
    for index, symbol in enumerate(symbols, 1):
        last_error = None
        for attempt in range(3):
            try:
                rows = get_rows(symbol)
                all_rows.extend(rows)
                counts[symbol] = len(rows)
                last_error = None
                break
            except Exception as exc:
                last_error = str(exc)
                try:
                    bs.logout()
                except Exception:
                    pass
                time.sleep(attempt + 1)
                try:
                    login()
                except Exception as login_exc:
                    last_error = f"{last_error}; {login_exc}"
        if last_error:
            errors[symbol] = last_error
            counts[symbol] = 0
        if index % 20 == 0 or index == len(symbols):
            print(f"{index}/{len(symbols)}; rows={len(all_rows)}; errors={len(errors)}", flush=True)
    try:
        bs.logout()
    except Exception:
        pass

    all_rows.sort(key=lambda row: (row["symbol"], row["trade_date"]))
    data_file = OUT / "delisted_unadjusted_baostock.csv"
    write_csv(data_file, all_rows, OUTPUT_FIELDS)
    coverage_file = OUT / "delisted_baostock_coverage.csv"
    by_symbol: dict[str, list[dict]] = {}
    for row in all_rows:
        by_symbol.setdefault(row["symbol"], []).append(row)
    coverage = [
        {**member,
         "bars": counts.get(member["symbol"], 0),
         "first_date": by_symbol[member["symbol"]][0]["trade_date"] if member["symbol"] in by_symbol else "",
         "last_date": by_symbol[member["symbol"]][-1]["trade_date"] if member["symbol"] in by_symbol else "",
         "st_rows": sum(row["is_st"] == "1" for row in by_symbol.get(member["symbol"], [])),
         "suspended_rows": sum(row["trade_status"] == "0" for row in by_symbol.get(member["symbol"], [])),
         "fetch_error": errors.get(member["symbol"], "")}
        for member in members
    ]
    write_csv(coverage_file, coverage, list(members[0]) + ["bars", "first_date", "last_date", "st_rows", "suspended_rows", "fetch_error"])
    status_counts = Counter((row["trade_status"], row["is_st"]) for row in all_rows)
    manifest = {
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "development data integrity and survivorship-bias audit; no outcome analysis",
        "library": "baostock==0.9.4",
        "library_url": "https://pypi.org/project/baostock/0.9.4/",
        "query": {"function": "query_history_k_data_plus", "fields": QUERY_FIELDS,
                  "start_date": START, "end_date": END, "frequency": "d", "adjustflag": "3"},
        "assignment_sha256": assignment_hash,
        "assigned_symbols": len(symbols),
        "symbols_with_rows": sum(bool(n) for n in counts.values()),
        "rows": len(all_rows),
        "first_date": min((row["trade_date"] for row in all_rows), default=None),
        "last_date": max((row["trade_date"] for row in all_rows), default=None),
        "status_counts": {f"tradestatus={trade_status},isST={is_st}": n for (trade_status, is_st), n in status_counts.items()},
        "fetch_errors": errors,
        "sha256": {path.name: digest(path) for path in (data_file, coverage_file)},
        "field_caveats": [
            "adjustflag=3 yields unadjusted historical prices; corporate actions need separate treatment for event-day returns.",
            "tradestatus=0 rows are retained; they must not be modeled as executable trades.",
            "isST is source-reported per-day status, not independently verified against exchange records.",
            "Source list contains only 2019-2026 terminal dates; it does not constitute a full historical point-in-time A-share universe.",
        ],
        "sealed_validation_access": "none",
    }
    (OUT / "delisted_baostock_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: manifest[key] for key in ("assigned_symbols", "symbols_with_rows", "rows", "status_counts")}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
