import pytest

from risk_gateway.pagination import (
    InvalidCursor,
    decode_cursor,
    encode_cursor,
    paginate,
)


def test_cursor_rejects_other_request_hash():
    cursor = encode_cursor("hash-a", 50)

    with pytest.raises(InvalidCursor, match="request"):
        decode_cursor(cursor, "hash-b")


def test_paginate_returns_stable_next_cursor():
    rows = [{"id": value} for value in range(5)]

    first, cursor = paginate(rows, "request-hash", None, page_size=2)
    second, next_cursor = paginate(
        rows, "request-hash", cursor, page_size=2
    )

    assert first == [{"id": 0}, {"id": 1}]
    assert second == [{"id": 2}, {"id": 3}]
    assert decode_cursor(next_cursor, "request-hash") == 4


@pytest.mark.parametrize("cursor", ("", "not-base64", "e30="))
def test_cursor_rejects_malformed_values(cursor):
    with pytest.raises(InvalidCursor):
        decode_cursor(cursor, "request-hash")
