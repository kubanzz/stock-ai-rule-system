from datetime import date, datetime
import re
from typing import Iterable, Mapping, Sequence

import pandas as pd


class InvalidSeries(ValueError):
    pass


class AmbiguousRevision(InvalidSeries):
    pass


_OBJECT_KEY = re.compile(
    r"^(?:(market):(cn-a)|(sector):(sw1):(\d{6})|(stock):(\d{6})(?:\.(sh|sz|bj))?)$",
    re.IGNORECASE,
)
_PLAIN_STOCK = re.compile(r"^(\d{6})(?:\.(SH|SZ|BJ))?$", re.IGNORECASE)
_COLUMN_ALIASES = {
    "日期": "date",
    "date": "date",
    "开盘": "open",
    "open": "open",
    "收盘": "close",
    "close": "close",
    "成交量": "volume",
    "volume": "volume",
    "成交额": "amount",
    "amount": "amount",
}
_MARKET_COLUMNS = ["date", "open", "close", "volume", "amount"]


def normalize_object_key(raw: str) -> str:
    value = (raw or "").strip()
    plain = _PLAIN_STOCK.fullmatch(value)
    if plain:
        symbol, suffix = plain.groups()
        return f"stock:{symbol}.{(suffix or _infer_exchange(symbol)).upper()}"

    matched = _OBJECT_KEY.fullmatch(value)
    if not matched:
        raise ValueError(f"unsupported risk object: {raw}")
    lowered = value.lower()
    if lowered == "market:cn-a":
        return "market:CN-A"
    if lowered.startswith("sector:sw1:"):
        return f"sector:SW1:{value[-6:]}"
    symbol_match = re.fullmatch(r"stock:(\d{6})(?:\.(SH|SZ|BJ))?", value, re.IGNORECASE)
    if symbol_match:
        symbol, suffix = symbol_match.groups()
        return f"stock:{symbol}.{(suffix or _infer_exchange(symbol)).upper()}"
    raise ValueError(f"unsupported risk object: {raw}")


def akshare_stock_symbol(raw: str) -> str:
    normalized = normalize_object_key(raw)
    symbol, exchange = normalized.split(":", 1)[1].split(".", 1)
    return f"{exchange.lower()}{symbol}"


def normalize_market_frame(
    rows: Sequence[Mapping[str, object]] | pd.DataFrame,
    *,
    expected_dates: Iterable[date] | None = None,
    require_positive_open: bool = True,
) -> pd.DataFrame:
    frame = rows.copy() if isinstance(rows, pd.DataFrame) else pd.DataFrame(rows)
    if frame.empty:
        return pd.DataFrame(columns=_MARKET_COLUMNS)

    renamed = frame.rename(columns={column: _COLUMN_ALIASES.get(str(column), str(column)) for column in frame.columns})
    missing = {"date", "open", "close", "volume"}.difference(renamed.columns)
    if missing:
        raise InvalidSeries(f"missing required market columns: {sorted(missing)}")
    if "amount" not in renamed.columns:
        renamed["amount"] = pd.NA

    normalized = renamed[_MARKET_COLUMNS].copy()
    try:
        normalized["date"] = pd.to_datetime(normalized["date"], errors="raise").dt.date
        for column in ("open", "close", "volume", "amount"):
            normalized[column] = pd.to_numeric(normalized[column], errors="raise")
    except (TypeError, ValueError) as exception:
        raise InvalidSeries("market series contains an invalid date or numeric value") from exception

    exact = normalized.drop_duplicates()
    conflicting = exact[exact.duplicated(subset=["date"], keep=False)]
    if not conflicting.empty:
        dates = ", ".join(sorted({value.isoformat() for value in conflicting["date"]}))
        raise AmbiguousRevision(f"conflicting rows for trade date: {dates}")
    normalized = exact.sort_values("date").reset_index(drop=True)

    price_columns = ["open", "close"] if require_positive_open else ["close"]
    if normalized[price_columns].isna().any().any() or (normalized[price_columns] <= 0).any().any():
        raise InvalidSeries("market prices must be positive")
    if normalized["volume"].isna().any() or (normalized["volume"] < 0).any():
        raise InvalidSeries("market volume must not be negative")
    if normalized["amount"].notna().any() and (normalized["amount"].dropna() < 0).any():
        raise InvalidSeries("market amount must not be negative")

    if expected_dates is not None:
        expected = set(expected_dates)
        actual = set(normalized["date"])
        missing_dates = sorted(expected.difference(actual))
        if missing_dates:
            sample = ", ".join(value.isoformat() for value in missing_dates[:5])
            raise InvalidSeries(f"missing expected trade dates: {sample}")
    return normalized


def align_available_series(
    left: pd.DataFrame,
    right: pd.DataFrame,
    *,
    as_of: str | datetime,
) -> pd.DataFrame:
    cutoff = _utc_timestamp(as_of)
    left_ready = _available_before(left, cutoff, "left")
    right_ready = _available_before(right, cutoff, "right")
    return left_ready.merge(
        right_ready,
        on="date",
        how="inner",
        suffixes=("_left", "_right"),
        validate="one_to_one",
    ).sort_values("date").reset_index(drop=True)


def _available_before(frame: pd.DataFrame, cutoff: pd.Timestamp, side: str) -> pd.DataFrame:
    required = {"date", "availableAt"}
    if not required.issubset(frame.columns):
        raise InvalidSeries(f"{side} series must contain date and availableAt")
    if frame["date"].duplicated().any():
        raise AmbiguousRevision(f"{side} series contains duplicate trade dates")
    ready = frame.copy()
    try:
        ready["availableAt"] = pd.to_datetime(ready["availableAt"], utc=True, errors="raise")
    except (TypeError, ValueError) as exception:
        raise InvalidSeries(f"{side} series contains invalid availableAt") from exception
    return ready.loc[ready["availableAt"] <= cutoff]


def _utc_timestamp(raw: str | datetime) -> pd.Timestamp:
    parsed = pd.Timestamp(raw)
    if parsed.tzinfo is None:
        raise InvalidSeries("as_of must be timezone-aware")
    return parsed.tz_convert("UTC")


def _infer_exchange(symbol: str) -> str:
    if symbol.startswith(("4", "8")):
        return "BJ"
    if symbol.startswith("6"):
        return "SH"
    if symbol.startswith(("0", "3")):
        return "SZ"
    raise ValueError(f"cannot infer exchange for stock symbol: {symbol}")
