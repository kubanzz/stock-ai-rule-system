import pytest

from risk_gateway.aktools import (
    AkToolsClient,
    AkToolsSchemaError,
    AkToolsUnavailable,
)
from risk_gateway.config import Settings


class StubResponse:
    def __init__(self, status_code, payload=None):
        self.status_code = status_code
        self._payload = payload

    def json(self):
        return self._payload


class StubSession:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def get(self, url, *, params, timeout):
        self.calls.append((url, params, timeout))
        return self.responses.pop(0)


def settings(tmp_path, max_retries=2):
    return Settings(
        aktools_base_url="http://aktools:8090",
        cache_dir=tmp_path,
        max_retries=max_retries,
    )


def test_client_retries_503_then_returns_array(tmp_path):
    session = StubSession(
        [StubResponse(503), StubResponse(200, [{"日期": "2026-07-17"}])]
    )
    delays = []

    rows = AkToolsClient(
        settings(tmp_path), session=session, sleeper=delays.append
    ).get("stock_zh_a_hist", {"symbol": "600519"})

    assert rows == [{"日期": "2026-07-17"}]
    assert len(session.calls) == 2
    assert delays == [0.5]


def test_client_rejects_non_array_success(tmp_path):
    session = StubSession([StubResponse(200, {"data": []})])

    with pytest.raises(AkToolsSchemaError, match="array"):
        AkToolsClient(settings(tmp_path), session=session).get("broken", {})


def test_client_does_not_retry_permanent_http_error(tmp_path):
    session = StubSession([StubResponse(400)])

    with pytest.raises(AkToolsUnavailable, match="status=400"):
        AkToolsClient(settings(tmp_path), session=session).get("broken", {})

    assert len(session.calls) == 1
