import json
import csv
import sys
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from forward_ledger import ForwardLedger, digest  # noqa: E402


def _calendar_artifact(tmp_path, sessions):
    path = tmp_path / "market_sessions.txt"
    path.write_text("".join(
        f"{value},{(date.fromisoformat(value) + timedelta(days=1)).isoformat()},"
        f"{(date.fromisoformat(value) + timedelta(days=2)).isoformat()}\n" for value in sessions
    ), encoding="utf-8")
    import hashlib
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "provider": "exchange-fixture", "reference": "fixture://calendar",
            "available_at": "2030-01-02T15:00:00+08:00"}


def _source(when):
    return {"provider": "fixture", "reference": "fixture://p4", "available_at": when.isoformat()}


def _registration():
    with (HERE.parents[2] / "research/normal_stock_65/phase5/pit_assignment.csv").open(encoding="utf-8", newline="") as handle:
        symbols = sorted({row["symbol"] for row in csv.DictReader(handle)})
    base = {"schema_version": 1, "experiment_id": "P4-VOTE-001", "status": "FROZEN_RULE_PENDING_36_MONTH_FINAL",
            "window_freeze": {"start": "2027-01-01", "end": "2029-12-31"},
            "stock_scope": {"path": "research/normal_stock_65/phase5/pit_assignment.csv", "count": 100,
                            "symbols": symbols,
                            "sha256": "fa6b9a06e3ca0f79ccf0a3757961a03e68cc508b7dab770be2ec87102f7c3950"},
            "rule_version": "P4-VOTE-001",
            "prediction": "T close -> T+1 market-session open to T+2 market-session open",
            "up_definition": "exit_open > entry_open",
            "rule_sha256": "9c4bd808c4246a13668d2c5db83a45f7e3ab336a72046a34b407c85b14af29cc",
            "frozen_result_sha256": "b4501775cb5e57982648624b6400d3312be0a2a5e80467fbc77e71631651d8a7"}
    base["registration_sha256"] = digest(base)
    return base


def _batch(day, offset, wins):
    signal = day + timedelta(days=offset)
    entry = signal + timedelta(days=1)
    exit_ = signal + timedelta(days=2)
    tz = timezone(timedelta(hours=8), name="Asia/Shanghai")
    signal_recorded = datetime.combine(signal, time(15, 5), tzinfo=tz)
    signal_source = datetime.combine(signal, time(14, 59), tzinfo=tz)
    target_recorded = datetime.combine(exit_, time(10), tzinfo=tz)
    count = 4 if offset < 25 else 3
    symbols = _registration_symbols()
    selected_symbols = [symbols[(offset * 4 + number) % 100] for number in range(count)]
    # Duplicate symbols only recur after 25+ days, after their prior T+2
    # target is mature; this lets the evaluator exercise strict non-overlap.
    rows = []
    targets = []
    baselines = []
    for index in range(100):
        symbol = _registration_symbols()[index]
        selected = symbol in selected_symbols
        signal_row = {
            "symbol": symbol, "signal_date": signal.isoformat(), "rule_version": "P4-VOTE-001",
            "logic": "2-of-3", "change_pct_5d": -0.1 if selected else 0.0,
            "open_gap": 0.02 if selected else 0.0, "index_close_position": 0.2 if selected else 0.5,
            "atom_change_pct_5d": selected, "atom_open_gap": selected,
            "atom_index_close_position": selected, "matched_rule_count": 3 if selected else 0,
            "selected": selected, "source": _source(signal_source),
        }
        rows.append(signal_row)
        if selected:
            event_index = offset * 4 + selected_symbols.index(symbol)
            correct = event_index < wins
            target_signal = {key: value for key, value in signal_row.items() if key != "selected"}
            targets.append({
                **target_signal, "entry_date": entry.isoformat(), "exit_date": exit_.isoformat(),
                "entry_open": 1.0, "exit_open": 2.0 if correct else 0.5,
                "entry_volume": 100.0, "exit_volume": 100.0, "target_observable": True,
                "suspended_target": False, "target_mature": True, "missing_target_reason": "",
                "target_source": _source(datetime.combine(exit_, time(9, 30), tzinfo=tz)),
            })
        baselines.append({"symbol": symbol, "entry_date": entry.isoformat(), "eligible": True,
                          "correct": False, "source": _source(target_recorded),
                          "entry_open": 1.0, "exit_open": 0.5, "entry_volume": 100.0,
                          "exit_volume": 100.0, "target_observable": True,
                          "suspended_target": False, "missing_target_reason": ""})
    return {
        "commit": {"signal_date": signal.isoformat(), "entry_date": entry.isoformat(),
                   "exit_date": exit_.isoformat(), "coverage_source": _source(signal_source),
                   "rows": rows},
        "target": {"signal_date": signal.isoformat(), "entry_date": entry.isoformat(),
                   "exit_date": exit_.isoformat(), "target_source": _source(target_recorded),
                   "baseline_records": baselines, "rows": targets},
        "signal_recorded_at": signal_recorded, "target_recorded_at": target_recorded,
    }


def _registration_symbols():
    with (HERE.parents[2] / "research/normal_stock_65/phase5/pit_assignment.csv").open(encoding="utf-8", newline="") as handle:
        return sorted({row["symbol"] for row in csv.DictReader(handle)})


def test_hash_chain_duplicate_and_final_lock(tmp_path):
    registration = _registration()
    ledger = ForwardLedger(tmp_path / "forward.jsonl", tmp_path / "FINAL_LOCK.json", registration)
    start = date(2029, 1, 1)
    batches = []
    total_index = 0
    for offset in range(31):
        count = 4 if offset < 25 else 3
        batch = _batch(start, offset, wins=80)
        for row in batch["target"]["rows"]:
            row["exit_open"] = 2.0 if total_index < 80 else 0.5
            total_index += 1
        batches.append(batch)
    # A real stream commits each day's factors at T close. At each subsequent
    # T+2 open, the mature target is appended before that day's new T-close
    # commit; this keeps the hash-chain timestamps monotonic.
    for offset, batch in enumerate(batches):
        if offset >= 2:
            ledger.append_target_batch(batches[offset - 2]["target"], batches[offset - 2]["target_recorded_at"])
        ledger.append_signal_commit(batch["commit"], batch["signal_recorded_at"])
    for batch in batches[-2:]:
        ledger.append_target_batch(batch["target"], batch["target_recorded_at"])
    with pytest.raises(ValueError, match="duplicate"):
        ledger.append_signal_commit(batches[0]["commit"], batches[0]["signal_recorded_at"])
    tz = batches[-1]["target_recorded_at"].tzinfo
    sessions = [batch["commit"]["signal_date"] for batch in batches]
    ledger.append_coverage({"actual_market_sessions": sessions,
                            "source": _source(datetime(2030, 1, 2, 15, tzinfo=tz)),
                            "calendar_artifact": _calendar_artifact(tmp_path, sessions)},
                           datetime(2030, 1, 2, 16, tzinfo=tz))
    result = ledger.finalize(baseline_records=[], now=datetime(2030, 1, 2, 16, tzinfo=tz))
    assert result["status"] == "通过"
    assert result["strict_nonoverlap_total"]["predictions"] >= 100
    assert result["strict_nonoverlap_total"]["dates"] == 31
    assert (tmp_path / "FINAL_LOCK.json").exists()
    exported = ledger.export_final(tmp_path / "export")
    assert Path(exported["result"]).exists()
    assert Path(exported["triggers"]).exists()
    lock = tmp_path / "FINAL_LOCK.json"
    lock.write_text(lock.read_text().replace('"result_sha256"', '"result_sha256_tampered"', 1), encoding="utf-8")
    with pytest.raises(ValueError, match="final lock"):
        ledger.export_final(tmp_path / "export-tampered")
    with pytest.raises(ValueError, match="final lock"):
        ledger.finalize(baseline_records=[], now=datetime(2030, 1, 2, 16, tzinfo=tz))


def test_pending_before_window_end_and_chain_tamper(tmp_path):
    registration = _registration()
    ledger = ForwardLedger(tmp_path / "forward.jsonl", tmp_path / "FINAL_LOCK.json", registration)
    batch = _batch(date(2029, 1, 1), 0, wins=0)
    ledger.append_signal_commit(batch["commit"], batch["signal_recorded_at"])
    ledger.append_target_batch(batch["target"], batch["target_recorded_at"])
    pending = ledger.finalize(baseline_records=[], now=datetime(2029, 2, 1, 16, tzinfo=batch["target_recorded_at"].tzinfo))
    assert pending["status"] == "待最终验证"
    path = tmp_path / "forward.jsonl"
    raw = path.read_text().replace('"kind": "signal_commit"', '"kind": "tampered"', 1)
    path.write_text(raw)
    with pytest.raises(ValueError, match="hash chain"):
        ledger.records()


def test_two_phase_rejects_late_signal_commit_and_changed_target(tmp_path):
    registration = _registration()
    ledger = ForwardLedger(tmp_path / "forward.jsonl", tmp_path / "FINAL_LOCK.json", registration)
    batch = _batch(date(2029, 1, 1), 0, wins=0)
    late = batch["signal_recorded_at"] + timedelta(days=1)
    with pytest.raises(ValueError, match="on T after the close"):
        ledger.append_signal_commit(batch["commit"], late)
    ledger.append_signal_commit(batch["commit"], batch["signal_recorded_at"])
    changed = json.loads(json.dumps(batch["target"]))
    changed["rows"][0]["change_pct_5d"] = -0.2
    with pytest.raises(ValueError, match="immutable"):
        ledger.append_target_batch(changed, batch["target_recorded_at"])


def test_target_rejects_inconsistent_suspension_flag(tmp_path):
    registration = _registration()
    ledger = ForwardLedger(tmp_path / "forward.jsonl", tmp_path / "FINAL_LOCK.json", registration)
    batch = _batch(date(2029, 1, 1), 0, wins=0)
    ledger.append_signal_commit(batch["commit"], batch["signal_recorded_at"])
    batch["target"]["rows"][0]["suspended_target"] = True
    with pytest.raises(ValueError, match="suspended target"):
        ledger.append_target_batch(batch["target"], batch["target_recorded_at"])


def test_registration_requires_real_pit_scope_metadata(tmp_path):
    registration = _registration()
    registration["stock_scope"].pop("path")
    registration["registration_sha256"] = digest({k: v for k, v in registration.items() if k != "registration_sha256"})
    with pytest.raises(ValueError, match="stock-scope hash"):
        ForwardLedger(tmp_path / "forward.jsonl", tmp_path / "FINAL_LOCK.json", registration)
