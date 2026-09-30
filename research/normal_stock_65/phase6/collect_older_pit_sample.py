"""Freeze a 2010/2014 point-in-time mainboard cohort, then fetch daily history.

The assignment reads historical membership and *symbol identities* from prior
cohorts. It does not read quotes, labels, future delisting status, or the sealed
cohort's outcomes. In particular, it never uses the known delisted-stock list.

Usage::

    PYTHONPATH=/tmp/stock_data_quality_baostock python3 -m research.normal_stock_65.phase6.collect_older_pit_sample freeze
    PYTHONPATH=/tmp/stock_data_quality_baostock python3 -m research.normal_stock_65.phase6.collect_older_pit_sample fetch
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
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
MEMBERSHIP = HERE / "older_snapshot_membership.csv"
SNAPSHOTS = ("2010-01-04", "2014-01-02")
PER_CELL = 25
SEED = "older-pit-mainboard-v1-20260930"
END = "2019-01-07"
MAINBOARD = re.compile(r"(?:(?:600|601|603|605)\d{3}\.SH|(?:000|001|002|003)\d{3}\.SZ)\Z")
HISTORY_FIELDS = "date,code,open,high,low,close,preclose,volume,amount,adjustflag,tradestatus,isST"
OUTPUT_FIELDS = ["symbol", "trade_date", "open", "high", "low", "close", "pre_close",
                 "volume_shares", "amount_yuan", "adjust_flag", "trade_status", "is_st", "source"]
EXCLUSION_FILES = (
    ROOT / "research/one_day_20260930/universe.csv",  # old166
    ROOT / "research/normal_stock_65/universe.csv",  # first250
    ROOT / "research/normal_stock_65/phase2/cohort_assignment.csv",  # dev200 + sealed100
    ROOT / "research/normal_stock_65/phase4/historical_assignment.csv",
    ROOT / "research/normal_stock_65/phase5/pit_assignment.csv",
)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def csv_rows_digest(rows: list[dict], fields: list[str]) -> str:
    handle = io.StringIO(newline="")
    writer = csv.DictWriter(handle, fieldnames=fields)
    writer.writeheader()
    writer.writerows(rows)
    return hashlib.sha256(handle.getvalue().encode("utf-8")).hexdigest()


def freeze() -> None:
    assignment_path = HERE / "pit_older_assignment.csv"
    manifest_path = HERE / "pit_older_assignment_manifest.json"
    if assignment_path.exists() or manifest_path.exists() or MEMBERSHIP.exists():
        raise FileExistsError("A frozen point-in-time assignment already exists")
    excluded: set[str] = set()
    for path in EXCLUSION_FILES:
        excluded.update(row["symbol"] for row in read_csv(path))
    membership: list[dict[str, str]] = []
    login = bs.login()
    if login.error_code != "0":
        raise RuntimeError(f"BaoStock login: {login.error_code} {login.error_msg}")
    try:
        for snapshot in SNAPSHOTS:
            seen: set[str] = set()
            for raw in source_rows(bs.query_all_stock(day=snapshot)):
                code = raw["code"].lower()
                market, number = code.split(".")
                symbol = f"{number}.{market.upper()}"
                if not MAINBOARD.fullmatch(symbol):
                    continue
                if symbol in seen:
                    raise ValueError(f"Duplicate snapshot member: {snapshot} {symbol}")
                seen.add(symbol)
                membership.append({"snapshot_date": snapshot, "symbol": symbol,
                                   "snapshot_trade_status": raw["tradeStatus"]})
    finally:
        bs.logout()
    membership.sort(key=lambda row: (row["snapshot_date"], row["symbol"]))
    selected: list[dict] = []
    chosen: set[str] = set()
    cell_sizes: dict[str, int] = {}
    for snapshot in SNAPSHOTS:
        for exchange in ("SH", "SZ"):
            pool = [row for row in membership
                    if row["snapshot_date"] == snapshot
                    and row["symbol"].endswith(f".{exchange}")
                    and MAINBOARD.fullmatch(row["symbol"])
                    and row["symbol"] not in excluded
                    and row["symbol"] not in chosen]
            pool.sort(key=lambda row: (hashlib.sha256(
                f"{SEED}|{snapshot}|{row['symbol']}".encode()).hexdigest(), row["symbol"]))
            cell_sizes[f"{snapshot}_{exchange}"] = len(pool)
            if len(pool) < PER_CELL:
                raise RuntimeError(f"Too few members: {snapshot} {exchange}: {len(pool)}")
            for row in pool[:PER_CELL]:
                selected.append({"symbol": row["symbol"], "snapshot_date": snapshot,
                                 "exchange": exchange,
                                 "snapshot_trade_status": row["snapshot_trade_status"],
                                 "cohort": "older_point_in_time_development"})
                chosen.add(row["symbol"])
    selected.sort(key=lambda row: (row["snapshot_date"], row["exchange"], row["symbol"]))
    write_csv(MEMBERSHIP, membership, ["snapshot_date", "symbol", "snapshot_trade_status"])
    write_csv(assignment_path, selected,
              ["symbol", "snapshot_date", "exchange", "snapshot_trade_status", "cohort"])
    manifest = {
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "older point-in-time mainboard development sample allowing future delistings",
        "membership_source": "BaoStock 0.9.4 query_all_stock(day), frozen before quote fetching",
        "snapshot_dates": SNAPSHOTS,
        "per_snapshot_exchange_cell": PER_CELL,
        "seed": SEED,
        "selection": "SHA256(seed|snapshot_date|symbol) ascending; mainboard code regex; exclude old166, first250, phase2 dev200 and sealed100, phase4 historical80 and phase5 PIT100 by symbol; no future-status, name, price, or quote filter",
        "members_by_snapshot": {day: sum(row["snapshot_date"] == day for row in membership) for day in SNAPSHOTS},
        "cell_available_count": cell_sizes,
        "assigned_symbols": len(selected),
        "selected_snapshot_status_counts": dict(Counter(row["snapshot_trade_status"] for row in selected)),
        "input_sha256": {str(path.relative_to(ROOT)): digest(path) for path in (MEMBERSHIP, *EXCLUSION_FILES)},
        "assignment_sha256": digest(assignment_path),
        "sealed_validation_access": "phase2 assignment symbol identities only; no sealed quotes or outcomes",
        "caveats": ["BaoStock historical membership has not been independently exchange verified.",
                    "The prior and sealed cohort symbol exclusions are by design; this is not an all-market random sample.",
                    "The 2010/2014 snapshot dates and seed were fixed before fetching these stock histories."],
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"assigned_symbols": len(selected), "members_by_snapshot": manifest["members_by_snapshot"],
                      "cell_available_count": cell_sizes,
                      "assignment_sha256": manifest["assignment_sha256"]}, ensure_ascii=False), flush=True)


def source_rows(query) -> list[dict[str, str]]:
    if query.error_code != "0":
        raise RuntimeError(f"BaoStock query: {query.error_code} {query.error_msg}")
    rows = []
    while query.next():
        rows.append(dict(zip(query.fields, query.get_row_data())))
    if query.error_code != "0":
        raise RuntimeError(f"BaoStock iterate: {query.error_code} {query.error_msg}")
    return rows


def fetch_one(item: dict[str, str]) -> list[dict[str, str]]:
    symbol = item["symbol"]
    number, exchange = symbol.split(".")
    source_code = f"{exchange.lower()}.{number}"
    query = bs.query_history_k_data_plus(source_code, HISTORY_FIELDS,
                                          start_date=item["snapshot_date"],
                                          end_date=END, frequency="d", adjustflag="3")
    rows = []
    seen: set[str] = set()
    for raw in source_rows(query):
        day = raw["date"]
        if day in seen or raw["code"] != source_code or raw["adjustflag"] != "3":
            raise ValueError(f"Duplicate or unexpected quote: {symbol} {day}")
        seen.add(day)
        rows.append({"symbol": symbol, "trade_date": day,
                     "open": raw["open"], "high": raw["high"], "low": raw["low"],
                     "close": raw["close"], "pre_close": raw["preclose"],
                     "volume_shares": raw["volume"], "amount_yuan": raw["amount"],
                     "adjust_flag": raw["adjustflag"], "trade_status": raw["tradestatus"],
                     "is_st": raw["isST"],
                     "source": "BaoStock:query_history_k_data_plus:adjustflag=3"})
    return rows


def fetch() -> None:
    assignment_path = HERE / "pit_older_assignment.csv"
    assignment_manifest = json.loads((HERE / "pit_older_assignment_manifest.json").read_text(encoding="utf-8"))
    if digest(assignment_path) != assignment_manifest["assignment_sha256"]:
        raise ValueError("Point-in-time assignment changed after freeze")
    rows = read_csv(assignment_path)
    if len(rows) != len({row["symbol"] for row in rows}):
        raise ValueError("Duplicate assigned symbols")
    data_path = HERE / "pit_older_unadjusted_baostock.csv"
    coverage_path = HERE / "pit_older_baostock_coverage.csv"
    manifest_path = HERE / "pit_older_baostock_manifest.json"
    if any(path.exists() for path in (data_path, coverage_path, manifest_path)):
        raise FileExistsError("Point-in-time quote output already exists")
    output: list[dict[str, str]] = []
    coverage: list[dict[str, str | int]] = []
    errors: dict[str, str] = {}
    login = bs.login()
    if login.error_code != "0":
        raise RuntimeError(f"BaoStock login: {login.error_code} {login.error_msg}")
    try:
        for index, item in enumerate(rows, 1):
            last_error = None
            stock_rows: list[dict[str, str]] = []
            for attempt in range(3):
                try:
                    stock_rows = fetch_one(item)
                    last_error = None
                    break
                except Exception as exc:
                    last_error = f"{type(exc).__name__}: {exc}"
                    bs.logout()
                    login = bs.login()
                    if login.error_code != "0":
                        raise RuntimeError(f"BaoStock relogin: {login.error_code} {login.error_msg}")
                    time.sleep(attempt + 1)
            if last_error:
                errors[item["symbol"]] = last_error
            output.extend(stock_rows)
            coverage.append({**item, "rows": len(stock_rows),
                             "first_date": stock_rows[0]["trade_date"] if stock_rows else "",
                             "last_date": stock_rows[-1]["trade_date"] if stock_rows else "",
                             "error": last_error or ""})
            if index % 10 == 0 or index == len(rows):
                print(f"{index}/{len(rows)}: rows={len(output)} errors={len(errors)}", flush=True)
            if index % 20 == 0 and index != len(rows):
                bs.logout()
                login = bs.login()
                if login.error_code != "0":
                    raise RuntimeError(f"BaoStock periodic relogin: {login.error_code} {login.error_msg}")
    finally:
        bs.logout()
    output.sort(key=lambda row: (row["symbol"], row["trade_date"]))
    write_csv(data_path, output, OUTPUT_FIELDS)
    write_csv(coverage_path, coverage,
              ["symbol", "snapshot_date", "exchange", "snapshot_trade_status", "cohort",
               "rows", "first_date", "last_date", "error"])
    status = Counter((row["trade_status"], row["is_st"]) for row in output)
    manifest = {
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "point-in-time mainboard cohort raw quote collection; no labels or rule tuning",
        "source": "BaoStock 0.9.4 query_history_k_data_plus",
        "query": {"fields": HISTORY_FIELDS, "start_date": "each assigned snapshot date",
                  "end_date": END, "frequency": "d", "adjustflag": "3"},
        "assignment_sha256": digest(assignment_path),
        "assigned_symbols": len(rows),
        "symbols_with_rows": sum(int(row["rows"] > 0) for row in coverage),
        "rows": len(output),
        "status_counts": {f"tradestatus={trade_status},isST={is_st}": n
                          for (trade_status, is_st), n in sorted(status.items())},
        "fetch_errors": errors,
        "sha256": {path.name: digest(path) for path in (data_path, coverage_path)},
        "sealed_validation_access": "none",
        "caveats": ["Unadjusted prices need corporate-action handling for returns and rolling factors.",
                    "tradestatus=0 marks non-executable bars.",
                    "Daily isST is source reported, not independently exchange verified."],
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"assigned_symbols": len(rows), "symbols_with_rows": manifest["symbols_with_rows"],
                      "rows": len(output), "status_counts": manifest["status_counts"],
                      "fetch_errors": errors}, ensure_ascii=False), flush=True)


def resume_failed() -> None:
    """Retry all failed symbols after a source session expires, without reselection."""
    assignment_path = HERE / "pit_older_assignment.csv"
    data_path = HERE / "pit_older_unadjusted_baostock.csv"
    coverage_path = HERE / "pit_older_baostock_coverage.csv"
    manifest_path = HERE / "pit_older_baostock_manifest.json"
    frozen = json.loads((HERE / "pit_older_assignment_manifest.json").read_text(encoding="utf-8"))
    previous = json.loads(manifest_path.read_text(encoding="utf-8"))
    if digest(assignment_path) != frozen["assignment_sha256"]:
        raise ValueError("Frozen assignment changed")
    if digest(data_path) != previous["sha256"][data_path.name]:
        raise ValueError("Existing quote file changed")
    if digest(coverage_path) != previous["sha256"][coverage_path.name]:
        raise ValueError("Existing coverage file changed")
    assignment = read_csv(assignment_path)
    old = read_csv(data_path)
    old_coverage = {row["symbol"]: row for row in read_csv(coverage_path)}
    pending = [item for item in assignment if item["symbol"] in previous["fetch_errors"]]
    if set(previous["fetch_errors"]) != {item["symbol"] for item in pending}:
        raise ValueError("Failed-symbol set does not match frozen assignment")
    if any(old_coverage[item["symbol"]]["rows"] != "0" for item in pending):
        raise ValueError("Cannot resume a symbol with partial existing data")
    errors = dict(previous["fetch_errors"])
    login = bs.login()
    if login.error_code != "0":
        raise RuntimeError(f"BaoStock login: {login.error_code} {login.error_msg}")
    try:
        for index, item in enumerate(pending, 1):
            rows: list[dict[str, str]] = []
            error = None
            for attempt in range(3):
                try:
                    rows = fetch_one(item)
                    error = None
                    break
                except Exception as exc:
                    error = f"{type(exc).__name__}: {exc}"
                    bs.logout()
                    login = bs.login()
                    if login.error_code != "0":
                        raise RuntimeError(f"BaoStock relogin: {login.error_code} {login.error_msg}")
                    time.sleep(attempt + 1)
            if error is None:
                errors.pop(item["symbol"], None)
                old.extend(rows)
            else:
                errors[item["symbol"]] = error
            old_coverage[item["symbol"]] = {**item, "rows": len(rows),
                                             "first_date": rows[0]["trade_date"] if rows else "",
                                             "last_date": rows[-1]["trade_date"] if rows else "",
                                             "error": error or ""}
            if index % 10 == 0 or index == len(pending):
                print(f"retried {index}/{len(pending)}; remaining errors={len(errors)}", flush=True)
    finally:
        bs.logout()
    old.sort(key=lambda row: (row["symbol"], row["trade_date"]))
    coverage = [old_coverage[item["symbol"]] for item in assignment]
    write_csv(data_path, old, OUTPUT_FIELDS)
    write_csv(coverage_path, coverage,
              ["symbol", "snapshot_date", "exchange", "snapshot_trade_status", "cohort",
               "rows", "first_date", "last_date", "error"])
    status = Counter((row["trade_status"], row["is_st"]) for row in old)
    previous["resumed_utc"] = datetime.now(timezone.utc).isoformat()
    previous["retry_history"] = [{"reason": "BaoStock session expired after 80 symbols",
                                  "symbols_retried": len(pending),
                                  "original_quote_sha256": previous["sha256"][data_path.name],
                                  "original_coverage_sha256": previous["sha256"][coverage_path.name]}]
    previous["symbols_with_rows"] = sum(int(int(row["rows"]) > 0) for row in coverage)
    previous["rows"] = len(old)
    previous["status_counts"] = {f"tradestatus={trade_status},isST={is_st}": n
                                 for (trade_status, is_st), n in sorted(status.items())}
    previous["fetch_errors"] = errors
    previous["sha256"] = {path.name: digest(path) for path in (data_path, coverage_path)}
    manifest_path.write_text(json.dumps(previous, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"symbols_with_rows": previous["symbols_with_rows"],
                      "rows": previous["rows"], "remaining_errors": errors}, ensure_ascii=False), flush=True)


def finalize_interrupted_resume() -> None:
    """Recover when output CSVs were written but manifest update was interrupted."""
    assignment_path = HERE / "pit_older_assignment.csv"
    data_path = HERE / "pit_older_unadjusted_baostock.csv"
    coverage_path = HERE / "pit_older_baostock_coverage.csv"
    manifest_path = HERE / "pit_older_baostock_manifest.json"
    frozen = json.loads((HERE / "pit_older_assignment_manifest.json").read_text(encoding="utf-8"))
    previous = json.loads(manifest_path.read_text(encoding="utf-8"))
    if digest(assignment_path) != frozen["assignment_sha256"]:
        raise ValueError("Frozen assignment changed")
    assignment = read_csv(assignment_path)
    current = read_csv(data_path)
    coverage = read_csv(coverage_path)
    if len(coverage) != len(assignment):
        raise ValueError("Coverage count mismatch")
    if any(row["error"] for row in coverage):
        raise ValueError("Cannot finalize while coverage contains errors")
    prior_failed = set(previous["fetch_errors"])
    if len(prior_failed) == 0:
        raise ValueError("Old manifest did not contain failures")
    prior_rows = [row for row in current if row["symbol"] not in prior_failed]
    if csv_rows_digest(prior_rows, OUTPUT_FIELDS) != previous["sha256"][data_path.name]:
        raise ValueError("Previously collected quote rows differ from old hash")
    coverage_fields = ["symbol", "snapshot_date", "exchange", "snapshot_trade_status", "cohort",
                       "rows", "first_date", "last_date", "error"]
    old_coverage = []
    for row in coverage:
        if row["symbol"] in prior_failed:
            old_coverage.append({**row, "rows": "0", "first_date": "", "last_date": "",
                                 "error": previous["fetch_errors"][row["symbol"]]})
        else:
            old_coverage.append(row)
    if csv_rows_digest(old_coverage, coverage_fields) != previous["sha256"][coverage_path.name]:
        raise ValueError("Previously collected coverage rows differ from old hash")
    count = Counter(row["symbol"] for row in current)
    if set(count) != {row["symbol"] for row in assignment}:
        raise ValueError("Some assigned symbols have no quote rows")
    if any(count[row["symbol"]] != int(row["rows"]) for row in coverage):
        raise ValueError("Coverage counts do not match quote rows")
    dates = [(row["symbol"], row["trade_date"]) for row in current]
    if len(dates) != len(set(dates)):
        raise ValueError("Duplicate symbol-date rows")
    status = Counter((row["trade_status"], row["is_st"]) for row in current)
    previous["resumed_utc"] = datetime.now(timezone.utc).isoformat()
    previous["retry_history"] = [{"reason": "BaoStock session expired after 80 symbols; manifest update interrupted after retry CSV write",
                                  "symbols_retried": len(prior_failed),
                                  "original_quote_sha256": previous["sha256"][data_path.name],
                                  "original_coverage_sha256": previous["sha256"][coverage_path.name],
                                  "prior_data_reconstructed_and_verified": True}]
    previous["symbols_with_rows"] = len(count)
    previous["rows"] = len(current)
    previous["status_counts"] = {f"tradestatus={trade_status},isST={is_st}": n
                                 for (trade_status, is_st), n in sorted(status.items())}
    previous["fetch_errors"] = {}
    previous["sha256"] = {path.name: digest(path) for path in (data_path, coverage_path)}
    manifest_path.write_text(json.dumps(previous, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"symbols_with_rows": len(count), "rows": len(current),
                      "prior_data_reconstructed_and_verified": True}, ensure_ascii=False), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("freeze", "fetch", "resume", "finalize-interrupted-resume"))
    args = parser.parse_args()
    HERE.mkdir(parents=True, exist_ok=True)
    socket.setdefaulttimeout(30)
    if args.stage == "freeze":
        freeze()
    elif args.stage == "resume":
        resume_failed()
    elif args.stage == "finalize-interrupted-resume":
        finalize_interrupted_resume()
    else:
        fetch()


if __name__ == "__main__":
    main()
