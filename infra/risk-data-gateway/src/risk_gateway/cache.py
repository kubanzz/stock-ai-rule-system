from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
from tempfile import NamedTemporaryFile
from threading import Lock
from typing import Any, Iterator
from uuid import uuid4

import pandas as pd

from risk_gateway.models import GatewayResponse


class InvalidCachePartition(ValueError):
    pass


@dataclass
class _LockEntry:
    lock: Any
    references: int


class KeyedLockPool:
    """Coalesce identical in-process cache misses without leaking request keys."""

    def __init__(self):
        self._guard = Lock()
        self._entries: dict[str, _LockEntry] = {}

    @contextmanager
    def acquire(self, key: str) -> Iterator[None]:
        with self._guard:
            entry = self._entries.get(key)
            if entry is None:
                entry = _LockEntry(Lock(), 0)
                self._entries[key] = entry
            entry.references += 1
        entry.lock.acquire()
        try:
            yield
        finally:
            entry.lock.release()
            with self._guard:
                entry.references -= 1
                if entry.references == 0:
                    self._entries.pop(key, None)


_DATASET = re.compile(r"^[a-z][a-z0-9_]*$")
_OBJECT = re.compile(r"^[A-Za-z0-9_.:-]+$")
_REQUEST_HASH = re.compile(r"^[a-f0-9]{64}$")


@dataclass(frozen=True)
class CachePartition:
    dataset: str
    object_key: str
    year: int

    def __post_init__(self) -> None:
        if not _DATASET.fullmatch(self.dataset or ""):
            raise InvalidCachePartition("cache dataset is invalid")
        if not _OBJECT.fullmatch(self.object_key or ""):
            raise InvalidCachePartition("cache object key is invalid")
        if self.year < 2000 or self.year > 2100:
            raise InvalidCachePartition("cache year is invalid")


@dataclass(frozen=True)
class CacheEntry:
    frame: pd.DataFrame
    manifest: dict[str, Any]


class ParquetCache:
    def __init__(self, root: Path | str):
        self._root = Path(root)

    def paths(self, partition: CachePartition) -> tuple[Path, Path]:
        directory = (
            self._root
            / partition.dataset
            / partition.object_key
            / str(partition.year)
        )
        return directory / "part.parquet", directory / "manifest.json"

    def put(
        self,
        partition: CachePartition,
        frame: pd.DataFrame,
        *,
        source: str,
        fetched_at: datetime,
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        if not source.strip():
            raise ValueError("cache source must not be blank")
        data_path, manifest_path = self.paths(partition)
        data_path.parent.mkdir(parents=True, exist_ok=True)
        temp_data: Path | None = None
        temp_manifest: Path | None = None
        try:
            with NamedTemporaryFile(
                dir=data_path.parent, prefix="part-", suffix=".parquet", delete=False
            ) as file_handle:
                temp_data = Path(file_handle.name)
            frame.to_parquet(temp_data, index=False)
            verified = pd.read_parquet(temp_data)
            if len(verified) != len(frame) or list(verified.columns) != list(frame.columns):
                raise OSError("cache parquet verification failed")
            content_hash = _sha256(temp_data)
            manifest = {
                "dataset": partition.dataset,
                "objectKey": partition.object_key,
                "year": partition.year,
                "rowCount": len(frame),
                "columns": list(frame.columns),
                "sha256": content_hash,
                "source": source.strip(),
                "fetchedAt": fetched_at.isoformat(),
                "metadata": metadata or {},
            }
            with NamedTemporaryFile(
                dir=data_path.parent, prefix="manifest-", suffix=".json", delete=False
            ) as file_handle:
                temp_manifest = Path(file_handle.name)
                file_handle.write(
                    json.dumps(
                        manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")
                    ).encode("utf-8")
                )
                file_handle.flush()
                os.fsync(file_handle.fileno())
            os.replace(temp_data, data_path)
            temp_data = None
            os.replace(temp_manifest, manifest_path)
            temp_manifest = None
            return manifest
        finally:
            for temporary in (temp_data, temp_manifest):
                if temporary is not None:
                    temporary.unlink(missing_ok=True)

    def get(self, partition: CachePartition) -> CacheEntry | None:
        data_path, manifest_path = self.paths(partition)
        if not data_path.exists() or not manifest_path.exists():
            return None
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            if manifest.get("sha256") != _sha256(data_path):
                raise OSError("cache content hash mismatch")
            frame = pd.read_parquet(data_path)
            if manifest.get("rowCount") != len(frame):
                raise OSError("cache row count mismatch")
            if manifest.get("columns") != list(frame.columns):
                raise OSError("cache schema mismatch")
            return CacheEntry(frame=frame, manifest=manifest)
        except (OSError, ValueError, json.JSONDecodeError):
            self._quarantine(data_path, manifest_path)
            return None

    def _quarantine(self, *paths: Path) -> None:
        quarantine = self._root / "quarantine"
        quarantine.mkdir(parents=True, exist_ok=True)
        token = uuid4().hex
        for path in paths:
            if path.exists():
                os.replace(path, quarantine / f"{token}-{path.name}")


class DerivedResponseCache:
    """短期复用昂贵衍生结果；原始 Parquet 仍是长期可审计事实缓存。"""

    def __init__(self, root: Path | str):
        self._root = Path(root)

    def path(self, dataset: str, request_hash: str) -> Path:
        self._validate_key(dataset, request_hash)
        return self._root / "derived_response" / dataset / f"{request_hash}.json"

    def put(
        self,
        dataset: str,
        request_hash: str,
        result: GatewayResponse,
        *,
        stored_at: datetime,
        ttl: timedelta,
    ) -> None:
        if stored_at.tzinfo is None or ttl.total_seconds() <= 0:
            raise ValueError("derived cache requires aware stored_at and positive ttl")
        target = self.path(dataset, request_hash)
        target.parent.mkdir(parents=True, exist_ok=True)
        payload = result.payload()
        payload_bytes = _canonical_json(payload)
        envelope = {
            "dataset": dataset,
            "requestHash": request_hash,
            "storedAt": stored_at.isoformat(),
            "expiresAt": (stored_at + ttl).isoformat(),
            "sha256": hashlib.sha256(payload_bytes).hexdigest(),
            "payload": payload,
        }
        temporary: Path | None = None
        try:
            with NamedTemporaryFile(
                dir=target.parent, prefix="response-", suffix=".json", delete=False
            ) as file_handle:
                temporary = Path(file_handle.name)
                file_handle.write(_canonical_json(envelope))
                file_handle.flush()
                os.fsync(file_handle.fileno())
            os.replace(temporary, target)
            temporary = None
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)

    def get(
        self,
        dataset: str,
        request_hash: str,
        *,
        now: datetime,
    ) -> GatewayResponse | None:
        if now.tzinfo is None:
            raise ValueError("derived cache requires aware now")
        target = self.path(dataset, request_hash)
        if not target.exists():
            return None
        try:
            envelope = json.loads(target.read_text(encoding="utf-8"))
            if envelope.get("dataset") != dataset or envelope.get("requestHash") != request_hash:
                raise ValueError("derived cache key mismatch")
            expires_at = datetime.fromisoformat(str(envelope["expiresAt"]))
            if expires_at.tzinfo is None:
                raise ValueError("derived cache expiry must include timezone")
            payload = envelope["payload"]
            if envelope.get("sha256") != hashlib.sha256(_canonical_json(payload)).hexdigest():
                raise OSError("derived cache content hash mismatch")
            if now >= expires_at:
                return None
            return GatewayResponse.model_validate(payload)
        except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError):
            self._quarantine(target)
            return None

    def _validate_key(self, dataset: str, request_hash: str) -> None:
        if not _DATASET.fullmatch(dataset or "") or not _REQUEST_HASH.fullmatch(request_hash or ""):
            raise InvalidCachePartition("derived cache key is invalid")

    def _quarantine(self, target: Path) -> None:
        quarantine = self._root / "quarantine"
        quarantine.mkdir(parents=True, exist_ok=True)
        if target.exists():
            os.replace(target, quarantine / f"{uuid4().hex}-{target.name}")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file_handle:
        for chunk in iter(lambda: file_handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
