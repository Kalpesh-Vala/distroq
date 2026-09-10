# Upgrading to v1.0

From v0.9. Earlier versions should be upgraded through each intermediate release, because each one
added migrations that the next assumes.

**Summary: no schema change, no API break, and one setting that will stop your deployment starting
until you provide it.**

---

## The short version

```powershell
# 1. Back up. Not optional - see "Rollback" below for why.
.\ops\backup\backup-postgres.ps1 -DbHost localhost -Port 5433 -Database distroq -User distroq

# 2. Generate an administrative token and put it in the environment.
$token = -join ((1..32) | ForEach-Object { '{0:x2}' -f (Get-Random -Max 256) })
$env:DISTROQ_ADMIN_TOKEN = $token   # then store it wherever your secrets live

# 3. Stop the old instances, start the new binary.
.\mvnw.cmd clean package
java -jar target\distroq-1.0.0.jar

# 4. Confirm.
Invoke-RestMethod http://localhost:8080/actuator/health/readiness | ConvertTo-Json -Depth 6
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 8
```

---

## Database

**No migration. The schema stays at `V7`.**

`V8__v1_release_operational_metadata.sql` was considered and deliberately not written. The rule
applied was: add a table only if a concrete v1.0 requirement needs data to survive a restart and
cannot be met any other way. Each candidate failed it:

| Candidate | Why it was rejected |
|---|---|
| Release audit metadata | `/actuator/info` already reports version, build time and git commit, from the build itself rather than from a row someone has to remember to insert |
| Persistent reconciliation run records | Every repair reconciliation performs already writes a `reliability_actions` row. Storing the *runs* as well would persist a number that is only interesting while it is current, and reconciliation is already re-derivable on demand from `GET /api/admin/reconciliation` |
| Instance registration / shutdown records | The consumer name is already in `job_attempts.worker_id`, the lease owner is already in `jobs.execution_owner`, and the log carries `instanceId` on every line. A registry would be a third copy that can disagree with the other two |
| Metric snapshots | Explicitly ruled out. Every gauge v1.0 exposes is either a live count or a process-local counter; storing them would create a stale second answer |

The practical consequence: **the v1.0 binary runs against a v0.9 database with nothing to apply,**
Flyway reports "Schema is up to date", and Hibernate's `ddl-auto: validate` passes. Verified during
release testing against a live v0.9 database and against a database migrated from empty.

Because there is no migration, there is also no forward-only barrier at the schema level. The
barrier is elsewhere — see "Rollback".

---

## Required configuration changes

### 1. `DISTROQ_ADMIN_TOKEN` — required in production

This is the one change that will stop a deployment. Under the `production` profile, with
administrative endpoints enabled (the default), a missing token fails startup:

```
DistroQ refused to start: 1 configuration problem(s) must be fixed first.
  1. distroq.admin.token must be set when distroq.admin.enabled is true under the production
     profile; export DISTROQ_ADMIN_TOKEN. An unset environment variable binds as the literal
     placeholder text, which is why a blank value and an unresolved one are both refused here.
```

Generate one with `openssl rand -hex 32`. Distribute it to whatever calls the administrative
endpoints. Rotation and revocation are in `SECURITY.md`.

If you have no administrative callers at all, `DISTROQ_ADMIN_ENABLED=false` is a valid answer;
every protected endpoint then returns `403 ADMIN_DISABLED`.

### 2. The `production` profile is opt-in

v0.9 had one configuration. v1.0 has three, and `local` is the default. A deployment that does not
set `SPRING_PROFILES_ACTIVE=production` will start with the local profile — human-readable logs,
localhost datasource, and no requirement for an admin token. That is safe but almost certainly not
what a server wants.

```
SPRING_PROFILES_ACTIVE=production
```

### 3. Database and Redis connection details move to the environment

The production profile has no defaults for these. All of them are required:

```
DISTROQ_DB_URL=jdbc:postgresql://host:5432/distroq
DISTROQ_DB_USER=distroq
DISTROQ_DB_PASSWORD=...
DISTROQ_REDIS_HOST=redis
```

The full list is in `.env.example` and in the configuration reference in `README.md`.

### 4. Settings that are now refused rather than tolerated

If your v0.9 configuration contains any of these, v1.0 will not start until they are corrected.
Each was capable of causing a subtle production failure that only appeared under load or during an
outage:

| Setting | Rule |
|---|---|
| `distroq.worker.concurrency` | at least 1 |
| `distroq.worker.heartbeat-interval-ms` | strictly shorter than `execution-lease-ms` |
| `distroq.outbox.dedupe-retention-ms` | at least the longest `reconciliation.stale-*-after-ms` |
| `distroq.outbox.cleanup-interval-ms` | at least the longest `reconciliation.stale-*-after-ms` |
| `distroq.outbox.failed-retention-days` | at least `published-retention-days` |
| `distroq.streams.block-timeout-ms` | at least 1 (0 blocks forever and never notices shutdown) |
| `spring.task.scheduling.pool-size` | at least 6, one per sweep |
| `spring.jpa.hibernate.ddl-auto` (production) | `validate` or `none` |
| `distroq.outbox.fail-after-publish` (production) | `false` |
| The four Redis key properties | non-blank and all different |

The defaults shipped in `application.yml` satisfy every one of these. If you have not overridden
them, nothing here affects you.

---

## API changes

**No breaking changes to any existing endpoint.** Every v0.9 request that succeeded still succeeds,
with the same status code and the same success body.

### Error responses changed shape

This is the only difference a client can observe on a path that previously worked. Before:

```json
{ "timestamp": "...", "status": 400, "error": "Bad Request",
  "message": "priority must be one of HIGH, NORMAL, LOW ...", "path": "/api/jobs" }
```

After:

```json
{ "timestamp": "2026-09-10T15:44:48.855998200Z", "status": 400, "error": "Bad Request",
  "code": "INVALID_PRIORITY",
  "message": "priority must be one of HIGH, NORMAL, LOW (case-insensitive), got 'URGENT'",
  "path": "/api/jobs", "correlationId": "13afc202-6e28-4678-8f3b-9ad19e0ec4c0" }
```

Two fields added, none removed, status codes unchanged. A client that reads `status` and `message`
is unaffected. A client that was parsing the message text should move to `code`, which is now a
contract; the message is not.

Two responses that were previously an empty 404 body now carry the standard body with a code:
`GET /api/jobs/{id}` (`JOB_NOT_FOUND`) and `GET /api/dlq/{jobId}` (`DEAD_LETTER_NOT_FOUND`). The
status is unchanged.

### `/api/idempotency/{key}` now requires authentication

It returns `401` without a valid bearer token. This is a deliberate security correction rather
than a compatibility break: the key is caller-chosen and frequently a customer identifier, so an
unauthenticated lookup was an enumeration oracle. If a client depends on it, give that client the
token — or reconsider why a submitter needs to read the endpoint back.

### New endpoints

```
GET /actuator/health            GET /actuator/health/liveness
GET /actuator/health/readiness  GET /actuator/info
GET /actuator/metrics           GET /actuator/prometheus
```

`/api/metrics` is unchanged and remains supported.

---

## Analytics (v0.9 pipeline)

Nothing to do. The pipeline reads the schema, and the schema did not change. It reads the
application version out of `pom.xml`, so exports produced after this upgrade record `1.0.0` in
their metadata rather than `0.0.1-SNAPSHOT`; that is a metadata field, not a schema field, and
nothing consumes it structurally.

Re-run the analytics suite after upgrading if you want the confirmation:

```powershell
cd analytics
.\.venv\Scripts\python.exe -m pytest -q
```

---

## Operational behaviour that changes

None of these require action, but they will be visible.

- **Logs become JSON** under the production profile. If you have log parsing that assumes the
  Spring Boot console pattern, it needs updating. `DISTROQ_LOG_LEVEL` still controls verbosity;
  the local profile keeps the human-readable pattern.
- **Shutdown takes longer.** v0.9 stopped the worker pool with a fixed ten-second wait. v1.0 sets
  readiness DOWN, drains HTTP, then gives in-flight jobs `distroq.shutdown.worker-timeout-ms`
  (default 30s) to finish. Container stop grace periods must exceed
  `spring.lifecycle.timeout-per-shutdown-phase` plus that budget — the production compose file uses
  90s.
- **Readiness can be DOWN while liveness is UP.** If you have a health check pointed at
  `/actuator/health`, decide which question you are asking. An orchestrator's *liveness* probe must
  use `/actuator/health/liveness`, or a thirty-second database blip will restart every instance you
  have.
- **The instance ID appears in logs** as `instanceId`, defaulting to `<hostname>-<random>`. Set
  `DISTROQ_INSTANCE_ID` if you want a stable one.

---

## Verifying the upgrade

```powershell
# schema untouched, all seven migrations still recorded as successful
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

# the instance considers itself fit for work
Invoke-RestMethod http://localhost:8080/actuator/health/readiness | ConvertTo-Json -Depth 6

# the binary is the one you think it is
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 8

# administrative access works, and only with the token
curl.exe -i http://localhost:8080/api/admin/outbox                                   # 401
curl.exe -i -H "Authorization: Bearer $env:DISTROQ_ADMIN_TOKEN" `
  http://localhost:8080/api/admin/outbox                                             # 200

# existing data is intact
curl.exe -s http://localhost:8080/api/metrics | ConvertFrom-Json | Select-Object totalJobs, deadLetterCount
```

---

## Rollback

**Rolling back to v0.9 is a binary rollback. Do not expect it to be free.**

Because v1.0 adds no migration, the v0.9 binary can read a v1.0 database: the schema is identical.
Checking out `v0.9` and running it will start.

What you lose, immediately and without warning:

- **Administrative authentication.** v0.9 has none. The moment the older binary is running, every
  administrative endpoint is open to anything that can reach the port. If the rollback is a
  response to an incident, this is the wrong direction to move in, and the port must be blocked at
  the network before the rollback rather than after.
- Structured logging, the readiness/liveness split, `/actuator/*`, correlation IDs, stable error
  codes, and graceful shutdown. Anything monitoring those breaks.
- Startup configuration validation. A configuration v1.0 refused, v0.9 will happily run.

What survives: every job, attempt, dead letter, outbox event, audit row, effect and idempotency
key. The data model is unchanged.

### When a rollback *does* require restoring the database

If a future release adds a migration, the rule reverses, and it is worth stating now because the
instinct to "just check out the old tag" is strong during an incident:

> A forward migration is not undone by checking out an older binary. The older binary will run
> `ddl-auto: validate` against a schema it does not recognise and refuse to start — or, worse, will
> start and write rows that the newer schema's constraints would have rejected. Rolling back across
> a migration means restoring the database from the backup taken *before* the upgrade, and
> accepting the loss of everything written since.

That is why step 1 of this document is a backup, and why `ops/restore/restore-postgres.ps1`
verifies Flyway history and row counts rather than just reporting that `pg_restore` exited zero.

For v0.9 → v1.0 → v0.9 specifically, no restore is needed. Take the backup anyway. The one time it
matters is the time you did not.
