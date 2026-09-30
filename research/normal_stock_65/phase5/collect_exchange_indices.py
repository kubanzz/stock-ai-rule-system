"""Collect exchange index bars for development research only.

No stock labels or sealed validation quotes are read. Refuse to overwrite
existing files so their source hashes remain stable once collected.
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
FIELDS = "date,code,open,high,low,close,volume,amount,tradestatus"
INDICES = {"SSE_COMPOSITE": "sh.000001", "SZSE_COMPONENT": "sz.399001"}
OUTPUT = ["index", "code", "trade_date", "open", "high", "low", "close", "volume_shares", "amount_yuan", "trade_status"]


def main() -> None:
    HERE.mkdir(parents=True, exist_ok=True)
    target = HERE / "exchange_indices_unadjusted_baostock.csv"
    manifest = HERE / "exchange_indices_manifest.json"
    if target.exists() or manifest.exists():
        raise FileExistsError("Exchange index source or manifest already exists")
    socket.setdefaulttimeout(30)
    login = bs.login()
    if login.error_code != "0":
        raise RuntimeError(f"BaoStock login: {login.error_code} {login.error_msg}")
    rows = []
    counts = {}
    try:
        for name, code in INDICES.items():
            query = bs.query_history_k_data_plus(code, FIELDS, start_date=START, end_date=END,
                                                 frequency="d", adjustflag="3")
            if query.error_code != "0":
                raise RuntimeError(f"BaoStock query {code}: {query.error_code} {query.error_msg}")
            seen = set()
            while query.next():
                raw = dict(zip(query.fields, query.get_row_data()))
                day = raw["date"]
                if raw["code"] != code or day in seen:
                    raise ValueError(f"Unexpected or duplicate index bar: {code} {day}")
                seen.add(day)
                rows.append({"index": name, "code": code, "trade_date": day,
                             "open": raw["open"], "high": raw["high"], "low": raw["low"],
                             "close": raw["close"], "volume_shares": raw["volume"],
                             "amount_yuan": raw["amount"], "trade_status": raw["tradestatus"]})
            if query.error_code != "0":
                raise RuntimeError(f"BaoStock iteration {code}: {query.error_code} {query.error_msg}")
            counts[name] = len(seen)
    finally:
        bs.logout()
    if not rows or any(n < 1800 for n in counts.values()):
        raise ValueError(f"Insufficient exchange index rows: {counts}")
    rows.sort(key=lambda x: (x["index"], x["trade_date"]))
    with target.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=OUTPUT)
        writer.writeheader()
        writer.writerows(rows)
    data = {"created_utc": datetime.now(timezone.utc).isoformat(),
            "source": "BaoStock 0.9.4 query_history_k_data_plus",
            "query": {"codes": INDICES, "fields": FIELDS, "start": START, "end": END,
                      "frequency": "d", "adjustflag": "3"},
            "rows": counts, "total_rows": len(rows),
            "source_sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
            "sealed_validation_access": "none"}
    manifest.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(data, ensure_ascii=False))


if __name__ == "__main__":
    main()
