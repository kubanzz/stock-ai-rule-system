"""Focused checks for the time-safety and holding-period invariants."""

from __future__ import annotations

import unittest

import numpy as np
import pandas as pd

from research.normal_stock_65.phase4.probabilistic_search import (
    add_lagged_stock_win_feature,
    nonoverlapping,
)


class ProbabilisticSearchTest(unittest.TestCase):
    def test_history_uses_only_matured_exits(self) -> None:
        dates = pd.bdate_range("2024-01-01", periods=32)
        frame = pd.DataFrame({
            "symbol": ["600000.SH"] * 30,
            "trade_date": dates[:30],
            "entry_date": dates[1:31],
            "exit_date": dates[2:32],
            "entry_executable": [True] * 30,
            "blocked_exit": [False] * 30,
            "net_return": np.ones(30),
        })
        actual = add_lagged_stock_win_feature(frame)
        self.assertEqual(24, actual.loc[25, "lagged_stock_executable_n_252"])
        self.assertTrue(np.isnan(actual.loc[25, "lagged_stock_net_win_252"]))
        self.assertEqual(25, actual.loc[26, "lagged_stock_executable_n_252"])
        self.assertAlmostEqual((25 + 20) / (25 + 50), actual.loc[26, "lagged_stock_net_win_252"])

        # The current and later event results must not alter the feature at T.
        changed_future = frame.copy()
        changed_future.loc[25:, "net_return"] = -1.0
        after = add_lagged_stock_win_feature(changed_future)
        self.assertAlmostEqual(actual.loc[26, "lagged_stock_net_win_252"],
                               after.loc[26, "lagged_stock_net_win_252"])

    def test_same_stock_positions_do_not_overlap(self) -> None:
        dates = pd.bdate_range("2024-01-01", periods=5)
        frame = pd.DataFrame({
            "symbol": ["600000.SH", "600000.SH", "600000.SH", "000001.SZ"],
            "trade_date": [dates[0], dates[1], dates[2], dates[0]],
            "entry_date": [dates[1], dates[2], dates[3], dates[1]],
            "exit_date": [dates[2], dates[3], dates[4], dates[2]],
        })
        kept = nonoverlapping(frame)
        self.assertEqual({0, 2, 3}, set(kept.index))


if __name__ == "__main__":
    unittest.main()
