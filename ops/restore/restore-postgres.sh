#!/usr/bin/env sh
# Restores a DistroQ PostgreSQL backup into a database, and verifies the result.
#
# DESTRUCTIVE. This drops and recreates the target database, so it does nothing without
# --confirm, and refuses a target named 'distroq' unless --allow-production-name is also given.
# Both guards exist because the failure mode is unrecoverable and the dangerous command differs
# from the safe one by one word.
#
#   PGPASSWORD=... ops/restore/restore-postgres.sh \
#       --dump-file ops/backup/distroq-20260910T120000Z.dump \
#       --database distroq_restore_test --confirm

set -eu

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5433}"
PGUSER="${PGUSER:-distroq}"
DUMP_FILE=""
DATABASE=""
CONFIRM=0
ALLOW_PRODUCTION_NAME=0

while [ $# -gt 0 ]; do
    case "$1" in
        --dump-file) DUMP_FILE="$2"; shift 2 ;;
        --database) DATABASE="$2"; shift 2 ;;
        --host) PGHOST="$2"; shift 2 ;;
        --port) PGPORT="$2"; shift 2 ;;
        --user) PGUSER="$2"; shift 2 ;;
        --confirm) CONFIRM=1; shift ;;
        --allow-production-name) ALLOW_PRODUCTION_NAME=1; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[ -n "$DUMP_FILE" ] || { echo "--dump-file is required" >&2; exit 2; }
[ -n "$DATABASE" ] || { echo "--database is required" >&2; exit 2; }
[ -f "$DUMP_FILE" ] || { echo "No such dump file: $DUMP_FILE" >&2; exit 1; }

for tool in pg_restore psql createdb dropdb; do
    command -v "$tool" >/dev/null 2>&1 || { echo "$tool is not on PATH." >&2; exit 1; }
done

if [ "$DATABASE" = "distroq" ] && [ "$ALLOW_PRODUCTION_NAME" -eq 0 ]; then
    cat >&2 <<EOF
Refusing to restore into a database named 'distroq'.

That is the default production database name. If this really is a scratch instance, pass
--allow-production-name. If it is not, restore into a new database first and compare it:

  ops/restore/restore-postgres.sh --dump-file '$DUMP_FILE' --database distroq_restore_test --confirm
EOF
    exit 1
fi

if [ "$CONFIRM" -eq 0 ]; then
    cat >&2 <<EOF
Refusing to run without --confirm.

This will DROP the database '$DATABASE' on ${PGHOST}:${PGPORT} and recreate it from
$DUMP_FILE. Every row currently in it will be gone.
EOF
    exit 1
fi

echo "Dropping and recreating '$DATABASE' on ${PGHOST}:${PGPORT}"
dropdb --host="$PGHOST" --port="$PGPORT" --username="$PGUSER" --if-exists "$DATABASE"
createdb --host="$PGHOST" --port="$PGPORT" --username="$PGUSER" "$DATABASE"

echo "Restoring $DUMP_FILE"
# --exit-on-error: a restore that reports success after skipping a failed table is worse than one
# that stops, because the gap is only discovered later
pg_restore --host="$PGHOST" --port="$PGPORT" --username="$PGUSER" --dbname="$DATABASE" \
    --no-owner --no-privileges --exit-on-error "$DUMP_FILE"

query() {
    psql --host="$PGHOST" --port="$PGPORT" --username="$PGUSER" --dbname="$DATABASE" \
        --tuples-only --no-align --command="$1"
}

echo
echo "Flyway history:"
query "SELECT '  ' || version || ' | ' || description || ' | success=' || success
       FROM flyway_schema_history ORDER BY installed_rank;"

FAILED="$(query "SELECT count(*) FROM flyway_schema_history WHERE success = false;")"
if [ "$FAILED" != "0" ]; then
    echo "The restored database has $FAILED failed migration(s) recorded. Do not use it." >&2
    exit 1
fi

echo
echo "Row counts (compare these against the source):"
for table in jobs job_attempts dead_letters outbox_events idempotency_keys \
             reliability_actions job_effects effect_counters; do
    if [ "$(query "SELECT to_regclass('public.${table}') IS NOT NULL;")" = "t" ]; then
        printf '  %-22s %s\n' "$table" "$(query "SELECT count(*) FROM ${table};")"
    else
        printf '  %-22s (absent)\n' "$table"
    fi
done

cat <<EOF

Restore complete. The remaining check is Hibernate's, and it is the one that matters:

  SPRING_PROFILES_ACTIVE=production \\
  DISTROQ_DB_URL=jdbc:postgresql://${PGHOST}:${PGPORT}/${DATABASE} \\
  ./mvnw spring-boot:run

A clean start means ddl-auto=validate agreed with the restored schema.
EOF
