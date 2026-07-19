from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
from tempfile import NamedTemporaryFile
from typing import Any
from uuid import uuid4

import pandas as pd


class InvalidCachePartition(ValueError):
    pass


_DATASET = re.compile(r"^[a-z][a-z0-9_]*$")
_OBJECT = re.compile(r"^[A-Za-z0-9_.:-]+$")


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


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file_handle:
        for chunk in iter(lambda: file_handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
