from datetime import date, datetime
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import Callable, Protocol

import requests
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from risk_gateway.aktools import AkToolsClient, AkToolsSchemaError, AkToolsUnavailable
from risk_gateway.config import Settings
from risk_gateway.datasets import DatasetContext
from risk_gateway.datasets.breadth import BreadthDataset
from risk_gateway.datasets.cross_market import CrossMarketDataset
from risk_gateway.datasets.etf import EtfRedemptionDataset
from risk_gateway.datasets.market_daily import MarketDailyDataset
from risk_gateway.datasets.membership import MembershipDataset
from risk_gateway.datasets.valuation import ValuationDataset
from risk_gateway.models import InvalidQuery, RiskQuery
from risk_gateway.pagination import InvalidCursor, paginate
from risk_gateway.series import InvalidSeries, normalize_market_frame
from risk_gateway.time_policy import SHANGHAI


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
    client: AkToolsClient | None = None,
    calendar_provider: Callable[[RiskQuery], tuple[date, ...]] | None = None,
) -> FastAPI:
    application = FastAPI(title="A-share risk derived data gateway")
    probe = health_probe or DefaultHealthProbe(settings)
    source_client = client or AkToolsClient(settings)
    provide_calendar = calendar_provider or (lambda query: _calendar(source_client, query))

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

    async def invalid_request(_request: Request, exception: Exception) -> JSONResponse:
        return JSONResponse(
            status_code=400,
            content={"error": "invalid_query", "message": str(exception)},
        )

    async def unavailable(_request: Request, exception: Exception) -> JSONResponse:
        return JSONResponse(
            status_code=503,
            content={"error": "upstream_unavailable", "message": str(exception)},
        )

    async def schema_error(_request: Request, exception: Exception) -> JSONResponse:
        return JSONResponse(
            status_code=502,
            content={"error": "upstream_schema_error", "message": str(exception)},
        )

    for exception_type in (InvalidQuery, InvalidCursor):
        application.add_exception_handler(exception_type, invalid_request)
    application.add_exception_handler(AkToolsUnavailable, unavailable)
    for exception_type in (AkToolsSchemaError, InvalidSeries):
        application.add_exception_handler(exception_type, schema_error)

    dataset_types = {
        "market-daily": MarketDailyDataset,
        "valuation": ValuationDataset,
        "breadth": BreadthDataset,
        "cross-market": CrossMarketDataset,
        "sw1-membership": MembershipDataset,
        "etf-redemption": EtfRedemptionDataset,
    }

    def data_response(
        dataset_code: str,
        start_date: str,
        end_date: str,
        objects: str,
        cursor: str | None,
    ) -> JSONResponse:
        query = RiskQuery.from_raw(start_date, end_date, objects, cursor)
        context = DatasetContext(
            client=source_client,
            fetched_at=datetime.now(SHANGHAI),
            a_share_sessions=provide_calendar(query),
            source_version="AKTools/AKShare",
        )
        dataset_type = dataset_types[dataset_code]
        if dataset_code == "breadth":
            dataset = dataset_type(context, max_concurrency=settings.max_concurrency)
        else:
            dataset = dataset_type(context)
        result = dataset.fetch(query)
        page, next_cursor = paginate(
            result.data, query.request_hash(dataset_code), query.cursor, settings.page_size,
        )
        result.meta.next_cursor = next_cursor
        result.data = page
        return JSONResponse(content=result.payload())

    for dataset_code in dataset_types:
        def route(
            start_date: str,
            end_date: str,
            objects: str,
            cursor: str | None = None,
            _dataset_code: str = dataset_code,
        ) -> JSONResponse:
            return data_response(_dataset_code, start_date, end_date, objects, cursor)

        application.add_api_route(
            f"/api/risk/{dataset_code}", route, methods=["GET"],
            name=f"risk-{dataset_code}",
        )

    return application


def _calendar(client: AkToolsClient, query: RiskQuery) -> tuple[date, ...]:
    del query
    rows = client.get("stock_zh_index_daily", {"symbol": "sh000300"})
    frame = normalize_market_frame(rows)
    return tuple(frame["date"])


app = create_app(Settings.from_env())
