import csv
import json
import subprocess
import sys
from datetime import date, timedelta
from pathlib import Path
import csv


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def _symbols():
    with (ROOT / "research/normal_stock_65/phase5/pit_assignment.csv").open(encoding="utf-8", newline="") as handle:
        return sorted({row["symbol"] for row in csv.DictReader(handle)})


def test_report_is_reproducible_and_supported_status_is_explicit():
    subprocess.run([sys.executable, str(HERE / "generate_report.py")], cwd=ROOT, check=True, capture_output=True, text=True)
    result = json.loads((HERE / "RESULT.json").read_text(encoding="utf-8"))
    assert result["classification"] == "待最终验证"
    assert result["final_classification"] is None
    assert result["final_validation"]["statistics"]["observations"] == 0
    assert result["final_validation"]["statistics"]["mature_nonoverlap_signals"] == 0
    assert result["final_validation"]["window"]["finalized"] is False
    assert result["final_validation"]["window"]["status"] == "FROZEN_RULE_PENDING_36_MONTH_FINAL"
    assert result["rule"]["logic"] == "三选二（命中至少2条）"
    assert result["incremental_conditions"]["combination_count"] == 0
    rows = list(csv.DictReader((HERE / "FINAL_TRIGGER_RECORDS.csv").open(encoding="utf-8")))
    assert rows == []


def test_historical_diagnostics_match_frozen_results():
    result = json.loads((HERE / "RESULT.json").read_text(encoding="utf-8"))
    p5 = result["historical_diagnostics"]["phase5_pit_2024_2026"]["statistics"]
    p6 = result["historical_diagnostics"]["phase6_older_2010_2018"]["statistics"]
    assert (p5["correct"], p5["nonoverlap_signal_count"]) == (140, 209)
    assert (p6["correct"], p6["nonoverlap_signal_count"]) == (164, 288)
    assert p5["same_date_baseline"]["lift_event_weighted_pp"] == 1.472
    assert p6["same_date_baseline"]["lift_event_weighted_pp"] == 0.597


def test_final_evaluator_three_terminal_states_and_pending_gate():
    from final_evaluator import evaluate_final

    pending = evaluate_final(trigger_records=[], baseline_records=[], window_ended=False,
                             coverage_complete=False, maturity_complete=False)
    assert pending["status"] == "待最终验证"

    def row(symbol, signal, entry, correct):
        return {
            "symbol": symbol, "signal_date": signal, "entry_date": entry,
            "exit_date": "2029-10-01", "target_mature": True,
            "target_observable": True, "entry_open": 1.0,
            "exit_open": 2.0 if correct else 0.5, "entry_volume": 1.0,
            "exit_volume": 1.0, "rule_version": "P4-VOTE-001", "logic": "2-of-3",
            "change_pct_5d": -0.1, "open_gap": 0.02, "index_close_position": 0.2,
            "atom_change_pct_5d": True, "atom_open_gap": True, "atom_index_close_position": True,
            "matched_rule_count": 3, "suspended_target": False, "missing_target_reason": "",
            "source": {"provider": "fixture", "reference": "fixture://signal",
                        "available_at": f"{signal}T14:59:00+08:00"},
            "target_source": {"provider": "fixture", "reference": "fixture://target",
                               "available_at": "2029-10-01T09:30:00+08:00"},
        }

    symbols = _symbols()
    rows = [
        row(symbols[i], (date(2029, 1, 1) + timedelta(days=i)).isoformat(),
             (date(2029, 1, 2) + timedelta(days=i)).isoformat(), i < 64)
        for i in range(100)
    ]
    baselines = [{"symbol": r["symbol"], "entry_date": r["entry_date"], "eligible": True, "correct": False,
                  "source": {"provider": "fixture", "reference": "fixture://baseline",
                              "available_at": "2029-10-01T09:30:00+08:00"},
                  "entry_open": 1.0, "exit_open": 0.5, "entry_volume": 1.0, "exit_volume": 1.0,
                  "target_observable": True, "suspended_target": False, "missing_target_reason": ""}
                 for r in rows]
    failed = evaluate_final(trigger_records=rows, baseline_records=baselines, window_ended=True,
                            coverage_complete=True, maturity_complete=True, as_of=date(2030, 1, 2))
    assert failed["status"] == "未通过"
    assert failed["strict_nonoverlap_total"]["predictions"] == 100

    insufficient = evaluate_final(trigger_records=rows[:99], baseline_records=baselines[:99], window_ended=True,
                                  coverage_complete=True, maturity_complete=True, as_of=date(2030, 1, 2))
    assert insufficient["status"] == "支持不足"

    passed_rows = [
        row(symbols[i], (date(2029, 1, 1) + timedelta(days=i)).isoformat(),
             (date(2029, 1, 2) + timedelta(days=i)).isoformat(), True)
        for i in range(100)
    ]
    passed_baselines = [{"symbol": r["symbol"], "entry_date": r["entry_date"], "eligible": True, "correct": False,
                        "source": {"provider": "fixture", "reference": "fixture://baseline",
                                    "available_at": "2029-10-01T09:30:00+08:00"},
                        "entry_open": 1.0, "exit_open": 0.5, "entry_volume": 1.0, "exit_volume": 1.0,
                        "target_observable": True, "suspended_target": False, "missing_target_reason": ""}
                       for r in passed_rows]
    passed = evaluate_final(trigger_records=passed_rows, baseline_records=passed_baselines, window_ended=True,
                            coverage_complete=True, maturity_complete=True, as_of=date(2030, 1, 2))
    assert passed["status"] == "通过"
