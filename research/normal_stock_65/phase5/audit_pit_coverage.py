"""Verify assignment reproducibility and raw quote quality without outcome labels."""

from __future__ import annotations

import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
MEMBERSHIP = ROOT / "research/normal_stock_65/phase4/historical_snapshot_membership.csv"
PREVIOUS_80 = ROOT / "research/normal_stock_65/phase4/historical_assignment.csv"
EXCLUSION_FILES = (
    ROOT / "research/one_day_20260930/universe.csv",
    ROOT / "research/normal_stock_65/universe.csv",
    ROOT / "research/normal_stock_65/phase2/cohort_assignment.csv",
)
SNAPSHOTS = ("2019-01-02", "2023-01-03")
PER_CELL = 25
SEED = "pit-mainboard-v1-20260930"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_csv(path: Path) -> list[dict[str, str]]:
    return pd.read_csv(path, dtype=str).fillna("").to_dict("records")


def is_mainboard(symbol: str) -> bool:
    return (symbol.endswith(".SH") and symbol[:3] in {"600", "601", "603", "605"}
            or symbol.endswith(".SZ") and symbol[:3] in {"000", "001", "002", "003"})

sys.path.insert(0, str(ROOT))
from research.normal_stock_65.normal_universe import add_normal_universe_eligibility  # noqa: E402


def verify_assignment(assignment: pd.DataFrame) -> None:
    excluded = {row["symbol"] for path in EXCLUSION_FILES for row in read_csv(path)}
    membership = read_csv(MEMBERSHIP)
    expected = []
    chosen = set()
    for snapshot in SNAPSHOTS:
        for exchange in ("SH", "SZ"):
            pool = [row for row in membership if row["snapshot_date"] == snapshot
                    and row["symbol"].endswith(f".{exchange}")
                    and is_mainboard(row["symbol"])
                    and row["symbol"] not in excluded and row["symbol"] not in chosen]
            pool.sort(key=lambda row: (hashlib.sha256(
                f"{SEED}|{snapshot}|{row['symbol']}".encode()).hexdigest(), row["symbol"]))
            selected = [row["symbol"] for row in pool[:PER_CELL]]
            expected.extend(selected)
            chosen.update(selected)
    if set(assignment["symbol"]) != set(expected) or len(assignment) != len(expected):
        raise ValueError("Frozen assignment differs from deterministic historical-member selection")


def main() -> None:
    assignment_path = HERE / "pit_assignment.csv"
    data_path = HERE / "pit_unadjusted_baostock.csv"
    assignment_manifest = json.loads((HERE / "pit_assignment_manifest.json").read_text(encoding="utf-8"))
    quote_manifest = json.loads((HERE / "pit_baostock_manifest.json").read_text(encoding="utf-8"))
    if digest(assignment_path) != assignment_manifest["assignment_sha256"]:
        raise ValueError("Assignment SHA mismatch")
    if digest(data_path) != quote_manifest["sha256"][data_path.name]:
        raise ValueError("Quote SHA mismatch")
    for path in (MEMBERSHIP, *EXCLUSION_FILES):
        if digest(path) != assignment_manifest["input_sha256"][str(path.relative_to(ROOT))]:
            raise ValueError(f"Assignment input changed: {path}")
    assignment = pd.read_csv(assignment_path, dtype={"symbol": str})
    assignment["snapshot_date"] = pd.to_datetime(assignment["snapshot_date"])
    verify_assignment(assignment)
    raw = pd.read_csv(data_path, dtype={"symbol": str, "trade_status": str, "is_st": str})
    raw["trade_date"] = pd.to_datetime(raw["trade_date"])
    if raw.duplicated(["symbol", "trade_date"]).any():
        raise ValueError("Duplicate symbol-date rows")
    if set(raw["symbol"]) - set(assignment["symbol"]):
        raise ValueError("Unexpected quote symbols")
    frame = raw.merge(assignment[["symbol", "snapshot_date", "exchange"]],
                      on="symbol", how="left", validate="many_to_one")
    if (frame["trade_date"] < frame["snapshot_date"]).any():
        raise ValueError("Quote precedes snapshot membership")
    if not frame["adjust_flag"].eq(3).all():
        raise ValueError("Mixed quote adjustment flags")
    traded = frame.loc[frame["trade_status"].eq("1")].copy()
    prices = ["open", "high", "low", "close"]
    valid = traded.dropna(subset=prices + ["volume_shares", "amount_yuan"])
    valid = valid.loc[valid[prices + ["volume_shares", "amount_yuan"]].gt(0).all(axis=1)].copy()
    tol = .011
    ohlc_bad = valid.loc[(valid["low"] > valid[["open", "close"]].min(axis=1) + tol)
                         | (valid["high"] < valid[["open", "close"]].max(axis=1) - tol)
                         | (valid["low"] > valid["high"])]
    vwap = valid["amount_yuan"] / valid["volume_shares"]
    amount_bad = valid.loc[(vwap < valid["low"] - tol) | (vwap > valid["high"] + tol)]
    normal = add_normal_universe_eligibility(
        valid.rename(columns={"close": "close_price", "amount_yuan": "amount"}))
    normal_non_st = normal.loc[normal["normal_universe_eligible"] & normal["is_st"].eq("0")]
    by_snapshot_year = {}
    for (snapshot, year), group in normal.groupby(["snapshot_date", normal["trade_date"].dt.year]):
        eligible = group.loc[group["normal_universe_eligible"] & group["is_st"].eq("0")]
        by_snapshot_year[f"{snapshot.date()}_{year}"] = {
            "valid_traded_rows": len(group),
            "normal_nonST_rows": len(eligible),
            "normal_nonST_symbols": int(eligible["symbol"].nunique()),
            "normal_nonST_dates": int(eligible["trade_date"].nunique()),
        }
    # Future-delisted identity is consulted only now, after the assignment and
    # quote collection are frozen; it was not a selection input.
    delisted_path = ROOT / "research/normal_stock_65/phase3/data_quality/delisted_assignment.csv"
    known_future_delisted = {row["symbol"] for row in read_csv(delisted_path)}
    selected_delisted = sorted(set(assignment["symbol"]) & known_future_delisted)
    previous = {row["symbol"] for row in read_csv(PREVIOUS_80)}
    report = {
        "purpose": "point-in-time cohort and raw data integrity only; no labels, forward returns or rule fitting",
        "assignment_sha256": digest(assignment_path),
        "data_sha256": digest(data_path),
        "assigned_symbols": len(assignment),
        "quote_symbols": int(raw["symbol"].nunique()),
        "raw_rows": len(raw),
        "traded_rows": len(traded),
        "valid_traded_rows": len(valid),
        "ohlc_bad_rows": len(ohlc_bad),
        "amount_div_volume_outside_ohlc_rows": len(amount_bad),
        "status_counts": {f"tradestatus={status},isST={is_st}": n
                          for (status, is_st), n in sorted(Counter(zip(raw["trade_status"], raw["is_st"])).items())},
        "selected_known_future_delisted_count": len(selected_delisted),
        "selected_known_future_delisted_symbols": selected_delisted,
        "selected_overlap_phase4_80_count": len(set(assignment["symbol"]) & previous),
        "normal_nonST_rows": len(normal_non_st),
        "normal_nonST_symbols": int(normal_non_st["symbol"].nunique()),
        "normal_nonST_dates": int(normal_non_st["trade_date"].nunique()),
        "by_snapshot_year": by_snapshot_year,
        "sealed_validation_access": "phase2 assignment symbol identities only; no sealed quotes or outcomes",
        "caveats": ["This is a 100-symbol sample rather than the full point-in-time universe.",
                    "Membership and daily ST flags are BaoStock reported, not exchange independently verified.",
                    "The phase4 historical cohort has stock overlap; do not treat this entire cohort as independent of phase4.",
                    "Unadjusted corporate-action price jumps need separate treatment before return labels."],
    }
    target = HERE / "pit_coverage.json"
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in (
        "assigned_symbols", "quote_symbols", "raw_rows", "valid_traded_rows", "ohlc_bad_rows",
        "amount_div_volume_outside_ohlc_rows", "selected_known_future_delisted_count",
        "normal_nonST_rows", "normal_nonST_symbols")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
