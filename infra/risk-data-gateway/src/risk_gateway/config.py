import os
from pathlib import Path

from pydantic import BaseModel, Field, field_validator


class Settings(BaseModel):
    aktools_base_url: str = "http://aktools:8090"
    cache_dir: Path = Path("/cache")
    timeout_seconds: float = Field(default=30.0, gt=0, le=300)
    max_retries: int = Field(default=3, ge=0, le=5)
    max_concurrency: int = Field(default=4, ge=1, le=16)
    page_size: int = Field(default=2000, ge=1, le=5000)
    derived_cache_ttl_seconds: int = Field(default=3600, ge=60, le=86400)

    @field_validator("aktools_base_url")
    @classmethod
    def validate_base_url(cls, value: str) -> str:
        normalized = value.strip().rstrip("/")
        if not normalized.startswith(("http://", "https://")):
            raise ValueError("aktools_base_url must be HTTP(S)")
        return normalized

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            aktools_base_url=os.getenv(
                "RISK_GATEWAY_AKTOOLS_BASE_URL", "http://aktools:8090"
            ),
            cache_dir=Path(os.getenv("RISK_GATEWAY_CACHE_DIR", "/cache")),
            timeout_seconds=float(
                os.getenv("RISK_GATEWAY_TIMEOUT_SECONDS", "30")
            ),
            max_retries=int(os.getenv("RISK_GATEWAY_MAX_RETRIES", "3")),
            max_concurrency=int(
                os.getenv("RISK_GATEWAY_MAX_CONCURRENCY", "4")
            ),
            page_size=int(os.getenv("RISK_GATEWAY_PAGE_SIZE", "2000")),
            derived_cache_ttl_seconds=int(
                os.getenv("RISK_GATEWAY_DERIVED_CACHE_TTL_SECONDS", "3600")
            ),
        )
