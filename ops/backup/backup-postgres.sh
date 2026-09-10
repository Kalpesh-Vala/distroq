#!/usr/bin/env sh
# Custom-format logical backup of the DistroQ PostgreSQL database.
#
# The POSIX counterpart of backup-postgres.ps1, for the machine the database actually runs on.
# Read-only: pg_dump takes a consistent snapshot and does not block writers.
#
# The password is never an argument. Set PGPASSWORD, or use ~/.pgpass, so it stays out of the
# shell history and out of `ps`.
#
#   PGPASSWORD=... ops/backup/backup-postgres.sh
#
# Environment:
#   PGHOST (localhost)  PGPORT (5433)  PGDATABASE (distroq)  PGUSER (distroq)
#   OUTPUT_DIR (the directory this script lives in)

set -eu

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5433}"
PGDATABASE="${PGDATABASE:-distroq}"
PGUSER="${PGUSER:-distroq}"
OUTPUT_DIR="${OUTPUT_DIR:-$(dirname "$0")}"

command -v pg_dump >/dev/null 2>&1 || {
    echo "pg_dump is not on PATH. Install the PostgreSQL client tools, or run it in the container:" >&2
    echo "  docker exec distroq-postgres pg_dump -U distroq -d distroq -Fc -f /backup/distroq.dump" >&2
    exit 1
}

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP_FILE="${OUTPUT_DIR}/distroq-${PGDATABASE}-${STAMP}.dump"

echo "Backing up ${PGDATABASE} from ${PGHOST}:${PGPORT} to ${DUMP_FILE}"

# --no-owner/--no-privileges so the dump restores into a test database whose roles differ
pg_dump --host="$PGHOST" --port="$PGPORT" --username="$PGUSER" --dbname="$PGDATABASE" \
    --format=custom --compress=6 --no-owner --no-privileges --file="$DUMP_FILE"

# reading the archive back is the cheapest possible proof that it is not a zero-byte file
pg_restore --list "$DUMP_FILE" > "${DUMP_FILE}.manifest.txt"

echo
echo "Backup complete."
echo "  file     : ${DUMP_FILE}"
echo "  size     : $(du -h "$DUMP_FILE" | cut -f1)"
echo "  manifest : ${DUMP_FILE}.manifest.txt"
echo
echo "Verify it by restoring into a scratch database before you need it:"
echo "  ops/restore/restore-postgres.sh --dump-file '${DUMP_FILE}' --database distroq_restore_test --confirm"
