"""Collect a fixed HS300 price series for pre-2023 regime research.

This collector uses source prices only. It does not read stock prediction
labels, the sealed stock cohort, or any forward returns.
"""

from __future__ import annotations

import csv
import hashlib
import json
import socket
from datetime import datetime, timezone
from pathlib import Path

import baostock as bs


HERE = Path(__file__).resolve().parent
START, END = "2019-01-01", "2026-09-29"
FIELDS = "date,code,open,high,low,close,preclose,volume,amount,adjustflag,tradestatus"
OUTPUT = ["trade_date", "open", "high", "low", "close", "pre_close", "volume_shares", "amount_yuan", "trade_status", "source"]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    HERE.mkdir(parents=True, exist_ok=True)
    socket.setdefaulttimeout(30)
    login = bs.login()
    if login.error_code != "0":
        raise RuntimeError(f"BaoStock login: {login.error_code} {login.error_msg}")
    try:
        query = bs.query_history_k_data_plus(
            "sh.000300", FIELDS, start_date=START, end_date=END,
            frequency="d", adjustflag="3",
        )
        if query.error_code != "0":
            raise RuntimeError(f"BaoStock query: {query.error_code} {query.error_msg}")
        rows = []
        seen = set()
        while query.next():
            raw = dict(zip(query.fields, query.get_row_data()))
            day = raw["date"]
            if day in seen or raw["code"] != "sh.000300" or raw["adjustflag"] != "3":
                raise ValueError(f"duplicate or unexpected index record: {raw}")
            seen.add(day)
            rows.append({
                "trade_date": day, "open": raw["open"], "high": raw["high"],
                "low": raw["low"], "close": raw["close"],
                "pre_close": raw["preclose"], "volume_shares": raw["volume"],
                "amount_yuan": raw["amount"], "trade_status": raw["tradestatus"],
                "source": "BaoStock:query_history_k_data_plus:sh.000300:adjustflag=3",
            })
        if query.error_code != "0":
            raise RuntimeError(f"BaoStock iterate: {query.error_code} {query.error_msg}")
    finally:
        bs.logout()
    if not rows:
        raise RuntimeError("No HS300 index records")
    rows.sort(key=lambda row: row["trade_date"])
    path = HERE / "hs300_unadjusted_baostock.csv"
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=OUTPUT)
        writer.writeheader()
        writer.writerows(rows)
    manifest = {
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "historical market-regime feature availability only; no stock labels or rule outcomes",
        "source": "BaoStock 0.9.4 query_history_k_data_plus",
        "query": {"code": "sh.000300", "fields": FIELDS, "start_date": START, "end_date": END, "frequency": "d", "adjustflag": "3"},
        "rows": len(rows), "first_date": rows[0]["trade_date"], "last_date": rows[-1]["trade_date"],
        "trade_status_counts": {status: sum(row["trade_status"] == status for row in rows) for status in sorted({row["trade_status"] for row in rows})},
        "sha256": {path.name: digest(path)},
        "sealed_validation_access": "none",
    }
    (HERE / "hs300_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == "__main__":
    main()
