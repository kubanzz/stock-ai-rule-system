"""Count historically non-ST normal-stock eligibility in the delisted cohort.

Coverage only: no forward labels, returns, signal rankings, or sealed data.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pandas as pd


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT))
from research.normal_stock_65.normal_universe import add_normal_universe_eligibility  # noqa: E402


SOURCE = HERE / "delisted_unadjusted_baostock.csv"


def main() -> None:
    frame = pd.read_csv(SOURCE, dtype={"symbol": str, "trade_status": str, "is_st": str})
    frame["trade_date"] = pd.to_datetime(frame["trade_date"])
    traded = frame.loc[frame["trade_status"].eq("1")].copy()
    traded = traded.rename(columns={"close": "close_price", "amount_yuan": "amount"})
    traded = traded.dropna(subset=["close_price", "amount", "volume_shares"])
    traded = traded.loc[traded["amount"].gt(0) & traded["close_price"].gt(0) & traded["volume_shares"].gt(0)].copy()
    flagged = add_normal_universe_eligibility(traded)
    base_eligible = flagged.loc[flagged["normal_universe_eligible"]]
    non_st = base_eligible.loc[base_eligible["is_st"].eq("0")]
    terminal_map = pd.read_csv(HERE / "delisted_assignment.csv", dtype={"symbol": str})
    terminal_map["delisting_or_suspension_date"] = pd.to_datetime(terminal_map["delisting_or_suspension_date"])
    non_st = non_st.merge(terminal_map[["symbol", "delisting_or_suspension_date"]], on="symbol", validate="many_to_one")
    non_st = non_st.loc[non_st["trade_date"].le(non_st["delisting_or_suspension_date"])]

    years = {}
    for year, group in flagged.groupby(flagged["trade_date"].dt.year):
        eligible = group.loc[group["normal_universe_eligible"]]
        eligible_non_st = eligible.loc[eligible["is_st"].eq("0")]
        years[str(year)] = {
            "trading_rows": len(group),
            "normal_filter_eligible_rows_before_isST": len(eligible),
            "normal_and_nonST_rows": len(eligible_non_st),
            "normal_and_nonST_symbols": int(eligible_non_st["symbol"].nunique()),
            "normal_and_nonST_dates": int(eligible_non_st["trade_date"].nunique()),
        }
    report = {
        "purpose": "coverage only; no outcomes or forward quote labels",
        "source_sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        "assignment_sha256": hashlib.sha256((HERE / "delisted_assignment.csv").read_bytes()).hexdigest(),
        "raw_rows": len(frame),
        "traded_valid_rows": len(flagged),
        "normal_filter_eligible_rows_before_isST": len(base_eligible),
        "normal_filter_eligible_ST_rows_excluded": int(base_eligible["is_st"].eq("1").sum()),
        "normal_and_nonST_rows": len(non_st),
        "normal_and_nonST_symbols": int(non_st["symbol"].nunique()),
        "normal_and_nonST_dates": int(non_st["trade_date"].nunique()),
        "by_year": years,
        "qualification": "The filter only uses T-or-earlier traded bars, source reported T-day isST, and fixed delisting metadata. Suspended bars do not count as traded history. This is coverage, not a prediction result.",
        "limitations": [
            "The 192-symbol list covers records with terminal dates 2019-2026, not all historically traded A shares.",
            "BaoStock isST/tradestatus are source-reported and have not been independently checked against exchange records.",
            "Unadjusted prices across corporate action dates can distort rolling close return/volatility qualification; adjustment-event detection is needed before performance evaluation.",
            "Observed-history bar count is not calendar listing age; a full point-in-time universe still requires active and delisted names.",
        ],
        "sealed_validation_access": "none",
    }
    path = HERE / "delisted_eligibility_coverage.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("raw_rows", "traded_valid_rows", "normal_filter_eligible_rows_before_isST", "normal_filter_eligible_ST_rows_excluded", "normal_and_nonST_rows", "normal_and_nonST_symbols", "by_year")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
