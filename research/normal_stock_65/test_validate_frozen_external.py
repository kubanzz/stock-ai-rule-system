"""Synthetic execution checks; never reads the external validation sample."""

import json
import tempfile
import unittest
from pathlib import Path

import pandas as pd

from research.normal_stock_65.search_directional_rules import metrics
from research.normal_stock_65.validate_frozen_external import load_frozen, position_metrics
from research.one_day_20260930.quant_research import nonoverlapping


class FrozenExternalSyntheticTest(unittest.TestCase):
    def test_one_frozen_three_condition_rule_is_accepted(self) -> None:
        payload = {"direction": "down", "conditions": [
            {"field": "rsi14", "op": ">=", "value": 75},
            {"field": "return_1d", "op": ">=", "value": .01},
            {"field": "return_2d", "op": "<=", "value": .05},
        ]}
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "frozen.json"
            path.write_text(json.dumps(payload))
            frozen, conditions = load_frozen(path)
        self.assertEqual(frozen["direction"], "down")
        self.assertEqual(len(conditions), 3)

    def test_missing_entry_stays_in_signal_denominator_and_fails(self) -> None:
        rows = self._rows()
        rows.loc[0, "blocked_entry"] = True
        rows.loc[0, "entry_executable"] = False
        rows.loc[0, "direction_observable"] = False
        rows.loc[0, "up_correct"] = False
        rows.loc[0, "down_correct"] = False
        result = metrics(rows, "down")
        self.assertEqual((result["events"], result["correct"], result["long_executable_entry_events"]), (2, 1, 1))

    def test_limit_up_is_execution_block_but_observed_direction_can_be_correct(self) -> None:
        rows = self._rows()
        rows.loc[0, "blocked_entry"] = True
        rows.loc[0, "entry_executable"] = False
        rows.loc[0, "up_correct"] = True
        result = metrics(rows, "up")
        self.assertEqual(result["events"], 2)
        self.assertEqual(result["correct"], 2)
        self.assertEqual(result["long_executable_entry_events"], 1)

    def test_missing_exit_and_limit_down_are_failures(self) -> None:
        rows = self._rows()
        rows["blocked_exit"] = True
        rows["direction_observable"] = False
        rows["up_correct"] = False
        rows["down_correct"] = False
        result = metrics(rows, "down")
        self.assertEqual(result["events"], 2)
        self.assertEqual(result["correct"], 0)
        self.assertEqual(result["blocked_exits"], 2)

    def test_calendar_boundary_requires_full_signal_and_exit_in_period(self) -> None:
        rows = self._rows()
        rows.loc[0, "trade_date"] = pd.Timestamp("2025-12-31")
        rows.loc[0, "entry_date"] = pd.Timestamp("2026-01-02")
        rows.loc[0, "exit_date"] = pd.Timestamp("2026-01-05")
        selected = rows.loc[rows["trade_date"].ge("2026-01-01") & rows["exit_date"].le("2026-09-29")]
        self.assertEqual(len(selected), 1)

    def test_same_symbol_overlapping_signal_is_deduplicated(self) -> None:
        rows = self._rows()
        extra = rows.iloc[[0]].copy()
        extra["trade_date"] = pd.Timestamp("2026-01-06")
        extra["entry_date"] = pd.Timestamp("2026-01-07")
        extra["exit_date"] = pd.Timestamp("2026-01-08")
        merged = pd.concat([rows.iloc[[0]], extra], ignore_index=True)
        self.assertEqual(len(nonoverlapping(merged)), 1)
        self.assertEqual(position_metrics(merged, "up")["positions"], 1)

    @staticmethod
    def _rows() -> pd.DataFrame:
        return pd.DataFrame({
            "symbol": ["000001.SZ", "000002.SZ"],
            "trade_date": pd.to_datetime(["2026-01-05", "2026-01-05"]),
            "entry_date": pd.to_datetime(["2026-01-06", "2026-01-06"]),
            "exit_date": pd.to_datetime(["2026-01-07", "2026-01-07"]),
            "up_correct": [True, True], "down_correct": [False, True],
            "blocked_entry": [False, False], "blocked_exit": [False, False],
            "entry_executable": [True, True],
            "direction_observable": [True, True],
            "net_return": [0.01, -0.01],
            "would_lose_long_net": [False, True],
        })


if __name__ == "__main__":
    unittest.main()
