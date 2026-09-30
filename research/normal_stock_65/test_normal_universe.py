"""Run with ``python3 -m unittest research.normal_stock_65.test_normal_universe``."""

import unittest

import numpy as np
import pandas as pd

from research.normal_stock_65.normal_universe import add_normal_universe_eligibility


class NormalUniverseTest(unittest.TestCase):
    def setUp(self) -> None:
        dates = pd.bdate_range("2024-01-01", periods=280)
        self.quotes = pd.DataFrame({
            "symbol": "600001.SH",
            "trade_date": dates,
            "close_price": 10 * np.cumprod(np.full(len(dates), 1.001)),
            "amount": 100_000_000,
        })

    def test_uses_only_data_through_signal_day(self) -> None:
        original = add_normal_universe_eligibility(self.quotes)
        changed = self.quotes.copy()
        changed.loc[changed.index > 260, "close_price"] *= 10
        changed.loc[changed.index > 260, "amount"] = 1
        replayed = add_normal_universe_eligibility(changed)
        pd.testing.assert_series_equal(
            original.loc[:260, "normal_universe_eligible"],
            replayed.loc[:260, "normal_universe_eligible"],
        )
        self.assertFalse(bool(original.loc[250, "normal_universe_eligible"]))
        self.assertTrue(bool(original.loc[251, "normal_universe_eligible"]))

    def test_rejects_non_mainboard_and_extreme_recent_move(self) -> None:
        mainboard = self.quotes.copy()
        mainboard.loc[259, "close_price"] *= 1.2
        result = add_normal_universe_eligibility(mainboard)
        self.assertFalse(bool(result.loc[259, "normal_universe_eligible"]))
        self.assertFalse(bool(result.loc[275, "normal_universe_eligible"]))
        chinext = self.quotes.copy()
        chinext["symbol"] = "300001.SZ"
        self.assertFalse(add_normal_universe_eligibility(chinext)["normal_universe_eligible"].any())

    def test_rolling_features_align_for_interleaved_symbols_and_shuffled_index(self) -> None:
        second = self.quotes.copy()
        second["symbol"] = "000001.SZ"
        second["close_price"] *= 2
        second["amount"] *= 3
        second.loc[270, "close_price"] *= 1.05
        interleaved = pd.concat([self.quotes, second]).sort_values(
            ["trade_date", "symbol"], ascending=[False, False]
        ).reset_index(drop=True)
        actual = add_normal_universe_eligibility(interleaved).sort_values(
            ["symbol", "trade_date"]
        ).reset_index(drop=True)
        reference = pd.concat([
            add_normal_universe_eligibility(self.quotes),
            add_normal_universe_eligibility(second),
        ]).sort_values(["symbol", "trade_date"]).reset_index(drop=True)
        for field in (
            "normal_history_bars", "normal_median_amount_20d", "normal_volatility_20d",
            "normal_max_abs_return_20d", "normal_universe_eligible",
        ):
            pd.testing.assert_series_equal(actual[field], reference[field])


if __name__ == "__main__":
    unittest.main()
