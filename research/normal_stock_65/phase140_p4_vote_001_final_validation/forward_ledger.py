#!/usr/bin/env python3
"""Append-only forward ledger for the frozen P4-VOTE-001 window.

This module deliberately does not collect data. An external collector must
provide a complete daily snapshot and a mature T+2 target batch. The ledger
only validates the frozen contract, chains records, and permits one terminal
evaluation after the full window.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import csv
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Any

from final_evaluator import evaluate_final


BEIJING = timezone(timedelta(hours=8), name="Asia/Shanghai")
WINDOW_START = date(2027, 1, 1)
WINDOW_END = date(2029, 12, 31)
FINAL_LOCK_NAME = "FINAL_LOCK.json"
UNCONFIRMED_STATUS = "DRAFT_WINDOW_AND_SCOPE_UNCONFIRMED"
REPO_ROOT = Path(__file__).resolve().parents[3]
FROZEN_SCOPE_PATH = REPO_ROOT / "research/normal_stock_65/phase5/pit_assignment.csv"
FROZEN_SCOPE_SHA256 = "fa6b9a06e3ca0f79ccf0a3757961a03e68cc508b7dab770be2ec87102f7c3950"


def digest(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _calendar_file(path_value: Any, sessions: list[date], expected_sha: Any) -> dict[date, tuple[date, date]]:
    if not isinstance(path_value, str) or not path_value:
        raise ValueError("calendar artifact path is required")
    if not isinstance(expected_sha, str) or len(expected_sha) != 64:
        raise ValueError("calendar artifact SHA-256 is required")
    path = Path(path_value)
    if not path.is_file() or sha256_file(path) != expected_sha:
        raise ValueError("calendar artifact file or SHA-256 does not match")
    lines = [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    triplets: dict[date, tuple[date, date]] = {}
    ordered_rows: list[tuple[date, date, date]] = []
    entry_dates: set[date] = set()
    exit_dates: set[date] = set()
    for line in lines:
        parts = line.split(",")
        if len(parts) != 3:
            raise ValueError("calendar artifact must contain signal_date,entry_date,exit_date rows")
        signal, entry, exit_ = (_day(value) for value in parts)
        if (signal in triplets or signal not in sessions or not signal < entry < exit_
                or entry in entry_dates or exit_ in exit_dates):
            raise ValueError("calendar artifact contains invalid or duplicate session mapping")
        triplets[signal] = (entry, exit_)
        ordered_rows.append((signal, entry, exit_))
        entry_dates.add(entry)
        exit_dates.add(exit_)
    if list(triplets) != sessions:
        raise ValueError("calendar artifact dates do not match the submitted sessions")
    # The artifact is the independent market-session anchor.  The target
    # dates for every signal must be the next two sessions in its union, so a
    # caller cannot silently substitute calendar days or skip a holiday.
    all_sessions = sorted({value for row in ordered_rows for value in row})
    positions = {value: index for index, value in enumerate(all_sessions)}
    for signal, entry, exit_ in ordered_rows:
        index = positions[signal]
        if index + 2 >= len(all_sessions) or (entry, exit_) != (all_sessions[index + 1], all_sessions[index + 2]):
            raise ValueError("calendar artifact must map T to the next two market sessions")
    return triplets


def _lock_payload(lock: Path) -> dict[str, Any]:
    try:
        payload = json.loads(lock.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("invalid final lock") from exc
    if not isinstance(payload, dict) or not isinstance(payload.get("result"), dict):
        raise ValueError("invalid final lock envelope")
    if payload.get("result_sha256") != digest(payload["result"]):
        raise ValueError("final lock result hash mismatch")
    return payload


def _day(value: Any) -> date:
    if not isinstance(value, str):
        raise ValueError("date must be an ISO string")
    parsed = date.fromisoformat(value)
    if parsed.isoformat() != value:
        raise ValueError("date must use canonical YYYY-MM-DD")
    return parsed


def _aware(value: str) -> datetime:
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        raise ValueError("recorded_at must include timezone")
    return parsed.astimezone(BEIJING)


def _source(value: Any) -> datetime:
    if not isinstance(value, dict) or set(value) != {"provider", "reference", "available_at"}:
        raise ValueError("source must contain provider/reference/available_at")
    if any(not isinstance(value[key], str) or not value[key] for key in value):
        raise ValueError("source fields must be non-empty strings")
    return _aware(value["available_at"])


def _positive(value: Any) -> bool:
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and value > 0)


def _finite(value: Any) -> bool:
    return not isinstance(value, bool) and isinstance(value, (int, float)) and math.isfinite(value)


def _validate_trigger(row: dict[str, Any], *, signal: date, entry: date, exit_: date) -> None:
    required = {
        "symbol", "signal_date", "entry_date", "exit_date", "rule_version", "logic",
        "change_pct_5d", "open_gap", "index_close_position", "matched_rule_count",
        "atom_change_pct_5d", "atom_open_gap", "atom_index_close_position",
        "entry_open", "exit_open", "entry_volume", "exit_volume", "target_observable",
        "suspended_target", "target_mature", "missing_target_reason", "source", "target_source",
    }
    if not isinstance(row, dict) or set(row) != required:
        raise ValueError("trigger row has unknown or missing fields")
    if row["rule_version"] != "P4-VOTE-001" or row["logic"] != "2-of-3":
        raise ValueError("trigger row violates frozen P4-VOTE-001 contract")
    factor_values = (row["change_pct_5d"], row["open_gap"], row["index_close_position"])
    if not all(_finite(value) for value in factor_values):
        raise ValueError("trigger factor values must be finite numbers")
    expected_atoms = (factor_values[0] <= -0.10, factor_values[1] >= 0.02, factor_values[2] <= 0.20)
    reported_atoms = (row["atom_change_pct_5d"], row["atom_open_gap"], row["atom_index_close_position"])
    if any(type(value) is not bool for value in reported_atoms) or tuple(reported_atoms) != expected_atoms:
        raise ValueError("trigger atom hits do not match frozen P4 thresholds")
    if not isinstance(row["matched_rule_count"], int) or row["matched_rule_count"] != sum(expected_atoms) \
            or row["matched_rule_count"] < 2:
        raise ValueError("trigger matched_rule_count does not match frozen atoms")
    if row["symbol"] == "" or not isinstance(row["symbol"], str):
        raise ValueError("symbol must be a non-empty string")
    if _day(row["signal_date"]) != signal or _day(row["entry_date"]) != entry or _day(row["exit_date"]) != exit_:
        raise ValueError("trigger dates disagree with daily calendar")
    if row["target_mature"] is not True:
        raise ValueError("only mature T+2 target rows may be appended")
    if not isinstance(row["target_observable"], bool) or not isinstance(row["suspended_target"], bool):
        raise ValueError("target flags must be booleans")
    if row["suspended_target"] and row["target_observable"]:
        raise ValueError("suspended target cannot be marked observable")
    source_time = _source(row["source"])
    target_time = _source(row["target_source"])
    if source_time > datetime.combine(signal, time(15), tzinfo=BEIJING):
        raise ValueError("factor source must be available by T close")
    if target_time < datetime.combine(exit_, time(9, 30), tzinfo=BEIJING):
        raise ValueError("target source cannot predate T+2 maturity")
    if row["target_observable"]:
        if row["missing_target_reason"] != "":
            raise ValueError("observable target cannot have a missing reason")
        if not all(_positive(row[key]) for key in ("entry_open", "exit_open", "entry_volume", "exit_volume")):
            raise ValueError("observable target requires positive opens and volumes")
    else:
        if not row["missing_target_reason"]:
            raise ValueError("mature missing target requires a reason")
        if any(row[key] is not None for key in ("entry_open", "exit_open", "entry_volume", "exit_volume")):
            raise ValueError("unobservable target must keep target prices and volumes null")


SIGNAL_RECORD_FIELDS = {
    "symbol", "signal_date", "rule_version", "logic", "change_pct_5d", "open_gap",
    "index_close_position", "atom_change_pct_5d", "atom_open_gap",
    "atom_index_close_position", "matched_rule_count", "selected", "source",
}


def _validate_signal_record(row: dict[str, Any], *, signal: date, scope: set[str], recorded: datetime) -> None:
    """Validate one T-close rule decision before any forward target is known.

    The complete scope is committed at T close, including non-triggering stocks.
    This makes an empty signal day an auditable observation rather than an
    omitted row that can be filled in retrospect.
    """
    if not isinstance(row, dict) or set(row) != SIGNAL_RECORD_FIELDS:
        raise ValueError("signal record has unknown or missing fields")
    symbol = row["symbol"]
    if not isinstance(symbol, str) or not symbol or symbol not in scope:
        raise ValueError("signal record symbol is outside the frozen P4 scope")
    if _day(row["signal_date"]) != signal:
        raise ValueError("signal record date disagrees with the daily commit")
    if row["rule_version"] != "P4-VOTE-001" or row["logic"] != "2-of-3":
        raise ValueError("signal record violates frozen P4-VOTE-001 contract")
    values = (row["change_pct_5d"], row["open_gap"], row["index_close_position"])
    if not all(_finite(value) for value in values):
        raise ValueError("signal factor values must be finite numbers")
    atoms = (values[0] <= -0.10, values[1] >= 0.02, values[2] <= 0.20)
    reported = (row["atom_change_pct_5d"], row["atom_open_gap"], row["atom_index_close_position"])
    if any(type(value) is not bool for value in reported) or tuple(reported) != atoms:
        raise ValueError("signal atoms do not match frozen P4 thresholds")
    count = row["matched_rule_count"]
    if type(count) is not int or count != sum(atoms):
        raise ValueError("signal matched_rule_count does not match frozen atoms")
    if type(row["selected"]) is not bool or row["selected"] != (count >= 2):
        raise ValueError("signal selected flag does not match the frozen two-of-three rule")
    source_time = _source(row["source"])
    close = datetime.combine(signal, time(15), tzinfo=BEIJING)
    if source_time > close or source_time > recorded:
        raise ValueError("signal source must be available by T close")


def _signal_contract(row: dict[str, Any]) -> dict[str, Any]:
    """Return the fields that must remain identical between T and T+2."""
    return {key: row[key] for key in SIGNAL_RECORD_FIELDS if key != "source"}


class ForwardLedger:
    """Hash-chained append-only ledger with a one-way final lock."""

    def __init__(self, ledger: Path, lock: Path, registration: dict[str, Any]):
        self.ledger = Path(ledger)
        self.lock = Path(lock)
        self.registration = registration
        if registration.get("experiment_id") != "P4-VOTE-001":
            raise ValueError("wrong experiment registration")
        if registration.get("rule_version") != "P4-VOTE-001":
            raise ValueError("registration rule version is not frozen P4-VOTE-001")
        if registration.get("prediction") != "T close -> T+1 market-session open to T+2 market-session open":
            raise ValueError("registration prediction cycle is not frozen")
        if registration.get("up_definition") != "exit_open > entry_open":
            raise ValueError("registration up definition is not frozen")
        if registration.get("status") == UNCONFIRMED_STATUS:
            raise ValueError("P4-VOTE-001 window and stock scope are not independently frozen")
        expected = registration.get("registration_sha256")
        if expected != digest({k: v for k, v in registration.items() if k != "registration_sha256"}):
            raise ValueError("registration logical hash mismatch")
        window = registration.get("window_freeze", {})
        if window.get("start") != WINDOW_START.isoformat() or window.get("end") != WINDOW_END.isoformat():
            raise ValueError("registration window does not match the frozen P4 window")
        scope = registration.get("stock_scope", {})
        symbols = scope.get("symbols")
        if not isinstance(symbols, list) or len(symbols) != 100 or len(set(symbols)) != 100:
            raise ValueError("registration must freeze 100 unique P4 stock-scope symbols")
        if scope.get("path") != "research/normal_stock_65/phase5/pit_assignment.csv" \
                or scope.get("sha256") != FROZEN_SCOPE_SHA256:
            raise ValueError("registration PIT100 stock-scope hash is not the frozen P4 assignment")
        if not FROZEN_SCOPE_PATH.is_file() or sha256_file(FROZEN_SCOPE_PATH) != FROZEN_SCOPE_SHA256:
            raise ValueError("frozen PIT100 assignment file is unavailable or changed")
        with FROZEN_SCOPE_PATH.open(encoding="utf-8", newline="") as handle:
            frozen_symbols = sorted({row["symbol"] for row in csv.DictReader(handle)})
        if symbols != frozen_symbols:
            raise ValueError("registration symbols do not match the frozen PIT100 assignment")
        if registration.get("rule_sha256") != "9c4bd808c4246a13668d2c5db83a45f7e3ab336a72046a34b407c85b14af29cc":
            raise ValueError("registration rule source hash is not the frozen P4 source")
        if registration.get("frozen_result_sha256") != "b4501775cb5e57982648624b6400d3312be0a2a5e80467fbc77e71631651d8a7":
            raise ValueError("registration frozen-result hash is not the frozen P4 result")
        self.scope_symbols = set(symbols)

    def records(self) -> list[dict[str, Any]]:
        if not self.ledger.exists():
            return []
        rows: list[dict[str, Any]] = []
        previous = None
        for sequence, line in enumerate(self.ledger.read_text(encoding="utf-8").splitlines(), 1):
            record = json.loads(line)
            checksum = record.pop("record_sha256", None)
            if record.get("sequence") != sequence or record.get("previous_sha256") != previous or digest(record) != checksum:
                raise ValueError("forward ledger hash chain mismatch")
            if record.get("registration_sha256") != self.registration["registration_sha256"]:
                raise ValueError("ledger registration hash mismatch")
            record["record_sha256"] = checksum
            rows.append(record)
            previous = checksum
        return rows

    def _append(self, kind: str, payload: dict[str, Any], recorded_at: datetime) -> dict[str, Any]:
        if self.lock.exists():
            raise ValueError("final lock already exists")
        rows = self.records()
        now = recorded_at.astimezone(BEIJING)
        if rows and now < _aware(rows[-1]["recorded_at"]):
            raise ValueError("recorded_at cannot move backward")
        record = {
            "sequence": len(rows) + 1, "kind": kind, "payload": payload,
            "recorded_at": now.isoformat(), "registration_sha256": self.registration["registration_sha256"],
            "previous_sha256": rows[-1]["record_sha256"] if rows else None,
        }
        record["record_sha256"] = digest(record)
        self.ledger.parent.mkdir(parents=True, exist_ok=True)
        with self.ledger.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
            handle.flush(); os.fsync(handle.fileno())
        return record

    def append_signal_commit(self, payload: dict[str, Any], recorded_at: datetime) -> dict[str, Any]:
        """Commit the complete T-close signal decision for one market day.

        This is the only method that can create a new signal day.  It must be
        called on that day after the frozen factor source is available.  The
        full 100-stock scope is required, including ``selected=false`` rows,
        so a later target import cannot invent or omit a signal day.
        """
        if set(payload) != {"signal_date", "entry_date", "exit_date", "coverage_source", "rows"}:
            raise ValueError("signal commit fields must include the complete scope and calendar")
        signal = _day(payload["signal_date"]); entry = _day(payload["entry_date"]); exit_ = _day(payload["exit_date"])
        recorded = recorded_at.astimezone(BEIJING)
        if not WINDOW_START <= signal <= WINDOW_END or not signal < entry < exit_:
            raise ValueError("signal commit date outside frozen window or bad T+1/T+2 order")
        close = datetime.combine(signal, time(15), tzinfo=BEIJING)
        if recorded.date() != signal or recorded < close:
            raise ValueError("signal commit must be recorded on T after the close")
        coverage_time = _source(payload["coverage_source"])
        if coverage_time > recorded or coverage_time > close:
            raise ValueError("signal coverage source must be available by T close")
        rows = payload["rows"]
        if not isinstance(rows, list) or len(rows) != len(self.scope_symbols):
            raise ValueError("signal commit must contain exactly one row for every frozen scope symbol")
        symbols: set[str] = set()
        selected = 0
        for row in rows:
            _validate_signal_record(row, signal=signal, scope=self.scope_symbols, recorded=recorded)
            if row["symbol"] in symbols:
                raise ValueError("duplicate symbol in signal commit")
            symbols.add(row["symbol"])
            selected += int(row["selected"])
        if symbols != self.scope_symbols:
            raise ValueError("signal commit does not cover the frozen stock scope")
        existing = self.records()
        if any(r["kind"] == "signal_commit" and r["payload"]["signal_date"] == signal.isoformat() for r in existing):
            raise ValueError("duplicate signal date")
        if any(r["kind"] == "signal_commit" and r["payload"]["entry_date"] == entry.isoformat() for r in existing):
            raise ValueError("duplicate entry date")
        return self._append("signal_commit", {**payload, "selected_count": selected}, recorded_at)

    def append_target_batch(self, payload: dict[str, Any], recorded_at: datetime) -> dict[str, Any]:
        """Attach mature T+2 targets to an existing immutable signal commit."""
        required = {"signal_date", "entry_date", "exit_date", "target_source", "baseline_records", "rows"}
        if set(payload) != required:
            raise ValueError("target batch fields must include mature targets and same-date baseline")
        signal = _day(payload["signal_date"]); entry = _day(payload["entry_date"]); exit_ = _day(payload["exit_date"])
        recorded = recorded_at.astimezone(BEIJING)
        maturity = datetime.combine(exit_, time(9, 30), tzinfo=BEIJING)
        if recorded.date() != exit_ or recorded < maturity:
            raise ValueError("target batch must be recorded on T+2 after the open")
        target_time = _source(payload["target_source"])
        if target_time < maturity or target_time > recorded:
            raise ValueError("target source must be available by the target append time")
        records = self.records()
        commits = [r for r in records if r["kind"] == "signal_commit"
                   and r["payload"]["signal_date"] == signal.isoformat()]
        if len(commits) != 1:
            raise ValueError("target batch requires one existing T-close signal commit")
        commit = commits[0]["payload"]
        if commit["entry_date"] != entry.isoformat() or commit["exit_date"] != exit_.isoformat():
            raise ValueError("target batch dates disagree with the committed signal calendar")
        if any(r["kind"] == "target_batch" and r["payload"]["signal_date"] == signal.isoformat() for r in records):
            raise ValueError("duplicate target batch")
        commit_by_symbol = {row["symbol"]: row for row in commit["rows"]}
        selected_symbols = {symbol for symbol, row in commit_by_symbol.items() if row["selected"]}
        rows = payload["rows"]
        if (not isinstance(rows, list) or len(rows) != len(selected_symbols)
                or len({row.get("symbol") for row in rows}) != len(rows)
                or {row.get("symbol") for row in rows} != selected_symbols):
            raise ValueError("target rows must exactly match selected symbols in the signal commit")
        for row in rows:
            if row.get("symbol") not in self.scope_symbols:
                raise ValueError("target symbol is outside the frozen P4 stock scope")
            _validate_trigger(row, signal=signal, entry=entry, exit_=exit_)
            committed = commit_by_symbol[row["symbol"]]
            for key in ("rule_version", "logic",
                        "change_pct_5d", "open_gap", "index_close_position", "atom_change_pct_5d",
                        "atom_open_gap", "atom_index_close_position", "matched_rule_count", "source"):
                if row[key] != committed[key]:
                    raise ValueError("target row changes an immutable T-close signal field")
            if row["signal_date"] != commit["signal_date"] or row["entry_date"] != commit["entry_date"] \
                    or row["exit_date"] != commit["exit_date"]:
                raise ValueError("target row changes the immutable T-close calendar")
            # The batch-level source is an envelope; every row's own target
            # source must also have been available by this T+2 append.  Do
            # not allow a row to smuggle in a future quote timestamp.
            if _source(row["target_source"]) > recorded:
                raise ValueError("target row source cannot be available after target append time")
        baselines = payload["baseline_records"]
        if not isinstance(baselines, list) or len(baselines) != len(self.scope_symbols):
            raise ValueError("same-date baseline must contain every frozen scope symbol")
        baseline_symbols: set[str] = set()
        baseline_fields = {"symbol", "entry_date", "eligible", "correct", "source",
                           "entry_open", "exit_open", "entry_volume", "exit_volume",
                           "target_observable", "suspended_target", "missing_target_reason"}
        for baseline in baselines:
            if not isinstance(baseline, dict) or set(baseline) != baseline_fields:
                raise ValueError("baseline row has unknown or missing fields")
            symbol = baseline["symbol"]
            if not isinstance(symbol, str) or symbol not in self.scope_symbols or symbol in baseline_symbols:
                raise ValueError("baseline symbols must be unique and inside the frozen scope")
            if _day(baseline["entry_date"]) != entry or type(baseline["eligible"]) is not bool \
                    or type(baseline["correct"]) is not bool:
                raise ValueError("baseline row disagrees with the frozen entry date or boolean labels")
            if not baseline["eligible"] and baseline["correct"]:
                raise ValueError("ineligible baseline row cannot be marked correct")
            if type(baseline["target_observable"]) is not bool or type(baseline["suspended_target"]) is not bool:
                raise ValueError("baseline target flags must be booleans")
            observable = baseline["target_observable"] and not baseline["suspended_target"]
            if not isinstance(baseline["missing_target_reason"], str):
                raise ValueError("baseline missing target reason must be a string")
            if observable:
                if baseline["missing_target_reason"] or not all(_positive(baseline[key]) for key in
                                                                  ("entry_open", "exit_open", "entry_volume", "exit_volume")):
                    raise ValueError("observable baseline requires positive opens/volumes and no missing reason")
            else:
                if not baseline["missing_target_reason"] or any(baseline[key] is not None for key in
                                                                  ("entry_open", "exit_open", "entry_volume", "exit_volume")):
                    raise ValueError("unobservable baseline requires reason and null target values")
            if baseline["correct"] is not (baseline["eligible"] and observable
                                             and baseline["exit_open"] > baseline["entry_open"]):
                raise ValueError("baseline correctness does not match target values")
            if _source(baseline["source"]) > recorded:
                raise ValueError("baseline source cannot be available after target append time")
            baseline_symbols.add(symbol)
        if baseline_symbols != self.scope_symbols:
            raise ValueError("same-date baseline does not cover the frozen stock scope")
        return self._append("target_batch", payload, recorded_at)

    def append_batch(self, payload: dict[str, Any], recorded_at: datetime) -> dict[str, Any]:
        raise ValueError("append_batch is disabled; use append_signal_commit at T close and append_target_batch at T+2")

    def append_coverage(self, payload: dict[str, Any], recorded_at: datetime) -> dict[str, Any]:
        if set(payload) != {"actual_market_sessions", "source", "calendar_artifact"}:
            raise ValueError("coverage must contain sessions, source and independent calendar artifact")
        sessions = payload["actual_market_sessions"]
        if not isinstance(sessions, list) or not sessions:
            raise ValueError("actual market sessions must be a non-empty list")
        parsed = [_day(value) for value in sessions]
        if parsed != sorted(set(parsed)) or any(not WINDOW_START <= value <= WINDOW_END for value in parsed):
            raise ValueError("actual market sessions must be ordered, unique and within the frozen window")
        source_time = _source(payload["source"])
        artifact = payload["calendar_artifact"]
        if not isinstance(artifact, dict) or set(artifact) != {"path", "sha256", "provider", "reference", "available_at"}:
            raise ValueError("calendar_artifact must identify an independent dated file and source")
        _source({"provider": artifact["provider"], "reference": artifact["reference"],
                 "available_at": artifact["available_at"]})
        calendar_map = _calendar_file(artifact["path"], parsed, artifact["sha256"])
        now = recorded_at.astimezone(BEIJING)
        if now.date() <= WINDOW_END or source_time > now:
            raise ValueError("coverage can only be confirmed after the frozen window")
        existing = self.records()
        if any(record["kind"] == "coverage" for record in existing):
            raise ValueError("coverage already confirmed")
        commit_dates = sorted(_day(record["payload"]["signal_date"])
                              for record in existing if record["kind"] == "signal_commit")
        target_dates = sorted(_day(record["payload"]["signal_date"])
                              for record in existing if record["kind"] == "target_batch")
        if commit_dates != parsed or target_dates != parsed:
            raise ValueError("coverage must exactly match one committed signal and mature target per market session")
        for record in existing:
            if record["kind"] == "signal_commit":
                payload_row = record["payload"]
                expected = calendar_map[_day(payload_row["signal_date"])]
                if (_day(payload_row["entry_date"]), _day(payload_row["exit_date"])) != expected:
                    raise ValueError("calendar artifact T+1/T+2 mapping disagrees with signal commit")
        return self._append("coverage", payload, recorded_at)

    def finalize(self, *, baseline_records: list[dict[str, Any]], now: datetime) -> dict[str, Any]:
        if self.lock.exists():
            raise ValueError("final lock already exists")
        rows = self.records()
        coverage = [record["payload"] for record in rows if record["kind"] == "coverage"]
        commits = [record["payload"] for record in rows if record["kind"] == "signal_commit"]
        batches = [record["payload"] for record in rows if record["kind"] == "target_batch"]
        if len(coverage) != 1:
            return {"status": "待最终验证", "finalized": False,
                    "reason": "窗口结束后尚未登记完整市场交易日覆盖"}
        sessions = [_day(value) for value in coverage[0]["actual_market_sessions"]]
        if sorted(_day(commit["signal_date"]) for commit in commits) != sessions \
                or sorted(_day(batch["signal_date"]) for batch in batches) != sessions:
            return {"status": "待最终验证", "finalized": False,
                    "reason": "T close 提交、T+2 目标批次与完整市场交易日清单不一致"}
        triggers = [trigger for batch in batches for trigger in batch["rows"]]
        embedded_baselines = [baseline for batch in batches for baseline in batch["baseline_records"]]
        if baseline_records and baseline_records != embedded_baselines:
            raise ValueError("external baseline differs from hash-chained batch baseline")
        baselines = embedded_baselines
        result = evaluate_final(
            trigger_records=triggers,
            baseline_records=baselines,
            window_ended=now.astimezone(BEIJING).date() > WINDOW_END,
            coverage_complete=True,
            maturity_complete=all(_day(batch["exit_date"]) < now.astimezone(BEIJING).date() for batch in batches),
            as_of=now.astimezone(BEIJING).date(),
        )
        if not result.get("finalized"):
            return result
        rows_after = self.records()
        envelope = {"result": result, "result_sha256": digest(result), "finalized_at": now.astimezone(BEIJING).isoformat(),
                    "registration_sha256": self.registration["registration_sha256"],
                    "ledger_tip_sha256": rows_after[-1]["record_sha256"] if rows_after else None,
                    "ledger_record_count": len(rows_after)}
        self.lock.parent.mkdir(parents=True, exist_ok=True)
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        fd = os.open(self.lock, flags, 0o600)
        try:
            os.write(fd, (json.dumps(envelope, ensure_ascii=False, sort_keys=True) + "\n").encode())
            os.fsync(fd)
        finally:
            os.close(fd)
        return {**result, "final_lock_sha256": sha256_file(self.lock)}

    def export_final(self, output_dir: Path) -> dict[str, Any]:
        """Export only a locked terminal result and its complete trigger rows."""
        if not self.lock.exists():
            raise ValueError("cannot export before final lock")
        rows = self.records()
        lock_payload = _lock_payload(self.lock)
        if lock_payload.get("registration_sha256") != self.registration["registration_sha256"]:
            raise ValueError("final lock registration hash mismatch")
        if lock_payload.get("ledger_record_count") != len(rows) or lock_payload.get("ledger_tip_sha256") != (rows[-1]["record_sha256"] if rows else None):
            raise ValueError("final lock does not bind the current ledger tip")
        triggers = [trigger for record in rows if record["kind"] == "target_batch"
                    for trigger in record["payload"]["rows"]]
        output_dir = Path(output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)
        csv_path = output_dir / "FINAL_TRIGGER_RECORDS.csv"
        fields = sorted({key for trigger in triggers for key in trigger})
        import csv
        with csv_path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="raise")
            writer.writeheader(); writer.writerows(triggers)
        result_path = output_dir / "FINAL_RESULT.json"
        result_path.write_text(json.dumps(lock_payload["result"], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        artifacts = {
            "FINAL_LOCK.json": sha256_file(self.lock),
            "FINAL_TRIGGER_RECORDS.csv": sha256_file(csv_path),
            "FINAL_RESULT.json": sha256_file(result_path),
            "forward.jsonl": sha256_file(self.ledger),
        }
        artifact_path = output_dir / "FINAL_ARTIFACT_SHA256.json"
        artifact_path.write_text(json.dumps(artifacts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return {"result": str(result_path), "triggers": str(csv_path), "artifacts": artifacts}
