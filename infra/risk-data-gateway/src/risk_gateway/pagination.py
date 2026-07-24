import base64
import json
from typing import Any, Sequence


class InvalidCursor(ValueError):
    pass


def encode_cursor(request_hash: str, offset: int) -> str:
    if not request_hash or offset < 0:
        raise InvalidCursor("cursor payload is invalid")
    payload = json.dumps(
        {"requestHash": request_hash, "offset": offset, "version": 1},
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")


def decode_cursor(cursor: str | None, expected_request_hash: str) -> int:
    if not cursor:
        raise InvalidCursor("cursor must not be empty")
    try:
        padded = cursor + "=" * (-len(cursor) % 4)
        payload = json.loads(
            base64.b64decode(padded, altchars=b"-_", validate=True).decode("utf-8")
        )
        if payload.get("version") != 1:
            raise InvalidCursor("cursor version is unsupported")
        if payload.get("requestHash") != expected_request_hash:
            raise InvalidCursor("cursor belongs to another request")
        offset = payload.get("offset")
        if not isinstance(offset, int) or isinstance(offset, bool) or offset < 0:
            raise InvalidCursor("cursor offset is invalid")
        return offset
    except InvalidCursor:
        raise
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise InvalidCursor("cursor is malformed") from exception


def paginate(
    rows: Sequence[dict[str, Any]],
    request_hash: str,
    cursor: str | None,
    page_size: int,
) -> tuple[list[dict[str, Any]], str | None]:
    if page_size < 1:
        raise ValueError("page_size must be positive")
    offset = 0 if cursor is None else decode_cursor(cursor, request_hash)
    if offset > len(rows):
        raise InvalidCursor("cursor offset exceeds result size")
    page = list(rows[offset : offset + page_size])
    next_offset = offset + len(page)
    next_cursor = (
        encode_cursor(request_hash, next_offset) if next_offset < len(rows) else None
    )
    return page, next_cursor
