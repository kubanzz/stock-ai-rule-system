"""Audit raw historical sample integrity and existing normal-stock coverage.

This script does not calculate signals, forward returns, prediction labels,
or outcomes. All eligibility fields are measured using T-or-earlier bars.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pandas as pd


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.normal_universe import add_normal_universe_eligibility  # noqa: E402


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    source = HERE / "historical_unadjusted_baostock.csv"
    assignment_path = HERE / "historical_assignment.csv"
    manifest = json.loads((HERE / "historical_baostock_manifest.json").read_text(encoding="utf-8"))
    assignment_manifest = json.loads((HERE / "historical_assignment_manifest.json").read_text(encoding="utf-8"))
    if sha256(source) != manifest["sha256"][source.name]:
        raise ValueError("raw source SHA mismatch")
    if sha256(assignment_path) != assignment_manifest["sha256"][assignment_path.name]:
        raise ValueError("assignment SHA mismatch")
    frame = pd.read_csv(source, dtype={"symbol": str, "trade_status": str, "is_st": str})
    assignment = pd.read_csv(assignment_path, dtype={"symbol": str})
    frame["trade_date"] = pd.to_datetime(frame["trade_date"])
    assignment["snapshot_date"] = pd.to_datetime(assignment["snapshot_date"])
    if frame.duplicated(["symbol", "trade_date"]).any():
        raise ValueError("duplicate symbol-date")
    if set(frame["symbol"]) != set(assignment["symbol"]):
        raise ValueError("symbols with no rows or extra symbols")
    merged = frame.merge(assignment[["symbol", "snapshot_date", "exchange"]], on="symbol", validate="many_to_one")
    if (merged["trade_date"] < merged["snapshot_date"]).any():
        raise ValueError("quotes before historical cohort assignment date")
    traded = merged.loc[merged["trade_status"].eq("1")].copy()
    price_cols = ["open", "high", "low", "close"]
    valid = traded.dropna(subset=price_cols + ["volume_shares", "amount_yuan"]).copy()
    valid = valid.loc[valid[price_cols + ["volume_shares", "amount_yuan"]].gt(0).all(axis=1)].copy()
    tolerance = .011
    ohcl_bad = valid.loc[
        valid["low"].gt(valid[["open", "close"]].min(axis=1) + tolerance)
        | valid["high"].lt(valid[["open", "close"]].max(axis=1) - tolerance)
        | valid["low"].gt(valid["high"])
    ]
    vwap = valid["amount_yuan"] / valid["volume_shares"]
    vwap_bad = valid.loc[vwap.lt(valid["low"] - tolerance) | vwap.gt(valid["high"] + tolerance)]
    normal = add_normal_universe_eligibility(
        valid.rename(columns={"close": "close_price", "amount_yuan": "amount"})
    )
    eligible = normal.loc[normal["normal_universe_eligible"] & normal["is_st"].eq("0")].copy()
    by_snapshot_year = {}
    for (snapshot, year), group in normal.groupby(["snapshot_date", normal["trade_date"].dt.year]):
        good = group.loc[group["normal_universe_eligible"] & group["is_st"].eq("0")]
        by_snapshot_year[f"{snapshot.date()}_{year}"] = {
            "valid_traded_rows": len(group), "normal_nonST_rows": len(good),
            "normal_nonST_symbols": int(good["symbol"].nunique()),
            "normal_nonST_dates": int(good["trade_date"].nunique()),
        }
    report = {
        "purpose": "source quality and point-in-time normal-stock coverage only; no labels or outcomes",
        "source_sha256": sha256(source), "assignment_sha256": sha256(assignment_path),
        "assigned_symbols": len(assignment), "raw_rows": len(frame),
        "traded_rows": len(traded), "valid_traded_rows": len(valid),
        "invalid_or_missing_traded_rows": len(traded) - len(valid),
        "ohlc_outside_tolerance_rows": len(ohcl_bad),
        "amount_div_volume_outside_daily_low_high_rows": len(vwap_bad),
        "normal_nonST_rows": len(eligible),
        "normal_nonST_symbols": int(eligible["symbol"].nunique()),
        "normal_nonST_dates": int(eligible["trade_date"].nunique()),
        "by_snapshot_year": by_snapshot_year,
        "sealed_validation_access": "none",
        "qualification": "historical snapshot membership plus source T-day tradestatus=1/isST=0 and pre-existing mainboard_liquid_stable_v1 eligibility, no forward bars",
        "caveats": [
            "This is an 80-symbol reproducible sample, not all historically listed mainboard stocks; known future-delisted stocks were excluded before sampling.",
            "BaoStock's historical membership and daily isST have not been independently exchange verified.",
            "Unadjusted price jumps from corporate actions can distort rolling factors and future return labels.",
        ],
    }
    path = HERE / "historical_coverage.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in (
        "assigned_symbols", "raw_rows", "valid_traded_rows", "ohlc_outside_tolerance_rows",
        "amount_div_volume_outside_daily_low_high_rows", "normal_nonST_rows", "normal_nonST_symbols",
        "by_snapshot_year",
    )}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
