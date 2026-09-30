#!/usr/bin/env python3
"""Read-only, reproducible one-day A-share long-signal research.

Signal is formed after T close; enter at T+1 open; exit at T+2 open.
No database writes occur. This is research, never an automatic publication gate.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import pandas as pd


COST = 0.003  # minimum round-trip fees plus slippage; 30 basis points
LOCAL_SYMBOLS = [
    "000001.SZ", "000002.SZ", "002063.SZ", "002714.SZ",
    "300454.SZ", "600009.SH", "600519.SH",
]
TRAIN_END = "2024-12-31"
VALID_END = "2025-12-31"
LOCAL_TRAIN_END = "2026-03-31"
LOCAL_VALID_END = "2026-06-30"


@dataclass(frozen=True)
class Predicate:
    field: str
    operator: str
    value: float | str

    def mask(self, frame: pd.DataFrame) -> np.ndarray:
        values = frame[self.field]
        if self.operator == "lte":
            return (values <= self.value).to_numpy(dtype=bool)
        if self.operator == "gte":
            return (values >= self.value).to_numpy(dtype=bool)
        if self.operator == "lt":
            return (values < self.value).to_numpy(dtype=bool)
        if self.operator == "gt":
            return (values > self.value).to_numpy(dtype=bool)
        return (values == self.value).to_numpy(dtype=bool)


def load_local_quotes() -> pd.DataFrame:
    password = os.environ.get("MYSQL_PASSWORD") or os.environ.get("MYSQL_PWD")
    if password is None:
        # The local development file already contains a default; do not print it.
        config = Path(__file__).parents[2] / "stock-ai-rule-system-service/src/main/resources/application-dev.yml"
        match = re.search(r"password: \$\{MYSQL_PASSWORD:([^}]*)\}", config.read_text())
        if match:
            password = match.group(1)
    if not password:
        raise RuntimeError("Set MYSQL_PASSWORD or MYSQL_PWD for local read-only extraction")
    query = (
        "SELECT symbol,trade_date,open_price,high_price,low_price,close_price,"
        "pre_close,volume,amount,data_source FROM stock_daily_quote WHERE symbol IN ("
        + ",".join("'" + symbol + "'" for symbol in LOCAL_SYMBOLS)
        + ") ORDER BY symbol,trade_date"
    )
    run = subprocess.run(
        ["mysql", "-h127.0.0.1", "-P3306", "-uroot", "-Dstock-ai-rule", "-B", "-e", query],
        capture_output=True, text=True, check=True,
        env={**os.environ, "MYSQL_PWD": password},
    )
    from io import StringIO

    return pd.read_csv(StringIO(run.stdout), sep="\t", na_values=["NULL"])


def load_quotes(path: str | None) -> pd.DataFrame:
    frame = pd.read_csv(path) if path else load_local_quotes()
    frame = frame.rename(columns={
        "date": "trade_date", "open": "open_price", "high": "high_price",
        "low": "low_price", "close": "close_price", "source": "data_source",
    })
    required = {"symbol", "trade_date", "open_price", "high_price", "low_price", "close_price", "volume", "amount"}
    missing = required.difference(frame.columns)
    if missing:
        raise ValueError(f"Missing quote columns: {sorted(missing)}")
    frame["trade_date"] = pd.to_datetime(frame["trade_date"])
    frame = frame[frame["symbol"].str.match(r"^\d{6}\.(SZ|SH|BJ)$")].copy()
    if "data_source" in frame:
        frame = frame[~frame["data_source"].fillna("").str.lower().eq("mock")]
    for field in ("open_price", "high_price", "low_price", "close_price", "volume", "amount"):
        frame[field] = pd.to_numeric(frame[field], errors="coerce")
    frame = frame.dropna(subset=["trade_date", "open_price", "high_price", "low_price", "close_price", "volume", "amount"])
    frame = frame[(frame[["open_price", "high_price", "low_price", "close_price", "volume", "amount"]] > 0).all(axis=1)]
    frame = frame.drop_duplicates(["symbol", "trade_date"], keep="last").sort_values(["symbol", "trade_date"])
    return frame.reset_index(drop=True)


def calculate_factors(quotes: pd.DataFrame) -> pd.DataFrame:
    result = []
    for _, group in quotes.groupby("symbol", sort=True):
        group = group.copy()
        close = group["close_price"]
        volume = group["volume"]
        group["ma5"] = close.rolling(5).mean()
        group["ma20"] = close.rolling(20).mean()
        diff = close.diff()
        gains = diff.clip(lower=0).rolling(14).sum()
        losses = (-diff.clip(upper=0)).rolling(14).sum()
        rs = gains / losses.replace(0, np.nan)
        group["rsi14"] = (100 - 100 / (1 + rs)).fillna(100).where(losses.notna())
        ema12 = close.ewm(span=12, adjust=False).mean()
        ema26 = close.ewm(span=26, adjust=False).mean()
        dif = ema12 - ema26
        dea = dif.ewm(span=9, adjust=False).mean()
        group["macd_histogram"] = dif - dea
        group["volume_ratio_5d"] = volume / volume.shift(1).rolling(5).mean()
        group["change_pct_5d"] = close / close.shift(5) - 1
        up = (close >= group["ma5"]) & (group["ma5"] >= group["ma20"])
        down = (close <= group["ma5"]) & (group["ma5"] <= group["ma20"])
        group["short_term_trend"] = np.select(
            [up & (group["change_pct_5d"] >= .05), up,
             down & (group["change_pct_5d"] <= -.05), down],
            ["strong_up", "up", "strong_down", "down"], default="sideways",
        )
        vr = group["volume_ratio_5d"]
        group["volume_status"] = np.select(
            [vr >= 2, vr >= 1.3, vr <= .5],
            ["abnormal_high", "high", "low"], default="normal",
        )
        hist = group["macd_histogram"]
        group["technical_status"] = np.select(
            [group["rsi14"] <= 20,
             (group["rsi14"] >= 85) & group["volume_status"].eq("abnormal_high"),
             up & (hist > 0), down & (hist < 0)],
            ["oversold", "overbought", "bullish", "bearish"], default="neutral",
        )
        group["risk_status"] = np.where(
            group["technical_status"].eq("overbought") | group["volume_status"].eq("abnormal_high"),
            "high_risk", "normal",
        )
        day_change = close / close.shift(1) - 1
        group.loc[(group["risk_status"] == "normal") & day_change.abs().ge(.07), "risk_status"] = "caution"
        group["history_days"] = np.arange(1, len(group) + 1)
        result.append(group)
    return pd.concat(result, ignore_index=True)


def build_events(frame: pd.DataFrame) -> pd.DataFrame:
    frame = frame.copy()
    # A market date must have at least half of the maximum observed active universe.
    counts = frame.groupby("trade_date")["symbol"].nunique()
    calendar = counts[counts >= max(5, math.ceil(counts.max() * .5))].index.sort_values()
    mapping = {date: index for index, date in enumerate(calendar)}
    frame["calendar_index"] = frame["trade_date"].map(mapping)
    group = frame.groupby("symbol", sort=False)
    frame["entry_date"] = group["trade_date"].shift(-1)
    frame["exit_date"] = group["trade_date"].shift(-2)
    frame["entry_open"] = group["open_price"].shift(-1)
    frame["exit_open"] = group["open_price"].shift(-2)
    frame["entry_volume"] = group["volume"].shift(-1)
    frame["exit_volume"] = group["volume"].shift(-2)
    frame["entry_amount"] = group["amount"].shift(-1)
    frame["exit_amount"] = group["amount"].shift(-2)
    frame["entry_close"] = group["close_price"].shift(-1)
    frame["entry_cal_index"] = frame["entry_date"].map(mapping)
    frame["exit_cal_index"] = frame["exit_date"].map(mapping)
    # The intended exit is the market's T+2 session, even when the stock has
    # no tradable bar that day. Preserve the intended date in the split and
    # count such an already-entered position as a blocked exit.
    expected_exit = {index: calendar[index + 2] for index in range(len(calendar) - 2)}
    frame["exit_date"] = frame["calendar_index"].map(expected_exit)
    frame["entry_gap"] = frame["entry_open"] / frame["close_price"] - 1
    frame["exit_gap"] = frame["exit_open"] / frame["entry_close"] - 1
    frame["limit_fraction"] = np.select(
        [frame["symbol"].str.endswith(".BJ"),
         frame["symbol"].str.match(r"^(300|301|688|689)\d{3}\.")],
        [.30, .20], default=.10,
    )
    # Exclude likely unbuyable entry limit-up opens. Limit-down exits count as
    # failures, because an investor may have to hold beyond the intended day.
    frame["blocked_exit"] = (
        frame["exit_cal_index"].ne(frame["calendar_index"] + 2)
        | frame["exit_volume"].le(0)
        | frame["exit_gap"].le(-frame["limit_fraction"] + .005)
    )
    frame["net_return"] = frame["exit_open"] / frame["entry_open"] - 1 - COST
    frame.loc[frame["blocked_exit"], "net_return"] = -frame.loc[frame["blocked_exit"], "limit_fraction"] - COST
    frame["win"] = (frame["net_return"] > 0) & ~frame["blocked_exit"]
    eligible = (
        frame["history_days"].ge(26)
        & frame["calendar_index"].notna()
        & frame["exit_date"].notna()
        & frame["entry_cal_index"].eq(frame["calendar_index"] + 1)
        & frame["entry_gap"].lt(frame["limit_fraction"] - .005)
        & frame["entry_volume"].gt(0)
        & frame["amount"].ge(20_000_000)
        & frame["risk_status"].eq("normal")
        & frame["rsi14"].notna() & frame["volume_ratio_5d"].notna()
    )
    return frame.loc[eligible].copy().reset_index(drop=True)


def predicates() -> list[Predicate]:
    result = []
    for field, op, values in [
        ("rsi14", "lte", [20, 25, 30, 35, 40, 45, 50]),
        ("rsi14", "gte", [55, 60, 65, 70, 75, 80]),
        ("volume_ratio_5d", "lte", [.5, .7, 1.0]),
        ("volume_ratio_5d", "gte", [1.3, 1.5, 2.0]),
        ("change_pct_5d", "lte", [-.10, -.07, -.05, -.03, 0]),
        ("change_pct_5d", "gte", [0, .03, .05, .07, .10]),
        ("macd_histogram", "gt", [0]),
        ("macd_histogram", "lt", [0]),
    ]:
        result.extend(Predicate(field, op, value) for value in values)
    for field, values in [
        ("short_term_trend", ["up", "strong_up", "down", "strong_down", "sideways"]),
        ("technical_status", ["bullish", "bearish", "oversold", "neutral"]),
        ("volume_status", ["normal", "high", "low"]),
    ]:
        result.extend(Predicate(field, "eq", value) for value in values)
    return result


def rule_candidates() -> list[tuple[Predicate, ...]]:
    atoms = predicates()
    rules = [(atom,) for atom in atoms]
    rules.extend(
        (first, second) for i, first in enumerate(atoms)
        for second in atoms[i + 1:] if first.field != second.field
    )
    return rules


def wilson(wins: int, count: int) -> float:
    if not count:
        return 0.0
    z = 1.959963984540054
    p = wins / count
    return (p + z * z / (2 * count) - z * math.sqrt((p * (1 - p) + z * z / (4 * count)) / count)) / (1 + z * z / count)


def nonoverlapping(events: pd.DataFrame) -> pd.DataFrame:
    # One position per symbol; new entries cannot overlap an open one.
    if events.empty:
        return events
    chosen = []
    ordered = events.sort_values(["symbol", "entry_date", "trade_date"])
    for _, group in ordered.groupby("symbol", sort=False):
        last_exit = np.iinfo("int64").min
        entries = group["entry_date"].to_numpy(dtype="datetime64[ns]").astype("int64")
        exits = group["exit_date"].to_numpy(dtype="datetime64[ns]").astype("int64")
        indices = group.index.to_numpy()
        for index, entry, exit_at in zip(indices, entries, exits):
            if entry > last_exit:
                chosen.append(index)
                last_exit = exit_at
    return events.loc[chosen].copy()


def metrics(events: pd.DataFrame) -> dict:
    events = nonoverlapping(events)
    n = len(events)
    wins = int(events["win"].sum()) if n else 0
    daily = events.groupby("entry_date")["win"].mean() if n else pd.Series(dtype=float)
    return {
        "trades": n, "wins": wins,
        "win_rate": round(wins / n, 4) if n else 0,
        "wilson95_lower": round(wilson(wins, n), 4),
        "mean_net_return": round(float(events["net_return"].mean()), 5) if n else 0,
        "median_net_return": round(float(events["net_return"].median()), 5) if n else 0,
        "entry_dates": int(events["entry_date"].nunique()),
        "symbols": int(events["symbol"].nunique()),
        "blocked_exits": int(events["blocked_exit"].sum()),
        "mean_daily_win_rate": round(float(daily.mean()), 4) if n else 0,
        "top_symbol_share": round(float(events["symbol"].value_counts(normalize=True).iloc[0]), 4) if n else 0,
    }


def rule_mask(events: pd.DataFrame, rule: tuple[Predicate, ...]) -> np.ndarray:
    mask = np.ones(len(events), dtype=bool)
    for predicate in rule:
        mask &= predicate.mask(events)
    return mask


def select_rule(train: pd.DataFrame, validation: pd.DataFrame, local: bool) -> tuple[tuple[Predicate, ...] | None, list]:
    candidates = rule_candidates()
    train_min, val_min = (15, 8) if local else (80, 40)
    ranked = []
    for rule in candidates:
        chosen = train[rule_mask(train, rule)]
        if len(chosen) < train_min:
            continue
        m = metrics(chosen)
        if m["trades"] < train_min or m["entry_dates"] < (8 if local else 30):
            continue
        if m["mean_net_return"] <= 0:
            continue
        ranked.append((m["wilson95_lower"], m["win_rate"], m["trades"], rule, m))
    ranked.sort(reverse=True, key=lambda item: item[:3])
    evaluated = []
    for _, _, _, rule, train_metrics in ranked[:30]:
        val_metrics = metrics(validation[rule_mask(validation, rule)])
        evaluated.append((rule, train_metrics, val_metrics))
    eligible = [item for item in evaluated if item[2]["trades"] >= val_min
                and item[2]["entry_dates"] >= (5 if local else 20)
                and item[2]["symbols"] >= (2 if local else 10)
                and item[2]["mean_net_return"] > 0]
    eligible.sort(reverse=True, key=lambda item: (
        item[2]["wilson95_lower"], item[2]["win_rate"], item[2]["trades"]
    ))
    return (eligible[0][0] if eligible else None), evaluated


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", help="isolated daily OHLCV CSV; omit for local development DB")
    parser.add_argument("--output", help="write JSON results to this path")
    args = parser.parse_args()
    local = not bool(args.csv)
    quotes = load_quotes(args.csv)
    events = build_events(calculate_factors(quotes))
    train_end, val_end = (LOCAL_TRAIN_END, LOCAL_VALID_END) if local else (TRAIN_END, VALID_END)
    # Purge trades crossing split boundaries. Otherwise the outcome for a
    # December signal could be observed in the following selection window.
    train = events[events["exit_date"] <= train_end]
    validation = events[(events["trade_date"] > train_end) & (events["exit_date"] <= val_end)]
    test = events[events["trade_date"] > val_end]
    chosen, evaluated = select_rule(train, validation, local)
    summary = {
        "source": "local_db_7_stocks_exploratory" if local else args.csv,
        "quotes": len(quotes), "quote_symbols": quotes["symbol"].nunique(),
        "quote_start": str(quotes["trade_date"].min().date()),
        "quote_end": str(quotes["trade_date"].max().date()),
        "cost_round_trip": COST,
        "execution": "T close signal -> T+1 open buy -> T+2 open sell",
        "split": {"train_end": train_end, "validation_end": val_end},
        "baseline": {"train": metrics(train), "validation": metrics(validation), "test": metrics(test)},
        "candidate_count": len(rule_candidates()),
        "train_top30_validated": len(evaluated),
        "selected_conditions": [asdict(p) for p in chosen] if chosen else None,
        "selected": {
            "train": metrics(train[rule_mask(train, chosen)]),
            "validation": metrics(validation[rule_mask(validation, chosen)]),
            "test": metrics(test[rule_mask(test, chosen)]),
        } if chosen else None,
    }
    if chosen and not local:
        m = summary["selected"]["test"]
        selected_test_events = nonoverlapping(test[rule_mask(test, chosen)])
        daily_test = selected_test_events.groupby("entry_date").agg(
            trades=("win", "size"), wins=("win", "sum"),
            mean_net_return=("net_return", "mean"),
        )
        if not daily_test.empty:
            daily_test["win_rate"] = daily_test["wins"] / daily_test["trades"]
            ordered = daily_test.sort_values("mean_net_return")
            summary["test_day_distribution"] = {
                "worst": [{"date": str(idx.date()), "trades": int(row.trades),
                           "win_rate": round(float(row.win_rate), 4),
                           "mean_net_return": round(float(row.mean_net_return), 5)}
                          for idx, row in ordered.head(3).iterrows()],
                "best": [{"date": str(idx.date()), "trades": int(row.trades),
                          "win_rate": round(float(row.win_rate), 4),
                          "mean_net_return": round(float(row.mean_net_return), 5)}
                         for idx, row in ordered.tail(3).iloc[::-1].iterrows()],
                "mean_daily_net_return": round(float(daily_test["mean_net_return"].mean()), 5),
            }
            rng = np.random.default_rng(20260930)
            values = daily_test["mean_net_return"].to_numpy()
            sampled = rng.choice(values, size=(10000, len(values)), replace=True).mean(axis=1)
            summary["test_day_distribution"]["daily_mean_bootstrap95"] = [
                round(float(np.quantile(sampled, .025)), 5),
                round(float(np.quantile(sampled, .975)), 5),
            ]
        summary["predeclared_production_gate"] = {
            "passed": bool(m["trades"] >= 100 and m["entry_dates"] >= 30
                           and m["symbols"] >= 20 and m["win_rate"] >= .60
                           and m["wilson95_lower"] > .50 and m["mean_net_return"] > 0
                           and m["blocked_exits"] == 0),
            "requirements": "test >=100 trades, >=30 independent entry dates, >=20 symbols, >=60% net win rate, Wilson95 lower >50%, positive mean net return, no blocked exit",
        }
    rendered = json.dumps(summary, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(rendered + "\n")
    print(rendered)


if __name__ == "__main__":
    main()
