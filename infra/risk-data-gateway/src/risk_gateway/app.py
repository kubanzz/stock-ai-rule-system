from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import Protocol

import requests
from fastapi import FastAPI
from fastapi.responses import JSONResponse

from risk_gateway.config import Settings


class HealthProbe(Protocol):
    def aktools_status(self) -> str: ...

    def cache_status(self) -> str: ...


class DefaultHealthProbe:
    def __init__(self, settings: Settings):
        self._settings = settings

    def aktools_status(self) -> str:
        try:
            response = requests.get(
                f"{self._settings.aktools_base_url}/version",
                timeout=min(self._settings.timeout_seconds, 5),
            )
            return "up" if response.ok else "down"
        except requests.RequestException:
            return "down"

    def cache_status(self) -> str:
        cache_dir: Path = self._settings.cache_dir
        try:
            cache_dir.mkdir(parents=True, exist_ok=True)
            with NamedTemporaryFile(dir=cache_dir, prefix="health-", delete=True):
                return "up"
        except OSError:
            return "down"


def create_app(
    settings: Settings,
    health_probe: HealthProbe | None = None,
) -> FastAPI:
    application = FastAPI(title="A-share risk derived data gateway")
    probe = health_probe or DefaultHealthProbe(settings)

    @application.get("/health")
    def health() -> JSONResponse:
        aktools = probe.aktools_status()
        cache = probe.cache_status()
        status = "up" if aktools == "up" and cache == "up" else "down"
        return JSONResponse(
            status_code=200 if status == "up" else 503,
            content={
                "status": status,
                "version": "1",
                "aktools": aktools,
                "cache": cache,
            },
        )

    return application


app = create_app(Settings.from_env())
