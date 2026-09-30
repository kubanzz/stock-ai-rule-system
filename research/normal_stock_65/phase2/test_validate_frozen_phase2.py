"""Synthetic phase2 validation tests; sealed data is never opened."""

import json
import tempfile
import unittest
from pathlib import Path

import pandas as pd

from research.normal_stock_65.phase2.validate_frozen_phase2 import (
    apply_order_filter, load_frozen, position_metrics,
)


class Phase2FrozenValidatorTest(unittest.TestCase):
    def test_stock_only_up_net_three_atoms(self) -> None:
        payload = {"direction": "up_net", "conditions": [
            {"field": "return_1d", "op": "<=", "value": -.04},
            {"field": "change_pct_5d", "op": "<=", "value": -.07},
            {"field": "volume_ratio_5d", "op": "<=", "value": 1.0},
        ], "order_filter": {"T_plus_1_entry_gap": "<= -0.01"}}
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "frozen.json"
            path.write_text(json.dumps(payload))
            frozen, conditions = load_frozen(path)
        self.assertEqual((frozen["direction"], len(conditions)), ("up_net", 3))

    def test_optional_order_filter_only_changes_orders(self) -> None:
        frame = pd.DataFrame({"entry_gap": [-.02, 0, .02], "entry_executable": [True] * 3})
        payload = {"order_filter": {"T_plus_1_entry_gap": "<= -0.01"}}
        selected = apply_order_filter(frame, payload)
        self.assertEqual(len(frame), 3)
        self.assertEqual(len(selected), 1)

    def test_hs300_directional_schema(self) -> None:
        payload = {"direction": "down", "conditions": [
            {"field": "hs300_return_5d", "op": "<=", "value": -.03},
            {"field": "rsi14", "op": ">=", "value": 75},
        ]}
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "frozen.json"
            path.write_text(json.dumps(payload))
            _, conditions = load_frozen(path)
        self.assertEqual(len(conditions), 2)

    def test_missing_exit_is_failed_net_position(self) -> None:
        rows = pd.DataFrame({
            "symbol": ["000001.SZ"],
            "trade_date": pd.to_datetime(["2026-01-05"]),
            "entry_date": pd.to_datetime(["2026-01-06"]),
            "exit_date": pd.to_datetime(["2026-01-07"]),
            "entry_executable": [True], "blocked_exit": [True],
            "up_correct": [False], "net_return": [-.103],
        })
        metric = position_metrics(rows, "up")
        self.assertEqual((metric["positions"], metric["net_long_win_rate"], metric["blocked_exits"]), (1, 0, 1))

    def test_net_wilson_uses_net_wins_not_gross_direction(self) -> None:
        rows = pd.DataFrame({
            "symbol": ["000001.SZ", "000002.SZ"],
            "trade_date": pd.to_datetime(["2026-01-05", "2026-01-05"]),
            "entry_date": pd.to_datetime(["2026-01-06", "2026-01-06"]),
            "exit_date": pd.to_datetime(["2026-01-07", "2026-01-07"]),
            "entry_executable": [True, True], "blocked_exit": [False, False],
            "up_correct": [True, True], "net_return": [.01, -.001],
        })
        metric = position_metrics(rows, "up")
        self.assertEqual((metric["correct"], metric["net_wins"]), (2, 1))
        self.assertLess(metric["net_long_wilson95_lower"], metric["gross_direction_wilson95_lower"])


if __name__ == "__main__":
    unittest.main()
