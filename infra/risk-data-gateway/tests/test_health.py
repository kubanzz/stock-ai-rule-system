import pytest
from fastapi.testclient import TestClient

from risk_gateway.app import create_app
from risk_gateway.config import Settings


pytestmark = pytest.mark.filterwarnings(
    "ignore:Using `httpx` with `starlette.testclient` is deprecated"
)


class HealthyDependencies:
    def aktools_status(self) -> str:
        return "up"

    def cache_status(self) -> str:
        return "up"


class UnhealthyAkTools(HealthyDependencies):
    def aktools_status(self) -> str:
        return "down"


def test_health_reports_version_and_dependencies(tmp_path):
    settings = Settings(
        aktools_base_url="http://aktools:8090",
        cache_dir=tmp_path,
    )
    client = TestClient(create_app(settings, health_probe=HealthyDependencies()))

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "up",
        "version": "1",
        "aktools": "up",
        "cache": "up",
    }


def test_health_is_unavailable_when_a_dependency_is_down(tmp_path):
    settings = Settings(
        aktools_base_url="http://aktools:8090",
        cache_dir=tmp_path,
    )
    client = TestClient(create_app(settings, health_probe=UnhealthyAkTools()))

    response = client.get("/health")

    assert response.status_code == 503
    assert response.json() == {
        "status": "down",
        "version": "1",
        "aktools": "down",
        "cache": "up",
    }
