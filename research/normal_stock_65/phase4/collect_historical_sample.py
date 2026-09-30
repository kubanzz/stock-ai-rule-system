"""Freeze a disjoint historical-membership sample, then fetch raw daily quotes.

Usage::

    PYTHONPATH=/tmp/stock_data_quality_baostock python3 collect_historical_sample.py freeze
    PYTHONPATH=/tmp/stock_data_quality_baostock python3 collect_historical_sample.py fetch

No prediction labels, forward returns, or sealed quotes are read. Historical
membership is fixed before any sampled stock quotes are fetched. The sealed
assignment is read only to guarantee symbol disjointness. The known future
delisted cohort is also excluded, so this sample has survival selection bias.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import socket
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import baostock as bs


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SNAPSHOTS = ("2019-01-02", "2023-01-03")
PER_CELL = 20
SEED = "historical-mainboard-normal-v1-20260930"
END = "2026-09-29"
MAINBOARD = re.compile(r"(?:sh\.(?:600|601|603|605)|sz\.(?:000|001|002|003))\d{3}\Z")
HISTORY_FIELDS = "date,code,open,high,low,close,preclose,volume,amount,adjustflag,tradestatus,isST"
OUTPUT_FIELDS = [
    "symbol", "trade_date", "open", "high", "low", "close", "pre_close",
    "volume_shares", "amount_yuan", "adjust_flag", "trade_status", "is_st", "source",
]
EXCLUSION_FILES = (
    ROOT / "research/one_day_20260930/universe.csv",
    ROOT / "research/normal_stock_65/universe.csv",
    ROOT / "research/normal_stock_65/phase2/cohort_assignment.csv",
    ROOT / "research/normal_stock_65/phase3/data_quality/delisted_assignment.csv",
)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def read_symbols(path: Path) -> set[str]:
    with path.open("r", encoding="utf-8", newline="") as handle:
        return {row["symbol"] for row in csv.DictReader(handle)}


def login() -> None:
    result = bs.login()
    if result.error_code != "0":
        raise RuntimeError(f"BaoStock login: {result.error_code} {result.error_msg}")


def source_rows(query) -> list[dict]:
    if query.error_code != "0":
        raise RuntimeError(f"BaoStock query: {query.error_code} {query.error_msg}")
    rows = []
    while query.next():
        rows.append(dict(zip(query.fields, query.get_row_data())))
    if query.error_code != "0":
        raise RuntimeError(f"BaoStock iterate: {query.error_code} {query.error_msg}")
    return rows


def symbol_for_code(code: str) -> str:
    market, number = code.split(".")
    return f"{number}.{market.upper()}"


def freeze() -> None:
    assignment_path = HERE / "historical_assignment.csv"
    if assignment_path.exists():
        raise FileExistsError(f"Frozen assignment exists: {assignment_path}")
    excluded = set()
    exclusion_hashes = {}
    for path in EXCLUSION_FILES:
        excluded |= read_symbols(path)
        exclusion_hashes[str(path.relative_to(ROOT))] = digest(path)
    snapshot_rows = []
    assignment = []
    already_chosen = set()
    login()
    try:
        for snapshot_day in SNAPSHOTS:
            data = source_rows(bs.query_all_stock(day=snapshot_day))
            main = {}
            for raw in data:
                code = raw["code"].lower()
                if not MAINBOARD.fullmatch(code):
                    continue
                symbol = symbol_for_code(code)
                if symbol in main:
                    raise ValueError(f"duplicate historical member {snapshot_day} {symbol}")
                main[symbol] = {"snapshot_date": snapshot_day, "symbol": symbol,
                                "snapshot_trade_status": raw["tradeStatus"]}
            snapshot_rows.extend(main[symbol] for symbol in sorted(main))
            for exchange in ("SH", "SZ"):
                pool = [symbol for symbol in main if symbol.endswith(f".{exchange}")
                        and symbol not in excluded and symbol not in already_chosen]
                pool.sort(key=lambda symbol: (hashlib.sha256(f"{SEED}|{snapshot_day}|{symbol}".encode()).hexdigest(), symbol))
                if len(pool) < PER_CELL:
                    raise RuntimeError(f"Too few members: {snapshot_day} {exchange}: {len(pool)}")
                for symbol in pool[:PER_CELL]:
                    assignment.append({"symbol": symbol, "snapshot_date": snapshot_day,
                                       "exchange": exchange, "snapshot_trade_status": main[symbol]["snapshot_trade_status"],
                                       "cohort": "historical_point_in_time_development"})
                    already_chosen.add(symbol)
    finally:
        bs.logout()
    assignment.sort(key=lambda row: (row["snapshot_date"], row["exchange"], row["symbol"]))
    membership_path = HERE / "historical_snapshot_membership.csv"
    write_csv(membership_path, snapshot_rows, ["snapshot_date", "symbol", "snapshot_trade_status"])
    write_csv(assignment_path, assignment, ["symbol", "snapshot_date", "exchange", "snapshot_trade_status", "cohort"])
    manifest = {
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "historical membership assignment disjoint from prior and sealed stock cohorts; known future-delisted cohort excluded",
        "source": "BaoStock 0.9.4 query_all_stock(day)",
        "snapshot_dates": SNAPSHOTS, "per_snapshot_exchange_cell": PER_CELL, "seed": SEED,
        "selection": "SHA256(seed|snapshot_date|symbol) ascending; mainboard code regex; exclude prior exposed, sealed, and known future-delisted symbols; no name or price filter",
        "assigned_symbols": len(assignment),
        "snapshot_mainboard_members": {day: sum(row["snapshot_date"] == day for row in snapshot_rows) for day in SNAPSHOTS},
        "excluded_files_sha256": exclusion_hashes,
        "sha256": {path.name: digest(path) for path in (membership_path, assignment_path)},
        "sealed_validation_access": "assignment symbols only; no sealed quote, factor, return or label read",
        "caveat": "Excluding known future-delisted symbols introduces survival selection bias. BaoStock historical code_name appears backfilled from present and is not used or stored. Historical membership is source reported, not independently exchange-verified.",
    }
    (HERE / "historical_assignment_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: manifest[key] for key in ("assigned_symbols", "snapshot_mainboard_members", "sha256")}, ensure_ascii=False), flush=True)


def get_history(item: dict) -> list[dict]:
    symbol = item["symbol"]
    code, exchange = symbol.split(".")
    query = bs.query_history_k_data_plus(
        f"{exchange.lower()}.{code}", HISTORY_FIELDS,
        start_date=item["snapshot_date"], end_date=END, frequency="d", adjustflag="3",
    )
    result = []
    seen = set()
    for raw in source_rows(query):
        day = raw["date"]
        if day in seen or raw["code"] != f"{exchange.lower()}.{code}" or raw["adjustflag"] != "3":
            raise ValueError(f"duplicate or unexpected record: {symbol} {day}")
        seen.add(day)
        result.append({
            "symbol": symbol, "trade_date": day,
            "open": raw["open"], "high": raw["high"], "low": raw["low"], "close": raw["close"],
            "pre_close": raw["preclose"], "volume_shares": raw["volume"], "amount_yuan": raw["amount"],
            "adjust_flag": raw["adjustflag"], "trade_status": raw["tradestatus"], "is_st": raw["isST"],
            "source": "BaoStock:query_history_k_data_plus:adjustflag=3",
        })
    return result


def fetch() -> None:
    assignment_path = HERE / "historical_assignment.csv"
    manifest_path = HERE / "historical_assignment_manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if digest(assignment_path) != manifest["sha256"][assignment_path.name]:
        raise ValueError("Historical assignment changed after freeze")
    with assignment_path.open("r", encoding="utf-8", newline="") as handle:
        assignment = list(csv.DictReader(handle))
    if len(assignment) != len({row["symbol"] for row in assignment}):
        raise ValueError("duplicate assigned symbols")
    data_path = HERE / "historical_unadjusted_baostock.csv"
    if data_path.exists():
        raise FileExistsError(f"Raw quote file already exists: {data_path}")
    all_rows = []
    coverage = []
    errors = {}
    login()
    try:
        for index, item in enumerate(assignment, 1):
            last_error = None
            rows = []
            for attempt in range(3):
                try:
                    rows = get_history(item)
                    last_error = None
                    break
                except Exception as exc:
                    last_error = f"{type(exc).__name__}: {exc}"
                    time.sleep(attempt + 1)
            if last_error:
                errors[item["symbol"]] = last_error
            all_rows.extend(rows)
            coverage.append({**item, "rows": len(rows),
                             "first_date": rows[0]["trade_date"] if rows else "",
                             "last_date": rows[-1]["trade_date"] if rows else "",
                             "error": last_error or ""})
            if index % 10 == 0 or index == len(assignment):
                print(f"{index}/{len(assignment)}: rows={len(all_rows)} errors={len(errors)}", flush=True)
    finally:
        bs.logout()
    all_rows.sort(key=lambda row: (row["symbol"], row["trade_date"]))
    coverage_path = HERE / "historical_baostock_coverage.csv"
    write_csv(data_path, all_rows, OUTPUT_FIELDS)
    write_csv(coverage_path, coverage,
              ["symbol", "snapshot_date", "exchange", "snapshot_trade_status", "cohort",
               "rows", "first_date", "last_date", "error"])
    status_counts = Counter((row["trade_status"], row["is_st"]) for row in all_rows)
    result = {
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "outcome-free historical point-in-time development stock quotes",
        "source": "BaoStock 0.9.4 query_history_k_data_plus",
        "query": {"fields": HISTORY_FIELDS, "start_date": "each assigned snapshot date", "end_date": END,
                  "frequency": "d", "adjustflag": "3"},
        "assignment_sha256": digest(assignment_path),
        "assigned_symbols": len(assignment), "symbols_with_rows": sum(row["rows"] > 0 for row in coverage),
        "rows": len(all_rows),
        "status_counts": {f"tradestatus={status},isST={is_st}": n for (status, is_st), n in status_counts.items()},
        "fetch_errors": errors,
        "sha256": {path.name: digest(path) for path in (data_path, coverage_path)},
        "sealed_validation_access": "none",
        "caveats": ["Unadjusted prices need corporate-action handling for returns and rolling factors.",
                    "tradestatus=0 marks non-executable bars.",
                    "Daily isST is source reported and has not been independently exchange verified."],
    }
    (HERE / "historical_baostock_manifest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("assigned_symbols", "symbols_with_rows", "rows", "status_counts", "fetch_errors")}, ensure_ascii=False), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("freeze", "fetch"))
    args = parser.parse_args()
    HERE.mkdir(parents=True, exist_ok=True)
    socket.setdefaulttimeout(30)
    if args.stage == "freeze":
        freeze()
    else:
        fetch()


if __name__ == "__main__":
    main()
