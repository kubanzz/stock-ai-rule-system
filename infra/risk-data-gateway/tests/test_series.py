from datetime import date

import pandas as pd
import pytest

from risk_gateway.series import (
    AmbiguousRevision,
    InvalidSeries,
    align_available_series,
    normalize_market_frame,
    normalize_object_key,
)


def test_object_key_normalizes_supported_market_sector_and_stock_codes():
    assert normalize_object_key("market:cn-a") == "market:CN-A"
    assert normalize_object_key("sector:sw1:801010") == "sector:SW1:801010"
    assert normalize_object_key("stock:600519.sh") == "stock:600519.SH"
    assert normalize_object_key("000001") == "stock:000001.SZ"
    assert normalize_object_key("430047") == "stock:430047.BJ"


def test_market_frame_converts_chinese_columns_and_sorts_dates():
    frame = normalize_market_frame(
        [
            {"日期": "2026-07-20", "开盘": "1501.2", "收盘": "1510.1", "成交量": "20", "成交额": "30202"},
            {"日期": "2026-07-17", "开盘": "1490", "收盘": "1500", "成交量": "10", "成交额": "15000"},
        ]
    )

    assert list(frame.columns) == ["date", "open", "close", "volume", "amount"]
    assert frame.to_dict("records") == [
        {"date": date(2026, 7, 17), "open": 1490.0, "close": 1500.0, "volume": 10.0, "amount": 15000.0},
        {"date": date(2026, 7, 20), "open": 1501.2, "close": 1510.1, "volume": 20.0, "amount": 30202.0},
    ]


def test_market_frame_deduplicates_identical_rows_but_rejects_revisions():
    row = {"日期": "2026-07-17", "开盘": 10, "收盘": 11, "成交量": 100, "成交额": 1000}
    assert len(normalize_market_frame([row, row])) == 1

    revised = dict(row, 收盘=12)
    with pytest.raises(AmbiguousRevision, match="2026-07-17"):
        normalize_market_frame([row, revised])


@pytest.mark.parametrize(
    "field,value,expected",
    (("开盘", 0, "positive"), ("收盘", -1, "positive"), ("成交量", -1, "negative")),
)
def test_market_frame_rejects_invalid_values(field, value, expected):
    row = {"日期": "2026-07-17", "开盘": 10, "收盘": 11, "成交量": 100, "成交额": 1000}
    row[field] = value

    with pytest.raises(InvalidSeries, match=expected):
        normalize_market_frame([row])


def test_market_frame_rejects_missing_expected_trade_date():
    rows = [
        {"日期": "2026-07-17", "开盘": 10, "收盘": 11, "成交量": 100, "成交额": 1000},
        {"日期": "2026-07-21", "开盘": 11, "收盘": 12, "成交量": 110, "成交额": 1200},
    ]

    with pytest.raises(InvalidSeries, match="missing expected trade dates"):
        normalize_market_frame(
            rows,
            expected_dates=(date(2026, 7, 17), date(2026, 7, 20), date(2026, 7, 21)),
        )


def test_series_alignment_uses_only_dates_available_on_both_sides():
    left = pd.DataFrame(
        [
            {"date": date(2026, 7, 17), "availableAt": "2026-07-17T15:30:00+08:00", "close": 10.0},
            {"date": date(2026, 7, 20), "availableAt": "2026-07-20T15:30:00+08:00", "close": 11.0},
        ]
    )
    right = pd.DataFrame(
        [
            {"date": date(2026, 7, 17), "availableAt": "2026-07-20T09:00:00+08:00", "value": 1.0},
            {"date": date(2026, 7, 20), "availableAt": "2026-07-21T09:00:00+08:00", "value": 2.0},
        ]
    )

    aligned = align_available_series(left, right, as_of="2026-07-20T16:00:00+08:00")

    assert aligned[["date", "close", "value"]].to_dict("records") == [
        {"date": date(2026, 7, 17), "close": 10.0, "value": 1.0}
    ]
