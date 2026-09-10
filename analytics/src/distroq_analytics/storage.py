"""Filesystem layout, dataset IO, and the overwrite rules for a run directory.

An export is an immutable snapshot. That is enforced here rather than by convention:
a destination that already exists is an error unless ``--overwrite`` was passed, and
``--overwrite`` removes exactly the one run directory it was pointed at.
"""

from __future__ import annotations

import json
import logging
import shutil
from datetime import datetime, timezone
from pathlib import Path

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql.types import StructType

from .errors import MissingInputError, OutputExistsError

log = logging.getLogger(__name__)

FACTS_DIR = "facts"
METADATA_DIR = "metadata"
REPORTS_DIR = "reports"
QUALITY_DIR = "quality"
EXPORT_METADATA_FILE = "export_metadata.json"


def run_directory(base: Path, run_id: str) -> Path:
    return Path(base) / run_id


def prepare_directory(target: Path, overwrite: bool, *, label: str) -> Path:
    """Create ``target``, refusing to clobber an existing one without permission."""
    target = Path(target)
    if target.exists() and any(target.iterdir()):
        if not overwrite:
            raise OutputExistsError(
                f"{label} already exists at {target} and is not empty. Analytics exports are "
                "immutable snapshots, so this is refused rather than merged. Pass --overwrite "
                "to replace this directory, or choose a different --output."
            )
        log.warning("--overwrite: replacing %s at %s", label, target)
        shutil.rmtree(target)
    target.mkdir(parents=True, exist_ok=True)
    return target


def write_dataset(df: DataFrame, directory: Path, name: str) -> int:
    """Write one Parquet dataset and return its row count.

    Coalesced to a single file: these datasets are read back by the next stage and by
    operators, and a stable one-file layout keeps a rerun comparable to its predecessor
    without depending on how many partitions the JDBC reader happened to produce.
    """
    target = Path(directory) / name
    cached = df.coalesce(1)
    cached.write.mode("overwrite").parquet(str(target.resolve().as_posix()))
    return read_dataset_at(df.sparkSession, target).count()


def read_dataset_at(spark: SparkSession, path: Path) -> DataFrame:
    return spark.read.parquet(str(Path(path).resolve().as_posix()))


def read_dataset(spark: SparkSession, directory: Path, name: str,
                 schema: StructType | None = None) -> DataFrame:
    target = Path(directory) / name
    if not target.is_dir():
        raise MissingInputError(
            f"Required dataset {name!r} was not found at {target}. Run `export` for this "
            "window first, and point --input at the run directory it created."
        )
    reader = spark.read
    if schema is not None:
        reader = reader.schema(schema)
    return reader.parquet(str(target.resolve().as_posix()))


def write_json(payload: dict, path: Path) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True, default=str), encoding="utf-8")
    return path


def read_json(path: Path) -> dict:
    path = Path(path)
    if not path.is_file():
        raise MissingInputError(f"Expected metadata at {path}, which does not exist.")
    return json.loads(path.read_text(encoding="utf-8"))


def write_csv_preview(df: DataFrame, directory: Path, name: str, limit: int = 5_000) -> Path:
    """Optional human-readable diagnostic output. Never the analytics interchange format.

    Written by hand rather than through pandas so that the bytes depend only on the
    rows: a pandas round-trip would reformat floats and nulls according to whichever
    pandas version happened to be installed, and these files are compared byte for byte
    to demonstrate that a rerun is reproducible.
    """
    target = Path(directory) / f"{name}.csv"
    lines = [",".join(df.columns)]
    for row in df.limit(limit).collect():
        lines.append(",".join(_csv_cell(row[column]) for column in df.columns))
    target.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return target


def _csv_cell(value) -> str:
    if value is None:
        return ""
    text = str(value)
    if any(ch in text for ch in ',"\n'):
        return '"' + text.replace('"', '""') + '"'
    return text


def utc_now() -> datetime:
    return datetime.now(timezone.utc)
