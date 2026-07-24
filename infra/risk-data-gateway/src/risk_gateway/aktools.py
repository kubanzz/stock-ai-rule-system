import time
from typing import Any, Callable

import requests

from risk_gateway.config import Settings


class AkToolsError(RuntimeError):
    pass


class AkToolsUnavailable(AkToolsError):
    pass


class AkToolsSchemaError(AkToolsError):
    pass


class AkToolsClient:
    _RETRYABLE_STATUSES = {429, 502, 503, 504}

    def __init__(
        self,
        settings: Settings,
        session: requests.Session | None = None,
        sleeper: Callable[[float], None] = time.sleep,
    ):
        self._settings = settings
        self._session = session or requests.Session()
        self._sleeper = sleeper

    def get(self, function: str, params: dict[str, Any]) -> list[dict[str, Any]]:
        normalized_function = function.strip()
        if not normalized_function or "/" in normalized_function:
            raise ValueError("AKTools function name is invalid")
        url = f"{self._settings.aktools_base_url}/api/public/{normalized_function}"
        last_error: Exception | None = None
        for attempt in range(self._settings.max_retries + 1):
            try:
                response = self._session.get(
                    url,
                    params=dict(sorted((params or {}).items())),
                    timeout=self._settings.timeout_seconds,
                )
                if response.status_code == 200:
                    return self._rows(normalized_function, response)
                if response.status_code not in self._RETRYABLE_STATUSES:
                    raise AkToolsUnavailable(
                        f"AKTools request failed: function={normalized_function} "
                        f"status={response.status_code}"
                    )
                last_error = AkToolsUnavailable(
                    f"AKTools temporary failure: function={normalized_function} "
                    f"status={response.status_code}"
                )
            except requests.RequestException as exception:
                last_error = AkToolsUnavailable(
                    f"AKTools connection failed: function={normalized_function} "
                    f"error={type(exception).__name__}"
                )
            if attempt < self._settings.max_retries:
                self._sleeper(0.5 * (2**attempt))
        if last_error is None:
            raise AkToolsUnavailable(
                f"AKTools request failed: function={normalized_function}"
            )
        raise last_error

    def _rows(self, function: str, response: Any) -> list[dict[str, Any]]:
        try:
            payload = response.json()
        except (TypeError, ValueError) as exception:
            raise AkToolsSchemaError(
                f"AKTools response is not JSON: function={function}"
            ) from exception
        if not isinstance(payload, list):
            raise AkToolsSchemaError(
                f"AKTools response must be an array: function={function}"
            )
        if any(not isinstance(row, dict) for row in payload):
            raise AkToolsSchemaError(
                f"AKTools response array must contain objects: function={function}"
            )
        return payload
