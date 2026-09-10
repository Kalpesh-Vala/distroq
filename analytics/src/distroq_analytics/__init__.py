"""Read-only historical analytics for DistroQ.

PostgreSQL is the source of truth. Nothing in this package writes to the application
database, to Redis, or to Flyway's schema history: every JDBC connection is opened
read-only and every statement issued is a SELECT.
"""

__version__ = "0.9.0"
