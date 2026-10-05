#!/usr/bin/env python3
"""P4-VOTE-001 final-window evaluator.

The evaluator is intentionally data-driven: it accepts only the frozen rule's
trigger rows and same-date eligible baselines. It never selects rules or
parameters. Before the fixed 36-month window ends it returns ``待最终验证``;
after the window, insufficient support returns ``支持不足`` and a supported
sample is classified only by the predeclared overall accuracy gate.
"""

from __future__ import annotations

from collections import defaultdict
import csv
from datetime import date, datetime, time, timedelta, timezone
import math
import random
from pathlib import Path
from typing import Any, Iterable


WINDOW_START = date(2027, 1, 1)
WINDOW_END = date(2029, 12, 31)
# The independent P4 registration uses three complete calendar years.
STAGES = (
    ("2027", date(2027, 1, 1), date(2027, 12, 31)),
    ("2028", date(2028, 1, 1), date(2028, 12, 31)),
    ("2029", date(2029, 1, 1), date(2029, 12, 31)),
)
MIN_SIGNALS = 100
MIN_DATES = 30
MIN_SYMBOLS = 20
MIN_ACCURACY = 0.65
BEIJING = timezone(timedelta(hours=8), name="Asia/Shanghai")
FROZEN_SCOPE_PATH = Path(__file__).resolve().parents[3] / "research/normal_stock_65/phase5/pit_assignment.csv"
FROZEN_SCOPE_SHA256 = "fa6b9a06e3ca0f79ccf0a3757961a03e68cc508b7dab770be2ec87102f7c3950"
_FROZEN_SYMBOLS: set[str] | None = None


def _frozen_symbols() -> set[str]:
    global _FROZEN_SYMBOLS
    if _FROZEN_SYMBOLS is None:
        import hashlib
        if not FROZEN_SCOPE_PATH.is_file() \
                or hashlib.sha256(FROZEN_SCOPE_PATH.read_bytes()).hexdigest() != FROZEN_SCOPE_SHA256:
            raise ValueError("frozen PIT100 assignment file is unavailable or changed")
        with FROZEN_SCOPE_PATH.open(encoding="utf-8", newline="") as handle:
            _FROZEN_SYMBOLS = {row["symbol"] for row in csv.DictReader(handle)}
        if len(_FROZEN_SYMBOLS) != 100:
            raise ValueError("frozen PIT100 assignment must contain 100 symbols")
    return _FROZEN_SYMBOLS


def _day(value: Any) -> date:
    if not isinstance(value, str):
        raise ValueError("date fields must be ISO strings")
    result = date.fromisoformat(value)
    if result.isoformat() != value:
        raise ValueError("date fields must use canonical YYYY-MM-DD")
    return result


def _positive(value: Any) -> bool:
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and math.isfinite(value) and value > 0)


def _finite(value: Any) -> bool:
    return not isinstance(value, bool) and isinstance(value, (int, float)) and math.isfinite(value)


def _source_time(value: Any, *, field: str) -> datetime:
    """Validate a provenance envelope without trusting its caller-side label."""
    if not isinstance(value, dict) or set(value) != {"provider", "reference", "available_at"}:
        raise ValueError(f"{field} must contain provider/reference/available_at")
    if any(not isinstance(value[key], str) or not value[key] for key in value):
        raise ValueError(f"{field} provenance fields must be non-empty strings")
    try:
        parsed = datetime.fromisoformat(value["available_at"])
    except ValueError as exc:
        raise ValueError(f"{field}.available_at must be an ISO timestamp") from exc
    if parsed.tzinfo is None:
        raise ValueError(f"{field}.available_at must include a timezone")
    return parsed.astimezone(BEIJING)


def _validate_contract(row: dict[str, Any]) -> None:
    required = (
        "symbol", "rule_version", "logic", "change_pct_5d", "open_gap", "index_close_position",
        "atom_change_pct_5d", "atom_open_gap", "atom_index_close_position", "matched_rule_count",
        "target_mature", "target_observable", "suspended_target", "signal_date", "entry_date", "exit_date",
        "missing_target_reason", "source", "target_source",
    )
    missing = [key for key in required if key not in row]
    if missing:
        raise ValueError("final row missing frozen contract fields: " + ",".join(missing))
    if not isinstance(row["symbol"], str) or not row["symbol"]:
        raise ValueError("final rows must contain a non-empty symbol")
    if row["symbol"] not in _frozen_symbols():
        raise ValueError("final row symbol is outside the frozen PIT100 scope")
    if row["rule_version"] != "P4-VOTE-001" or row["logic"] != "2-of-3":
        raise ValueError("final rows must use frozen P4-VOTE-001 three-of-two logic")
    if type(row["target_mature"]) is not bool or type(row["target_observable"]) is not bool \
            or type(row["suspended_target"]) is not bool:
        raise ValueError("target flags must be explicit booleans")
    values = (row["change_pct_5d"], row["open_gap"], row["index_close_position"])
    if not all(_finite(value) for value in values):
        raise ValueError("frozen factor atoms must be finite numbers")
    atoms = (
        row["change_pct_5d"] <= -0.10,
        row["open_gap"] >= 0.02,
        row["index_close_position"] <= 0.20,
    )
    reported_atoms = (row["atom_change_pct_5d"], row["atom_open_gap"], row["atom_index_close_position"])
    if any(type(value) is not bool for value in reported_atoms) or tuple(reported_atoms) != atoms:
        raise ValueError("reported atom hits do not match frozen thresholds")
    if type(row["matched_rule_count"]) is not int or row["matched_rule_count"] != sum(atoms) \
            or row["matched_rule_count"] < 2:
        raise ValueError("matched_rule_count does not match the frozen three atoms")
    if not isinstance(row["missing_target_reason"], str):
        raise ValueError("missing_target_reason must be a string")
    signal_day = _day(row["signal_date"])
    entry_day = _day(row["entry_date"])
    exit_day = _day(row["exit_date"])
    if not signal_day < entry_day < exit_day:
        raise ValueError("final rows must have signal < entry < exit dates")
    source_time = _source_time(row["source"], field="source")
    target_time = _source_time(row["target_source"], field="target_source")
    signal_close = datetime.combine(signal_day, time(15), tzinfo=BEIJING)
    target_open = datetime.combine(exit_day, time(9, 30), tzinfo=BEIJING)
    if source_time > signal_close:
        raise ValueError("factor source must be available by the signal close")
    if target_time < target_open:
        raise ValueError("target source cannot predate target maturity")
    if row["suspended_target"] and row["target_observable"]:
        raise ValueError("suspended target cannot be marked observable")
    if row["target_observable"]:
        if row["missing_target_reason"]:
            raise ValueError("observable target cannot have a missing reason")
        if not all(_positive(row.get(key)) for key in
                   ("entry_open", "exit_open", "entry_volume", "exit_volume")):
            raise ValueError("observable target requires positive opens and volumes")
    else:
        if not row["missing_target_reason"]:
            raise ValueError("unobservable mature target requires a reason")
        if any(row.get(key) is not None for key in
               ("entry_open", "exit_open", "entry_volume", "exit_volume")):
            raise ValueError("unobservable target must keep target prices and volumes null")


def _correct(row: dict[str, Any]) -> tuple[bool, bool]:
    """Return (observable, correct); mature missing targets count as wrong."""
    _validate_contract(row)
    if row["target_mature"] is not True:
        raise ValueError("final rows must contain mature targets")
    if row["suspended_target"]:
        return False, False
    observable = row["target_observable"]
    if observable:
        observable = (_positive(row.get("entry_open")) and _positive(row.get("exit_open"))
                      and _positive(row.get("entry_volume")) and _positive(row.get("exit_volume")))
    return observable, bool(observable and row["exit_open"] > row["entry_open"])


def _dedupe(rows: Iterable[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    ordered = sorted(rows, key=lambda r: (r["symbol"], _day(r["entry_date"]), _day(r["signal_date"])))
    kept: list[dict[str, Any]] = []
    excluded: list[dict[str, Any]] = []
    previous_exit: dict[str, date] = {}
    for row in ordered:
        entry = _day(row["entry_date"])
        exit_day = _day(row["exit_date"])
        if entry <= previous_exit.get(row["symbol"], date.min):
            excluded.append(row)
        else:
            kept.append(row)
            previous_exit[row["symbol"]] = exit_day
    return sorted(kept, key=lambda r: (_day(r["entry_date"]), r["symbol"], _day(r["signal_date"]))), excluded


def _wilson(wins: int, n: int) -> dict[str, float | None]:
    if not n:
        return {"lower": None, "upper": None}
    z = 1.959963984540054
    p = wins / n
    den = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / den
    half = z * math.sqrt((p * (1 - p) + z * z / (4 * n)) / n) / den
    return {"lower": round(centre - half, 6), "upper": round(centre + half, 6)}


def _metric(rows: list[dict[str, Any]]) -> dict[str, Any]:
    n = len(rows)
    wins = sum(int(r["correct"]) for r in rows)
    by_date: dict[str, list[int]] = defaultdict(list)
    for row in rows:
        # Date-equal accuracy uses the entry-date cohort, matching the
        # historical diagnostics and same-date baseline definition. Signal
        # date support remains reported separately as ``dates``.
        by_date[row["entry_date"]].append(int(row["correct"]))
    date_rates = [sum(values) / len(values) for values in by_date.values()]
    signal_dates = {r["signal_date"] for r in rows}
    signal_date_counts: dict[str, int] = defaultdict(int)
    for row in rows:
        signal_date_counts[row["signal_date"]] += 1
    return {
        "predictions": n,
        "correct": wins,
        "accuracy": round(wins / n, 6) if n else None,
        "accuracy_raw": wins / n if n else None,
        "date_equal_accuracy": round(sum(date_rates) / len(date_rates), 6) if date_rates else None,
        "date_equal_basis": "entry_date",
        # `dates` is the support-gate count: distinct signal dates.  The
        # entry-date cohort count is retained separately because date-equal
        # accuracy and the same-date baseline use executable entry dates.
        "dates": len(signal_dates),
        "entry_dates": len(by_date),
        "signal_dates": len(signal_dates),
        "symbols": len({r["symbol"] for r in rows}),
        "missing_targets_counted_wrong": sum(not r["observable"] for r in rows),
        "suspended_or_nontrading_targets": sum(bool(r.get("suspended_target")) for r in rows),
        "top_date_share": round(max(signal_date_counts.values()) / n, 6) if signal_date_counts else None,
        "top_entry_date_share": round(max(map(len, by_date.values())) / n, 6) if by_date else None,
        "top_signal_date_share": round(max(signal_date_counts.values()) / n, 6) if signal_date_counts else None,
        "top_symbol_share": round(max(sum(r["symbol"] == s for r in rows) for s in {r["symbol"] for r in rows}) / n, 6) if rows else None,
        "wilson_95": _wilson(wins, n),
    }


def _date_cluster_ci(rows: list[dict[str, Any]], draws: int = 1000) -> dict[str, Any]:
    groups: dict[str, tuple[int, int]] = {}
    for row in rows:
        wins, count = groups.get(row["entry_date"], (0, 0))
        groups[row["entry_date"]] = (wins + int(row["correct"]), count + 1)
    if len(groups) < 10:
        return {"lower": None, "upper": None, "clusters": len(groups), "method": "date_cluster_bootstrap_95"}
    values = list(groups.values())
    rng = random.Random(20260930)
    rates = []
    for _ in range(draws):
        sample = [values[rng.randrange(len(values))] for _ in values]
        rates.append(sum(w for w, _ in sample) / sum(n for _, n in sample))
    rates.sort()
    return {
        "lower": round(rates[int(0.025 * draws)], 6),
        "upper": round(rates[int(0.975 * draws) - 1], 6),
        "clusters": len(groups),
        "method": "date_cluster_bootstrap_95",
        "seed": 20260930,
        "draws": draws,
    }


def _same_date_baseline(rows: list[dict[str, Any]], baselines: list[dict[str, Any]],
                        *, as_of: date) -> dict[str, Any]:
    by_date: dict[str, list[int]] = defaultdict(list)
    seen: set[tuple[str, str]] = set()
    for row in baselines:
        if not isinstance(row, dict):
            raise ValueError("same-date baseline rows must be objects")
        required = {
            "symbol", "entry_date", "eligible", "correct", "source",
            "entry_open", "exit_open", "entry_volume", "exit_volume",
            "target_observable", "suspended_target", "missing_target_reason",
        }
        if set(row) != required:
            raise ValueError("same-date baseline row has unknown or missing fields")
        if not isinstance(row.get("symbol"), str) or not row["symbol"]:
            raise ValueError("same-date baseline requires a non-empty symbol")
        entry_date = _day(row.get("entry_date"))
        if type(row.get("eligible")) is not bool or type(row.get("correct")) is not bool:
            raise ValueError("same-date baseline labels must be explicit booleans")
        if type(row["target_observable"]) is not bool or type(row["suspended_target"]) is not bool:
            raise ValueError("same-date baseline target flags must be explicit booleans")
        if not row["eligible"] and row["correct"]:
            raise ValueError("ineligible same-date baseline cannot be correct")
        if not isinstance(row["missing_target_reason"], str):
            raise ValueError("same-date baseline missing reason must be a string")
        observable = row["target_observable"] and not row["suspended_target"]
        if observable:
            if row["missing_target_reason"] or not all(_positive(row.get(key)) for key in
                                                       ("entry_open", "exit_open", "entry_volume", "exit_volume")):
                raise ValueError("observable baseline requires positive opens/volumes and no missing reason")
        else:
            if not row["missing_target_reason"] or any(row.get(key) is not None for key in
                                                        ("entry_open", "exit_open", "entry_volume", "exit_volume")):
                raise ValueError("unobservable baseline requires a reason and null target values")
        derived_correct = bool(row["eligible"] and observable and row["exit_open"] > row["entry_open"])
        if row["correct"] is not derived_correct:
            raise ValueError("same-date baseline correctness does not match its target values")
        if _source_time(row["source"], field="baseline source") > datetime.combine(
                as_of, time.max, tzinfo=BEIJING):
            raise ValueError("baseline source is not available by final evaluation time")
        identity = (row["symbol"], entry_date.isoformat())
        if identity in seen:
            raise ValueError("duplicate same-date baseline row")
        seen.add(identity)
        if row.get("eligible") is True:
            by_date[entry_date.isoformat()].append(int(row["correct"]))
    signal_by_date: dict[str, list[int]] = defaultdict(list)
    for row in rows:
        signal_by_date[row["entry_date"]].append(int(row["correct"]))
    missing = sorted(set(signal_by_date) - set(by_date))
    if missing:
        raise ValueError("same-date baseline missing for signal dates")
    n = len(rows)
    if not n:
        return {"event_weighted_lift_pp": None, "date_equal_lift_pp": None, "signal_weighted_baseline": None,
                "date_equal_baseline": None}
    signal_accuracy = sum(r["correct"] for r in rows) / n
    weighted = sum(sum(by_date[d]) / len(by_date[d]) * len(signal_by_date[d]) for d in signal_by_date) / n
    date_equal = sum(sum(by_date[d]) / len(by_date[d]) for d in signal_by_date) / len(signal_by_date)
    signal_date_equal = sum(sum(values) / len(values) for values in signal_by_date.values()) / len(signal_by_date)
    return {
        "event_weighted_lift_pp": round((signal_accuracy - weighted) * 100, 3),
        "date_equal_lift_pp": round((signal_date_equal - date_equal) * 100, 3),
        "signal_weighted_baseline": round(weighted, 6),
        "date_equal_baseline": round(date_equal, 6),
    }


def evaluate_final(*, trigger_records: list[dict[str, Any]], baseline_records: list[dict[str, Any]],
                   window_ended: bool, coverage_complete: bool, maturity_complete: bool,
                   window: dict[str, Any] | None = None, as_of: date | None = None) -> dict[str, Any]:
    """Evaluate one final dataset under the frozen P4 contract."""
    active_start = WINDOW_START
    active_end = WINDOW_END
    active_stages = STAGES
    if window is not None:
        try:
            active_start = _day(window["signal_start"])
            active_end = _day(window["signal_end"])
            active_stages = tuple((stage["id"], _day(stage["signal_start"]), _day(stage["signal_end"]))
                                  for stage in window["stages"])
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError("invalid frozen final window") from exc
        if len(active_stages) != 3 or active_start >= active_end:
            raise ValueError("frozen final window must contain three ordered stages")
        if active_start != WINDOW_START or active_end != WINDOW_END or active_stages != STAGES:
            raise ValueError("final evaluator accepts only the independently frozen P4 window")
    if as_of is not None:
        actual_ended = as_of > active_end
        if window_ended != actual_ended:
            raise ValueError("window_ended does not match the supplied as_of date")
    if type(window_ended) is not bool or type(coverage_complete) is not bool \
            or type(maturity_complete) is not bool:
        raise ValueError("window, coverage and maturity gates must be explicit booleans")
    if not isinstance(trigger_records, list) or not isinstance(baseline_records, list):
        raise ValueError("trigger_records and baseline_records must be lists")
    if not window_ended or not coverage_complete or not maturity_complete:
        return {
            "status": "待最终验证",
            "reason": "36个月窗口、完整交易日覆盖或成熟目标尚未完成",
            "observations": len(trigger_records),
            "finalized": False,
        }
    if as_of is None or type(as_of) is not date:
        raise ValueError("final evaluation requires an explicit as_of date")
    normalized: list[dict[str, Any]] = []
    identities: dict[tuple[str, str, str, str], str] = {}
    for original in trigger_records:
        if not isinstance(original, dict):
            raise ValueError("final trigger rows must be objects")
        row = dict(original)
        signal = _day(row["signal_date"])
        if not active_start <= signal <= active_end:
            raise ValueError("trigger lies outside frozen final window")
        entry = _day(row["entry_date"]); exit_day = _day(row["exit_date"])
        if not signal < entry < exit_day:
            raise ValueError("entry and exit must follow the signal date")
        if exit_day > active_end + timedelta(days=10):
            raise ValueError("target exit is outside the frozen final maturity tail")
        _validate_contract(row)
        if any(_source_time(row[field], field=field) > datetime.combine(as_of, time.max, tzinfo=BEIJING)
               for field in ("source", "target_source")):
            raise ValueError("trigger provenance is not available by final evaluation time")
        identity = (str(row.get("symbol")), signal.isoformat(), entry.isoformat(), exit_day.isoformat())
        serialized = repr(sorted((key, repr(value)) for key, value in row.items()))
        if identity in identities:
            if identities[identity] != serialized:
                raise ValueError("conflicting duplicate trigger identity")
            raise ValueError("duplicate trigger identity")
        identities[identity] = serialized
        observable, correct = _correct(row)
        row["observable"] = observable
        row["correct"] = correct
        normalized.append(row)
    retained, excluded = _dedupe(normalized)
    total = _metric(retained)
    total["date_cluster_95"] = _date_cluster_ci(retained)
    stages = {}
    for name, start, end in active_stages:
        stages[name] = _metric([r for r in retained if start <= _day(r["signal_date"]) <= end])
    calendar_years = {
        str(year): _metric([r for r in retained if _day(r["signal_date"]).year == year])
        for year in range(active_start.year, active_end.year + 1)
    }
    baseline = _same_date_baseline(retained, baseline_records, as_of=as_of)
    # The support requirement is expressed in signal dates.  Date-equal
    # accuracy remains entry-date weighted by its pre-registered definition.
    support = (total["predictions"] >= MIN_SIGNALS
               and total["signal_dates"] >= MIN_DATES
               and total["symbols"] >= MIN_SYMBOLS)
    if not support:
        status = "支持不足"
        reason = "窗口结束后成熟非重叠信号、日期或股票支持量低于预登记门槛"
    elif total["accuracy_raw"] >= MIN_ACCURACY:
        status = "通过"
        reason = "总体上涨命中率达到预登记65%门槛"
    else:
        status = "未通过"
        reason = "总体上涨命中率低于预登记65%门槛"
    return {
        "status": status,
        "reason": reason,
        "finalized": True,
        "window": {"signal_start": active_start.isoformat(), "signal_end": active_end.isoformat(),
                   "stages": [{"id": name, "signal_start": start.isoformat(), "signal_end": end.isoformat()}
                              for name, start, end in active_stages]},
        "strict_nonoverlap_total": total,
        "strict_nonoverlap_stages": stages,
        "calendar_years": calendar_years,
        "same_date_baseline": baseline,
        "excluded_overlap_records": len(excluded),
        "raw_trigger_records": len(trigger_records),
        "final_support_gate": {"signals": MIN_SIGNALS, "signal_dates": MIN_DATES, "symbols": MIN_SYMBOLS},
        "overall_accuracy_gate": MIN_ACCURACY,
    }
