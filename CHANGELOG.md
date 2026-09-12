# Changelog

All notable changes to DistroQ. Versions are tags in this repository; each one is a merge to
`main` with a summary of what it changed and what it deliberately did not.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html) from v1.0.0 onwards.

---

## [1.1.0] — unreleased

A read-only operations dashboard for inspecting DistroQ without creating a second control plane.
Queue and worker transport state comes from Redis, durable job and reliability state comes from
PostgreSQL, analytics comes from completed v0.9 exports, and dependency failures remain visible as
unavailable sections rather than misleading zeroes.

### Added

- A React/TypeScript dashboard at `/dashboard/` with Overview, Queues, Workers, Outbox,
  Reconciliation, Jobs, Job Detail, DLQ, Analytics, and System views.
- Eleven GET-only endpoints under `/api/dashboard/**`, protected by the existing administrative
  bearer token and backed by bounded queries and explicit `AVAILABLE`, `UNAVAILABLE`, and
  `NOT_CONFIGURED` section states.
- Non-overlapping polling with cancellation, timeout, exponential backoff, manual refresh, and
  stale-data retention.
- Safe runtime, health, queue, lease, throughput, activity, and completed analytics-export views.
  DTOs omit job and outbox payloads by construction, and health failures do not expose raw errors.
- V8 read indexes for the timestamp and outcome queries used by polling views.

### Security

- The browser bundle contains no administrative token or build-time credential. Operators enter
  the token at runtime; it is held in `sessionStorage` and sent only in the Authorization header.
- A read-only filter rejects POST, PUT, PATCH, and DELETE below `/api/dashboard/**` with 405. Retry,
  replay, repair, and cleanup remain on the reason-bearing, audited `/api/admin/**` surface.
- The static `/dashboard/**` bundle is public. Deployments should restrict it with an
  identity-aware proxy or SSO because same-origin script execution can read session storage.

### Database

V8 adds six indexes and no tables, columns, or data changes. Ordinary Flyway index creation can
block writes while each index is built; large production tables should prebuild the indexes with
`CREATE INDEX CONCURRENTLY IF NOT EXISTS` before deploying. See `UPGRADE.md`.

### Limitations

- The dashboard polls; it does not use WebSockets or provide a live event stream.
- Analytics displays completed v0.9 exports and does not run or schedule the analytics pipeline.
- The shared bearer token is still a role credential, not a user identity.

## [1.0.0] — unreleased

The first release intended to be operated rather than demonstrated. No new queue mechanism, no new
job semantics, no new persistence: v1.0 makes the behaviour that already existed safe to run,
inspect, upgrade, recover and reason about.

**Delivery is still at-least-once.** Nothing here claims exactly-once execution, and nothing here
makes an arbitrary external side effect exactly-once. See "Limitations" below.

### Added

- **Configuration profiles.** `local`, `test` and `production`, with `local` as the default so a
  bare `mvnw spring-boot:run` still works with no environment set. Production takes every
  credential from an environment variable with no default.
- **Startup configuration validation.** `ConfigurationValidator` refuses to start on a
  configuration that would otherwise fail later, quietly, or only in production — zero worker
  concurrency, a heartbeat interval that is not shorter than the lease it renews, a deduplication
  window shorter than the reconciliation staleness window, a scheduler pool too small for the
  sweeps that share it, and a dozen more. Errors name the property.
- **Administrative authentication.** A configurable shared bearer token guards
  `/api/admin/**` and `/api/idempotency/**`. Constant-time comparison, never logged, never
  persisted, never exposed through actuator. Production startup fails if administrative endpoints
  are enabled without one.
- **Standardised error contract.** Every endpoint returns the same body with a stable
  machine-readable `code`, an HTTP status, a message, the request path and a correlation ID. No
  stack traces, no SQL, no Redis commands, no credentials.
- **Correlation IDs.** `X-Correlation-Id` is honoured when safe and generated otherwise, echoed on
  the response, and present on every log line the request produces.
- **Health, liveness and readiness.** `/actuator/health`, `/actuator/health/liveness` and
  `/actuator/health/readiness`, with separate indicators for PostgreSQL, Redis, Flyway, the outbox
  relay, the worker subsystem and the scheduler subsystem. Liveness is deliberately independent of
  both datastores.
- **Structured JSON logging** in the production profile, with a fixed field set and stable event
  names for every important transition (`job.submitted`, `job.dead_lettered`, `outbox.published`,
  `reconciliation.repair`, `application.shutdown_started`, and the rest).
- **Micrometer metrics** through `/actuator/metrics` and `/actuator/prometheus`, with bounded
  labels. The existing `/api/metrics` endpoint is unchanged.
- **Graceful shutdown.** Readiness goes DOWN first, then HTTP drains, then in-flight jobs are given
  a configurable budget to finish, then connections close. Work that outruns the budget is
  abandoned rather than falsely finalised.
- **Version metadata** at `/actuator/info`: version, build time, git commit and branch, all
  generated from the POM and the working tree rather than written down anywhere.
- **Container hardening.** A production `Dockerfile` (multi-stage, non-root, no baked secrets),
  `docker-compose.production.yml` with pinned images, health checks, named volumes, resource
  limits and a read-only root filesystem, plus `.dockerignore` and `.env.example`.
- **Backup and restore tooling** under `ops/backup/` and `ops/restore/`, safe by default and
  requiring explicit confirmation before anything destructive.
- **Release documentation**: this file, `UPGRADE.md`, `SECURITY.md` and `OPERATIONS.md`.

### Changed

- Project version is `1.0.0`, sourced from the POM alone. `application.yml` and `/actuator/info`
  both derive from it.
- `X-Admin-Actor` is now validated rather than silently truncated, and the fallback actor is
  configurable (`distroq.admin.default-actor`, default `admin`) instead of the hardcoded
  `operator`.
- Every background sweep — retry, scheduled-job, recovery, relay, reconciliation and retention —
  now consults one shared shutdown flag instead of keeping its own.
- `spring.data.redis.lettuce.pool` is enabled in the production profile, which required adding
  `commons-pool2`.
- `docker-compose.yml` pins `postgres:16.4-alpine` and `redis:7.4-alpine`, adds health checks and
  restart policies, and declares an explicit project name.

### Fixed

- **An unset environment variable could become the administrative token.** Spring's binder passes
  an unresolvable `${VAR}` through as literal text, so a production deployment that forgot to
  export `DISTROQ_ADMIN_TOKEN` would have started with a token whose value is printed in this
  repository. An unresolved placeholder is now treated as unset, which fails startup.
- **A false `job.execution_lease_lost` after a successful job.** The lease heartbeat runs on
  another thread and races finalisation; a renew landing microseconds after `succeed()` cleared the
  lease legitimately failed and was reported as lost ownership. Renewals are now stopped before
  finalisation and ignored afterwards. The job outcome was always correct; the alert was not.
- **The shutdown coordinator never ran.** It was a `SmartLifecycle`, but Spring publishes
  `ContextClosedEvent` before it stops any lifecycle bean, so the shared shutdown flag was already
  set by the time the coordinator's `stop()` was reached and the announcement was skipped. It is
  now an ordered `ContextClosedEvent` listener.
- **`docker-compose.production.yml` could delete the development containers.** Compose derives a
  project name from the directory, so both files landed in one project and bringing either up
  removed the other's containers as orphans. Both files now declare a project name.
- Unknown paths, unreadable bodies and type-mismatched path variables returned Spring's default
  error shape rather than the documented one.

### Limitations

Unchanged from v0.9, and stated again because a 1.0 is where people stop reading the fine print:

- Delivery is at-least-once. A job can execute more than once.
- The effect ledger makes *cooperative* effects idempotent. An arbitrary external API call is not
  exactly-once and is not claimed to be.
- The administrative bearer token authenticates a role, not a person. There is no per-user scoping,
  no rotation without a restart, and no rate limiting.
- Redis holds transport and scheduling state. Losing it may require reconciliation and can affect
  pending delivery recovery. See `OPERATIONS.md`.
- Rollback from v1.0 to v0.9 is a binary rollback only; see `UPGRADE.md`.

### Database

No migration. The schema is unchanged at `V7`. Every v1.0 requirement is met by configuration,
actuator, logs or the existing audit tables — see `UPGRADE.md` for why, and for what was
considered and rejected.

---

## [0.9] — read-only historical analytics

A containerised PySpark pipeline that extracts PostgreSQL source tables over explicit UTC
half-open windows, writes immutable Parquet fact datasets, produces operational aggregate reports
and runs data-quality checks. Read-only with respect to both PostgreSQL and Redis.

## [0.8] — outbox lifecycle, reconciliation and effect idempotency

Explicit outbox state, operator inspection and repair endpoints, a reliability audit trail,
scheduled reconciliation between PostgreSQL intent and Redis publication state, and an effect
ledger for cooperative idempotent side effects.

## [0.7] — transactional outbox, idempotency and execution leases

The transactional outbox and its relay, `Idempotency-Key` on submission, database execution leases
with heartbeats, and worker concurrency.

## [0.6] — durable user-scheduled jobs

`scheduledAt` on submission, a separate Redis sorted set for user-requested execution times, and a
promoter that survives restarts because the schedule was never in the JVM.

## [0.5] — Redis Streams

Consumer groups, Pending Entries Lists, `XAUTOCLAIM` recovery of abandoned work, and the
acknowledge-after-persist ordering that makes delivery at-least-once rather than at-most-once.

## [0.4] — priority tiers

`HIGH`, `NORMAL` and `LOW`, with a starvation guard that bounds consecutive higher-tier deliveries.

## [0.3] — dead letter queue

Terminal failures recorded and replayable.

## [0.2] — retries with exponential backoff

## [0.1] — initial job queue
