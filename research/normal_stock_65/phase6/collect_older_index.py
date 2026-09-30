"""Collect the pre-2019 HS300 trading calendar and fixed T-day index feature.

No stock labels, rule outcomes, or sealed-cohort data are read by this script.
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
START, END = "2010-01-01", "2019-01-07"
FIELDS = "date,code,open,high,low,close,preclose,volume,amount,adjustflag,tradestatus"
OUTPUT = ["trade_date", "open", "high", "low", "close", "pre_close",
          "volume_shares", "amount_yuan", "trade_status", "source"]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    output = HERE / "hs300_older_unadjusted_baostock.csv"
    manifest_path = HERE / "hs300_older_manifest.json"
    if output.exists() or manifest_path.exists():
        raise FileExistsError("Older HS300 series is already frozen")
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
                raise ValueError(f"Duplicate or unexpected index row: {day}")
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
        raise RuntimeError("No older HS300 index records")
    rows.sort(key=lambda row: row["trade_date"])
    with output.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=OUTPUT)
        writer.writeheader()
        writer.writerows(rows)
    manifest = {
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "pre-2019 trading calendar and fixed T-close market feature; no stock outcomes",
        "source": "BaoStock 0.9.4 query_history_k_data_plus",
        "query": {"code": "sh.000300", "fields": FIELDS, "start_date": START,
                  "end_date": END, "frequency": "d", "adjustflag": "3"},
        "rows": len(rows), "first_date": rows[0]["trade_date"],
        "last_date": rows[-1]["trade_date"],
        "sha256": {output.name: digest(output)},
        "sealed_validation_access": "none",
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
