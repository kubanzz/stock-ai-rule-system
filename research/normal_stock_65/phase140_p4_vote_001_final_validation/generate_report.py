#!/usr/bin/env python3
"""Build the governed P4-VOTE-001 status packet.

This packet deliberately treats phase 4/5/6 results as historical diagnostics.
It does not read sealed validation data and it does not fabricate a final
36-month observation.  The final observation ledger is therefore empty until
the separately frozen forward window has complete, mature source records.
"""

from __future__ import annotations

import csv
import hashlib
import json
import math
import sys
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT))

from research.normal_stock_65.phase5 import stress_frozen_vote as phase5  # noqa: E402
from research.normal_stock_65.phase6 import stress_older_fixed_vote as phase6  # noqa: E402

RULE_SOURCE = ROOT / "research/normal_stock_65/phase4/bullish_group_results.json"
FROZEN_SOURCE = ROOT / "research/normal_stock_65/phase5/pit_fixed_vote_stress.json"
OLDER_SOURCE = ROOT / "research/normal_stock_65/phase6/pit_older_fixed_vote_stress.json"

EXPECTED_MEMBERS = [
    {"field": "change_pct_5d", "op": "<=", "value": -0.1, "family": "momentum"},
    {"field": "open_gap", "op": ">=", "value": 0.02, "family": "candle"},
    {"field": "index_close_position", "op": "<=", "value": 0.2, "family": "market"},
]

# P45 is a different G144/G118 candidate and cannot silently supply the
# P4-VOTE-001 forward window or stock scope. A new registration is required
# before any final observation can be accepted.
FINAL_WINDOW_STATUS = "FROZEN_RULE_PENDING_36_MONTH_FINAL"
FINAL_WINDOW_REASON = "P4-VOTE-001 独立冻结登记完成；2027—2029 三个完整自然年为前瞻窗口。"
FINAL_WINDOW_START = "2027-01-01"
FINAL_WINDOW_END = "2029-12-31"
FINAL_WINDOW_SEMANTICS = "three complete calendar years; pre-registered default because source freeze evidence is 2026-09-30 and the final window starts at the next calendar year"
FINAL_STAGES = [
    {"id": "2027", "signal_start": "2027-01-01", "signal_end": "2027-12-31"},
    {"id": "2028", "signal_start": "2028-01-01", "signal_end": "2028-12-31"},
    {"id": "2029", "signal_start": "2029-01-01", "signal_end": "2029-12-31"},
]
STOCK_SCOPE = ROOT / "research/normal_stock_65/phase5/pit_assignment.csv"

INPUTS = {
    "phase4_rule_source": RULE_SOURCE,
    "phase5_frozen_result": FROZEN_SOURCE,
    "phase6_historical_diagnostic": OLDER_SOURCE,
    "phase5_quotes": ROOT / "research/normal_stock_65/phase5/pit_unadjusted_baostock.csv",
    "phase5_assignment": ROOT / "research/normal_stock_65/phase5/pit_assignment.csv",
    "phase5_index": ROOT / "research/normal_stock_65/phase4/hs300_unadjusted_baostock.csv",
    "phase6_quotes": ROOT / "research/normal_stock_65/phase6/pit_older_unadjusted_baostock.csv",
    "phase6_assignment": ROOT / "research/normal_stock_65/phase6/pit_older_assignment.csv",
    "phase6_index": ROOT / "research/normal_stock_65/phase6/hs300_older_unadjusted_baostock.csv",
    "phase5_calculator": ROOT / "research/normal_stock_65/phase5/stress_frozen_vote.py",
    "phase6_calculator": ROOT / "research/normal_stock_65/phase6/stress_older_fixed_vote.py",
    "universe_calculator": ROOT / "research/normal_stock_65/normal_universe.py",
    "factor_calculator": ROOT / "research/one_day_20260930/quant_research.py",
}


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_sha(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    ).hexdigest()


def round_or_none(value: float | None, digits: int = 6) -> float | None:
    return None if value is None or not math.isfinite(float(value)) else round(float(value), digits)


def wilson_interval(wins: int, n: int, z: float = 1.959963984540054) -> dict[str, float | None]:
    if not n:
        return {"lower": None, "upper": None}
    p = wins / n
    den = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / den
    half = z * math.sqrt((p * (1 - p) + z * z / (4 * n)) / n) / den
    return {"lower": round(centre - half, 6), "upper": round(centre + half, 6)}


def date_cluster_interval(frame, correct, draws: int = 1000) -> dict[str, float | None]:
    if frame.empty or frame["entry_date"].nunique() < 10:
        return {"lower": None, "upper": None, "method": "date_cluster_bootstrap_95", "clusters": int(frame["entry_date"].nunique())}
    grouped = (
        __import__("pandas")
        .DataFrame({"entry_date": frame["entry_date"], "correct": correct.astype(int).to_numpy()})
        .groupby("entry_date", sort=True)["correct"]
        .agg(["sum", "size"])
        .to_numpy()
    )
    rng = np.random.default_rng(20260930)
    picked = rng.integers(0, len(grouped), size=(draws, len(grouped)))
    sample = grouped[picked]
    rates = sample[:, :, 0].sum(axis=1) / sample[:, :, 1].sum(axis=1)
    return {
        "lower": round(float(np.quantile(rates, 0.025)), 6),
        "upper": round(float(np.quantile(rates, 0.975)), 6),
        "method": "date_cluster_bootstrap_95",
        "clusters": int(len(grouped)),
        "seed": 20260930,
        "draws": draws,
    }


def same_date_baseline(data, selected_nonoverlap) -> dict[str, Any]:
    if selected_nonoverlap.empty:
        return {
            "signal_count": 0,
            "signal_dates": 0,
            "signal_accuracy": None,
            "all_eligible_baseline_event_weighted": None,
            "all_eligible_baseline_date_equal": None,
            "lift_event_weighted_pp": None,
            "lift_date_equal_pp": None,
            "bootstrap_lift_95": {"lower_pp": None, "upper_pp": None},
        }
    baseline_by_day = data.groupby("entry_date")["up_correct"].mean()
    signal_by_day = selected_nonoverlap.groupby("entry_date")["up_correct"].agg(["sum", "size"])
    signal_by_day["baseline"] = baseline_by_day.reindex(signal_by_day.index)
    if signal_by_day["baseline"].isna().any():
        raise ValueError("same-date baseline missing for a signal date")
    n = int(len(selected_nonoverlap))
    signal_accuracy = float(selected_nonoverlap["up_correct"].mean())
    event_baseline = float((signal_by_day["baseline"] * signal_by_day["size"]).sum() / n)
    date_baseline = float(signal_by_day["baseline"].mean())
    signal_date_equal = float((signal_by_day["sum"] / signal_by_day["size"]).mean())
    differences = signal_by_day["sum"] - signal_by_day["baseline"] * signal_by_day["size"]
    rng = np.random.default_rng(20260930)
    matrix = np.column_stack([differences.to_numpy(), signal_by_day["size"].to_numpy()])
    picked = rng.integers(0, len(matrix), size=(1000, len(matrix)))
    sampled = matrix[picked]
    lifts = sampled[:, :, 0].sum(axis=1) / sampled[:, :, 1].sum(axis=1)
    return {
        "signal_count": n,
        # Historical diagnostics expose both notions explicitly: the
        # support count is distinct signal/trade dates, while date-equal
        # accuracy and its baseline use executable entry dates.
        "signal_dates": int(selected_nonoverlap["trade_date"].nunique()),
        "entry_dates": int(selected_nonoverlap["entry_date"].nunique()),
        "signal_accuracy": round(signal_accuracy, 6),
        "signal_date_equal_accuracy": round(signal_date_equal, 6),
        "all_eligible_baseline_event_weighted": round(event_baseline, 6),
        "all_eligible_baseline_date_equal": round(date_baseline, 6),
        "lift_event_weighted_pp": round((signal_accuracy - event_baseline) * 100, 3),
        "lift_date_equal_pp": round((signal_date_equal - date_baseline) * 100, 3),
        "bootstrap_lift_95": {
            "lower_pp": round(float(np.quantile(lifts, 0.025) * 100), 3),
            "upper_pp": round(float(np.quantile(lifts, 0.975) * 100), 3),
            "method": "entry_date_cluster_bootstrap_95",
            "seed": 20260930,
            "draws": 1000,
        },
    }


def trigger_rows(period: str, data, selected, selected_nonoverlap) -> list[dict[str, Any]]:
    selected_keys = {
        (r.symbol, r.trade_date, r.entry_date, r.exit_date)
        for r in selected_nonoverlap.itertuples()
    }
    rows = []
    for r in selected.sort_values(["trade_date", "symbol"]).itertuples():
        key = (r.symbol, r.trade_date, r.entry_date, r.exit_date)
        atom_hits = [
            bool(r.change_pct_5d <= -0.10) if not math.isnan(float(r.change_pct_5d)) else False,
            bool(r.open_gap >= 0.02) if not math.isnan(float(r.open_gap)) else False,
            bool(r.index_close_position <= 0.20) if not math.isnan(float(r.index_close_position)) else False,
        ]
        rows.append(
            {
                "period": period,
                "symbol": r.symbol,
                "signal_date": r.trade_date.date().isoformat(),
                "entry_date": r.entry_date.date().isoformat(),
                "exit_date": r.exit_date.date().isoformat(),
                "rule_version": "P4-VOTE-001",
                "logic": "2-of-3",
                "change_pct_5d": round_or_none(r.change_pct_5d),
                "open_gap": round_or_none(r.open_gap),
                "index_close_position": round_or_none(r.index_close_position),
                "atom_change_pct_5d": atom_hits[0],
                "atom_open_gap": atom_hits[1],
                "atom_index_close_position": atom_hits[2],
                "matched_rule_count": sum(atom_hits),
                "raw_trigger": True,
                "dedup_status": "kept_nonoverlap" if key in selected_keys else "excluded_overlap_or_duplicate",
                "target_mature": True,
                "entry_open": round_or_none(r.entry_open),
                "exit_open": round_or_none(r.exit_open),
                "entry_volume": round_or_none(r.entry_volume),
                "exit_volume": round_or_none(r.exit_volume),
                "target_observable": bool(r.direction_observable),
                "up_correct": bool(r.up_correct),
                "blocked_exit": bool(r.blocked_exit),
                "missing_target_reason": "" if bool(r.direction_observable) else "missing_or_non_executable_open",
            }
        )
    return rows


def metric_bundle(data, selected_nonoverlap) -> dict[str, Any]:
    n = len(selected_nonoverlap)
    wins = int(selected_nonoverlap["up_correct"].sum()) if n else 0
    return {
        "raw_trigger_count": None,
        "nonoverlap_signal_count": n,
        "correct": wins,
        "accuracy": round(wins / n, 6) if n else None,
        "wilson_95": wilson_interval(wins, n),
        "date_cluster_95": date_cluster_interval(selected_nonoverlap, selected_nonoverlap["up_correct"]),
        "date_equal_accuracy": round(float(selected_nonoverlap.groupby("entry_date")["up_correct"].mean().mean()), 6) if n else None,
        "date_equal_basis": "entry_date",
        "signal_dates": int(selected_nonoverlap["trade_date"].nunique()),
        "entry_dates": int(selected_nonoverlap["entry_date"].nunique()),
        "symbols": int(selected_nonoverlap["symbol"].nunique()),
        "top_date_share": round(float(selected_nonoverlap["trade_date"].value_counts(normalize=True).iloc[0]), 6) if n else None,
        "top_entry_date_share": round(float(selected_nonoverlap["entry_date"].value_counts(normalize=True).iloc[0]), 6) if n else None,
        "top_symbol_share": round(float(selected_nonoverlap["symbol"].value_counts(normalize=True).iloc[0]), 6) if n else None,
        "missing_target_or_unobservable": int((~selected_nonoverlap["direction_observable"]).sum()) if n else 0,
        "suspended_or_nontrading_target": int(
            ((~selected_nonoverlap["entry_status"].eq("1"))
             | (~selected_nonoverlap["exit_status"].eq("1"))).sum()
        ) if n else 0,
        "blocked_exit": int(selected_nonoverlap["blocked_exit"].sum()) if n else 0,
    }


def prepare_diagnostic(period: str, module, years: tuple[int, int]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    data = module.event_frame()
    data = data.loc[data["year"].between(*years)].copy()
    hit_count = (
        data["change_pct_5d"].le(-0.10).fillna(False).astype(int)
        + data["open_gap"].ge(0.02).fillna(False).astype(int)
        + data["index_close_position"].le(0.20).fillna(False).astype(int)
    )
    selected = data.loc[hit_count.ge(2)].copy()
    selected_nonoverlap = module.nonoverlap(selected)
    bundle = metric_bundle(data, selected_nonoverlap)
    bundle["raw_trigger_count"] = int(len(selected))
    bundle["eligible_event_count"] = int(len(data))
    bundle["same_date_baseline"] = same_date_baseline(data, selected_nonoverlap)
    bundle["years"] = {
        str(year): metric_bundle(data.loc[data["year"].eq(year)], module.nonoverlap(selected.loc[selected["year"].eq(year)]))
        for year in range(years[0], years[1] + 1)
    }
    bundle["trigger_record_count"] = int(len(selected))
    return {
        "period": period,
        "data_range": {"signal_start": str(data["trade_date"].min().date()) if len(data) else None,
                        "signal_end": str(data["trade_date"].max().date()) if len(data) else None},
        "statistics": bundle,
    }, trigger_rows(period, data, selected, selected_nonoverlap)


def write_csv(path: Path, rows: list[dict[str, Any]], fieldnames: list[str]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="raise")
        writer.writeheader()
        writer.writerows(rows)


def ensure_empty_final_ledger(path: Path, fieldnames: list[str]) -> None:
    """Create the initial empty ledger once; never erase future observations."""
    if not path.exists():
        write_csv(path, [], fieldnames)
        return
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames == fieldnames and next(reader, None) is None:
            return
        if reader.fieldnames == fieldnames:
            raise SystemExit("FINAL_TRIGGER_RECORDS.csv contains observations; use FINAL_LOCK export as the final source")
        raise SystemExit("FINAL_TRIGGER_RECORDS.csv is a terminal export or unknown schema; generator refuses to rewrite it")


def main() -> None:
    rule = json.loads(RULE_SOURCE.read_text(encoding="utf-8"))
    frozen = json.loads(FROZEN_SOURCE.read_text(encoding="utf-8"))
    older = json.loads(OLDER_SOURCE.read_text(encoding="utf-8"))
    chosen = rule["results"]["vote"][0]
    if chosen["members"] != EXPECTED_MEMBERS or chosen["min_matched_rules"] != 2:
        raise SystemExit("P4-VOTE-001 rule source no longer matches the frozen three-of-two definition")
    if frozen["fixed_group"]["members"] != EXPECTED_MEMBERS or frozen["fixed_group"]["min_matched_rules"] != 2:
        raise SystemExit("phase5 fixed group no longer matches P4-VOTE-001")
    if older["fixed_group"]["members"] != EXPECTED_MEMBERS or older["fixed_group"]["min_matched_rules"] != 2:
        raise SystemExit("phase6 diagnostic group no longer matches P4-VOTE-001")

    p5_dev_diag, p5_dev_rows = prepare_diagnostic("phase5_pit_2020_2023", phase5, (2020, 2023))
    p5_diag, p5_rows = prepare_diagnostic("phase5_pit_2024_2026", phase5, (2024, 2026))
    p6_diag, p6_rows = prepare_diagnostic("phase6_older_2010_2018", phase6, (2010, 2018))

    trigger_fields = list(p5_rows[0].keys()) if p5_rows else [
        "period", "symbol", "signal_date", "entry_date", "exit_date", "rule_version", "logic",
        "change_pct_5d", "open_gap", "index_close_position", "atom_change_pct_5d", "atom_open_gap",
        "atom_index_close_position", "matched_rule_count", "raw_trigger", "dedup_status", "target_mature",
        "entry_open", "exit_open", "entry_volume", "exit_volume", "target_observable", "up_correct",
        "blocked_exit", "missing_target_reason",
    ]
    write_csv(HERE / "HISTORICAL_TRIGGER_RECORDS.csv", p5_dev_rows + p5_rows + p6_rows, trigger_fields)
    ensure_empty_final_ledger(HERE / "FINAL_TRIGGER_RECORDS.csv", trigger_fields)

    final_window = {
        "status": FINAL_WINDOW_STATUS,
        "signal_start": FINAL_WINDOW_START,
        "signal_end": FINAL_WINDOW_END,
        "stage_semantics": FINAL_WINDOW_SEMANTICS,
        "stages": FINAL_STAGES,
        "prediction": "T close -> T+1 market-session open to T+2 market-session open",
        "up_definition": "exit_open > entry_open; mature missing or suspended open counts wrong",
        "nonoverlap": "same stock, entry_date strictly later than prior retained exit_date",
        "required_support": {"signals": 100, "signal_dates": 30, "symbols": 20},
        "required_accuracy": 0.65,
        "finalized": False,
        "window_ended": False,
        "reason": FINAL_WINDOW_REASON,
    }
    final_stats = {
        "status": "待最终验证",
        "observations": 0,
        "mature_nonoverlap_signals": 0,
        "mature_signal_dates": 0,
        "mature_symbols": 0,
        "correct": 0,
        "accuracy": None,
        "date_equal_accuracy": None,
        "same_date_baseline": None,
        "confidence_interval_95": {"lower": None, "upper": None},
        "date_concentration": None,
        "stock_concentration": None,
        "missing_targets": 0,
        "suspended_targets": 0,
        "target_observation_note": "无最终观察记录；0 表示尚未观察，不表示已证明不存在缺失或停牌。",
        "coverage_complete": False,
        "maturity_complete": False,
        "stage_statistics": {
            stage["id"]: {"signals": 0, "dates": 0, "symbols": 0, "correct": 0, "accuracy": None}
            for stage in FINAL_STAGES
        },
    }

    input_hashes = {name: sha256_file(path) for name, path in INPUTS.items()}
    stock_symbols = sorted({row["symbol"] for row in csv.DictReader(STOCK_SCOPE.open(encoding="utf-8"))})
    if len(stock_symbols) != 100:
        raise SystemExit("phase5 PIT100 stock scope is not exactly 100 unique symbols")
    result = {
        "schema_version": 1,
        "experiment_id": "P4-VOTE-001",
        "created_at_utc": "2026-10-04T00:00:00+00:00",
        "classification": "待最终验证",
        "final_classification": None,
        "classification_basis": [
            "规则源、冻结结果、窗口和股票范围已登记，但真实前瞻窗口尚未结束，当前观察数为0。",
            "因此没有可进入最终分母的成熟非重叠信号，无法宣称通过、未通过或支持不足。",
            "窗口结束后才触发最终三分类；不得延长窗口凑足样本。",
        ],
        "decision_scope": "仅为看涨辅助决策信号研究；不构成确定性预测、收益保证或自动交易授权。",
        "rule": {
            "version": "P4-VOTE-001",
            "source_path": str(RULE_SOURCE.relative_to(ROOT)),
            "source_sha256": sha256_file(RULE_SOURCE),
            "frozen_path": str(FROZEN_SOURCE.relative_to(ROOT)),
            "frozen_sha256": sha256_file(FROZEN_SOURCE),
            "members": EXPECTED_MEMBERS,
            "logic": "三选二（命中至少2条）",
            "prediction_cycle": "T日收盘发信号；T+1市场开盘入场；T+2市场开盘退出",
            "up_definition": "exit_open > entry_open",
            "mature_missing_policy": "成熟目标缺失、停牌或无有效开盘计错并保留分母",
        },
        "data_boundary": {
            "historical_diagnostics": ["2010-01-04..2018-12-31", "2020-01-13..2026-09-29"],
            "date_equal_basis": "entry_date cohort; signal_dates remains the signal-date support count",
            "historical_use": "只作诊断，不调参、不删样本、不挑年份、不替代最终验证",
            "final_validation": final_window,
            "final_observation_source": "未接入；没有读取封存验证集",
        },
        "incremental_conditions": {
            "status": "未测试",
            "new_development_data_used": False,
            "predeclared_categories": [],
            "parameter_ranges": {},
            "combination_count": 0,
            "final_data_used_for_selection": False,
            "reason": "当前没有新的、事前登记的开发数据；不以历史或最终数据反向选择增量条件。",
        },
        "historical_diagnostics": {
            "phase4_development_2020_2023": {
                "source_summary": "results.vote[0] from phase4/bullish_group_results.json",
                "raw_predictions": chosen["fit_raw_predictions"],
                "fit_score": chosen["fit_score"],
                "development_2024_2026_nonoverlap": chosen["development_2024_26"],
                "development_years": chosen["years"],
                "trigger_records_available": False,
                "interpretation": "phase4 aggregate only; not used for selection or final validation",
            },
            "phase5_pit_2020_2023": p5_dev_diag,
            "phase5_pit_2024_2026": p5_diag,
            "phase6_older_2010_2018": p6_diag,
        },
        "final_validation": {"window": final_window, "statistics": final_stats,
                             "status": "待最终验证",
                             "failure_reasons": [
                                 "真实前瞻观测记录为0。",
                                 "36个月窗口尚未结束，完整市场日覆盖、成熟目标和逐触发记录均未形成。",
                                 "当前尚未进入终局门槛判定；窗口结束后若支持量不足才标记支持不足。",
                             ]},
        "artifacts": {
            "historical_trigger_records": "HISTORICAL_TRIGGER_RECORDS.csv",
            "final_trigger_records": "FINAL_TRIGGER_RECORDS.csv",
            "historical_trigger_record_count": len(p5_dev_rows) + len(p5_rows) + len(p6_rows),
            "final_trigger_record_count": 0,
            "stock_scope": "phase5/pit_assignment.csv",
            "stock_scope_count": 100,
            "stock_scope_sha256": sha256_file(STOCK_SCOPE),
        },
        "input_sha256": input_hashes,
        "code_sha256": {
            "generate_report.py": sha256_file(HERE / "generate_report.py"),
            "final_evaluator.py": sha256_file(HERE / "final_evaluator.py"),
            "forward_ledger.py": sha256_file(HERE / "forward_ledger.py"),
        },
        "result_sha256": None,
    }
    result["result_sha256"] = canonical_sha({k: v for k, v in result.items() if k != "result_sha256"})
    (HERE / "RESULT.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    artifacts = {
        "RESULT.json": sha256_file(HERE / "RESULT.json"),
        "HISTORICAL_TRIGGER_RECORDS.csv": sha256_file(HERE / "HISTORICAL_TRIGGER_RECORDS.csv"),
        "FINAL_TRIGGER_RECORDS.csv": sha256_file(HERE / "FINAL_TRIGGER_RECORDS.csv"),
        "PROTOCOL.md": sha256_file(HERE / "PROTOCOL.md"),
        "README.md": sha256_file(HERE / "README.md"),
        "REPORT.md": sha256_file(HERE / "REPORT.md"),
        "test_report.py": sha256_file(HERE / "test_report.py"),
        "generate_report.py": sha256_file(HERE / "generate_report.py"),
        "final_evaluator.py": sha256_file(HERE / "final_evaluator.py"),
        "forward_ledger.py": sha256_file(HERE / "forward_ledger.py"),
        "COMPLETION_AUDIT.md": sha256_file(HERE / "COMPLETION_AUDIT.md"),
    }
    (HERE / "ARTIFACT_SHA256.json").write_text(json.dumps(artifacts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    registration = {
        "schema_version": 1,
        "experiment_id": "P4-VOTE-001",
        "status": FINAL_WINDOW_STATUS,
        "registered_at_utc": "2026-10-04T00:00:00+00:00",
        "rule_version": "P4-VOTE-001",
        "rule_sha256": sha256_file(RULE_SOURCE),
        "frozen_result_sha256": sha256_file(FROZEN_SOURCE),
        "window_freeze": {"start": FINAL_WINDOW_START, "end": FINAL_WINDOW_END,
                          "stages": FINAL_STAGES, "semantics": FINAL_WINDOW_SEMANTICS,
                          "freeze_gap_policy": "source freeze evidence through 2026-09-30; no signals are accepted before 2027-01-01; this was registered before final data and cannot be changed after observation"},
        "stock_scope": {"path": str(STOCK_SCOPE.relative_to(ROOT)), "count": 100,
                        "symbols": stock_symbols, "sha256": sha256_file(STOCK_SCOPE),
                        "selection": "frozen phase5 PIT100"},
        "final_window": final_window,
        "prediction": "T close -> T+1 market-session open to T+2 market-session open",
        "up_definition": "exit_open > entry_open",
        "mature_missing_policy": "mature missing or suspended target counts wrong",
        "support_gate": {"signals": 100, "signal_dates": 30, "symbols": 20, "accuracy": 0.65},
        "history_is_diagnostic_only": True,
        "incremental_conditions": {"categories": [], "parameter_ranges": {}, "combination_count": 0},
        "sealed_validation_read": False,
        "real_forward_observations": 0,
        "artifact_sha256": artifacts,
        "input_sha256": input_hashes,
        "code_sha256": {
            "generate_report.py": sha256_file(HERE / "generate_report.py"),
            "final_evaluator.py": sha256_file(HERE / "final_evaluator.py"),
            "forward_ledger.py": sha256_file(HERE / "forward_ledger.py"),
        },
    }
    registration_sha = canonical_sha(registration)
    registration["registration_sha256"] = registration_sha
    (HERE / "REGISTRATION.json").write_text(json.dumps(registration, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (HERE / "REGISTRATION.sha256").write_text(registration_sha + "\n", encoding="utf-8")
    print(json.dumps({"classification": result["classification"], "phase5": p5_diag["statistics"],
                      "phase6": p6_diag["statistics"], "artifacts": artifacts}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
