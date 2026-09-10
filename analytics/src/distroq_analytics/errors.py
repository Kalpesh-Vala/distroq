"""Exit codes and the error type that carries them.

Every failure path the CLI can take maps to a distinct nonzero code so that a caller
can tell "your window was nonsense" from "the database was down" without parsing text.
"""

from __future__ import annotations

EXIT_OK = 0
EXIT_USAGE = 1
EXIT_INVALID_WINDOW = 2
EXIT_DB_CONNECTION = 3
EXIT_MISSING_TABLE = 4
EXIT_EXTRACTION_FAILED = 5
EXIT_TRANSFORM_FAILED = 6
EXIT_OUTPUT_EXISTS = 7
EXIT_QUALITY_FATAL = 8
EXIT_MISSING_INPUT = 9

EXIT_NAMES = {
    EXIT_OK: "OK",
    EXIT_USAGE: "USAGE",
    EXIT_INVALID_WINDOW: "INVALID_WINDOW",
    EXIT_DB_CONNECTION: "DB_CONNECTION",
    EXIT_MISSING_TABLE: "MISSING_TABLE",
    EXIT_EXTRACTION_FAILED: "EXTRACTION_FAILED",
    EXIT_TRANSFORM_FAILED: "TRANSFORM_FAILED",
    EXIT_OUTPUT_EXISTS: "OUTPUT_EXISTS",
    EXIT_QUALITY_FATAL: "QUALITY_FATAL",
    EXIT_MISSING_INPUT: "MISSING_INPUT",
}


class AnalyticsError(Exception):
    """A failure with a specific exit code attached."""

    def __init__(self, message: str, exit_code: int) -> None:
        super().__init__(message)
        self.exit_code = exit_code

    @property
    def exit_name(self) -> str:
        return EXIT_NAMES.get(self.exit_code, "UNKNOWN")


class InvalidWindowError(AnalyticsError):
    def __init__(self, message: str) -> None:
        super().__init__(message, EXIT_INVALID_WINDOW)


class DatabaseConnectionError(AnalyticsError):
    def __init__(self, message: str) -> None:
        super().__init__(message, EXIT_DB_CONNECTION)


class MissingSourceTableError(AnalyticsError):
    def __init__(self, message: str) -> None:
        super().__init__(message, EXIT_MISSING_TABLE)


class ExtractionError(AnalyticsError):
    def __init__(self, message: str) -> None:
        super().__init__(message, EXIT_EXTRACTION_FAILED)


class TransformationError(AnalyticsError):
    def __init__(self, message: str) -> None:
        super().__init__(message, EXIT_TRANSFORM_FAILED)


class OutputExistsError(AnalyticsError):
    def __init__(self, message: str) -> None:
        super().__init__(message, EXIT_OUTPUT_EXISTS)


class MissingInputError(AnalyticsError):
    def __init__(self, message: str) -> None:
        super().__init__(message, EXIT_MISSING_INPUT)


class FatalQualityError(AnalyticsError):
    def __init__(self, message: str) -> None:
        super().__init__(message, EXIT_QUALITY_FATAL)
