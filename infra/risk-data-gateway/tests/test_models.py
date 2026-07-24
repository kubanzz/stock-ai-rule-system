from datetime import date

import pytest

from risk_gateway.models import InvalidQuery, RiskQuery


def test_query_normalizes_and_sorts_objects():
    query = RiskQuery.from_raw(
        "20210719",
        "20260717",
        "stock:600519.SH,market:CN-A,sector:SW1:801010",
    )

    assert query.start_date == date(2021, 7, 19)
    assert query.end_date == date(2026, 7, 17)
    assert query.object_keys == (
        "market:CN-A",
        "sector:SW1:801010",
        "stock:600519.SH",
    )


@pytest.mark.parametrize(
    "objects",
    (
        "",
        "market:OTHER",
        "sector:801010",
        "sector:SW1:80101",
        "stock:600519",
        "stock:600519.SH,stock:600519.SH",
    ),
)
def test_query_rejects_invalid_or_duplicate_objects(objects):
    with pytest.raises(InvalidQuery):
        RiskQuery.from_raw("20210719", "20260717", objects)


def test_query_rejects_inverted_or_excessive_date_range():
    with pytest.raises(InvalidQuery, match="start_date"):
        RiskQuery.from_raw("20260718", "20260717", "market:CN-A")
    with pytest.raises(InvalidQuery, match="eleven years"):
        RiskQuery.from_raw("20100101", "20260717", "market:CN-A")
