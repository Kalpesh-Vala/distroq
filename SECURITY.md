# Security notes

What DistroQ v1.1 protects, what it does not, and what an operator has to provide from outside.

This document is deliberately explicit about the gaps. A release that describes its own security
posture vaguely is one that gets deployed on the assumption it has more of it than it does.

---

## Threat model

DistroQ v1.0 assumes it runs on a network you control, reachable by services you trust, behind
something that terminates TLS. It defends against:

- An unauthenticated caller reaching the administrative surface and retrying outbox events,
  forcing reconciliation, deleting audit-eligible rows, or enumerating idempotency keys.
- Credentials leaking through logs, error responses, health details, metric labels or actuator
  endpoints.
- A misconfigured production deployment starting with a guessable or absent secret.
- Log injection through a caller-supplied correlation ID.
- Unbounded metric cardinality driven by caller-supplied job types.

It does **not** defend against:

- An attacker with network access to PostgreSQL or Redis directly. Both are trusted, and both hold
  everything.
- An attacker who can read the process environment or the container's environment variables.
- A malicious operator holding the administrative token.
- Denial of service. There is no rate limiting anywhere.

---

## Administrative authentication

The same bearer token also guards every GET endpoint under `/api/dashboard/**`. The dashboard API
is separately constrained to read methods: POST, PUT, PATCH, and DELETE are rejected with 405, and
all audited mutations remain under `/api/admin/**`.

The `/dashboard/**` HTML, CSS, and JavaScript files are public static resources. The bundle contains
no token. An operator enters the token at runtime, and the UI stores it in `sessionStorage` for the
tab and sends it only in the Authorization header. This avoids a persistent local credential and
prevents build-time publication, but it does not protect against same-origin script execution or a
compromised browser. Put the dashboard behind an identity-aware proxy or SSO, terminate TLS, and
limit it to an operations network. Do not supply an admin token through a `VITE_*` variable: Vite
substitutes those values into public JavaScript.

Where supported, let the identity-aware proxy retain the application credential server-side and
authenticate the browser with an HttpOnly, Secure session cookie, appropriate SameSite policy,
and CSRF protection. This is deployment guidance, not a session mechanism implemented by DistroQ.
Never put bearer tokens in URLs or `localStorage`. The current `sessionStorage` fallback is for
local development and does not prevent the browser user or a compromised session from reading it.

### What it is

A single shared bearer token, checked by a servlet filter in front of:

```
GET  /api/admin/outbox
GET  /api/admin/outbox/{eventId}
POST /api/admin/outbox/{eventId}/retry
POST /api/admin/outbox/cleanup
GET  /api/admin/reconciliation
POST /api/admin/reconciliation/run
GET  /api/admin/reliability-actions
GET  /api/idempotency/{key}
```

```
Authorization: Bearer <token>
```

`/api/idempotency/{key}` is in that list on purpose. The key is chosen by whoever submitted the
job and is very often a customer, order or invoice identifier, so an open lookup is both an
enumeration oracle and a way to read back someone else's job.

### What it is not

**It is not an identity system.** One token means one role, not one person. Two operators holding
the same token are indistinguishable to the application, and `X-Admin-Actor` is a self-declared
label — anyone with the token can claim to be anyone. `reliability_actions.actor` therefore
records *a claim made by a token holder*, not a verified subject. It is useful for reconstructing
intent after an incident, and it is not evidence.

There is no per-endpoint scoping, no read-only token, no expiry, no refresh, and no revocation
list.

### Properties that are guaranteed

| Property | How |
|---|---|
| The token is never logged | No call site logs the header, and only the method and path are logged on a rejection |
| The token is never persisted | It exists only in configuration and process memory |
| The token is never exposed through actuator | `env`, `configprops`, `beans`, `heapdump` and `threaddump` are not exposed |
| Comparison does not leak length or prefix by timing | `MessageDigest.isEqual` over UTF-8 bytes |
| An absent token fails production startup | `ConfigurationValidator`, not a runtime check |
| An *unresolved* `${VAR}` counts as absent | `DistroqProperties.isConfigured` — see below |
| A rejection reveals nothing about the resource | 401 is returned before any handler runs |

### The unresolved-placeholder rule

Spring's binder ignores a `${VAR}` it cannot resolve and passes the literal text through. Without
a specific guard, a production deployment that forgot to export `DISTROQ_ADMIN_TOKEN` would have
started successfully with an administrative token whose value is `${DISTROQ_ADMIN_TOKEN}` — a
string printed in this repository and in every copy of it.

v1.0 treats a value of the form `${...}` as unset, for the admin token, the datasource URL and the
Redis host. The deployment fails to start instead. This was found during v1.0 acceptance testing
and is the single most important fix in the release.

### Rotation

There is no online rotation. The procedure is a restart:

1. Generate a new token: `openssl rand -hex 32`.
2. Update the secret in whatever holds it (`.env`, a Kubernetes secret, a vault).
3. Restart the instances. With more than one instance, restart them one at a time: each instance
   reads the token at startup, so a rolling restart means a window during which both tokens are in
   use across the fleet. If that window is unacceptable, stop all instances first — administrative
   endpoints are not on the job submission path, so nothing is lost by having them briefly
   unavailable.
4. Distribute the new token to whoever needs it, over a channel that is not a ticket comment.

### Revocation

Revocation is rotation. There is no way to invalidate a token for one holder while keeping it valid
for another, because the application cannot tell them apart. If a token is believed to be
compromised, rotate immediately and audit `reliability_actions` for the period of exposure —
every mutating administrative call left a row there with a reason and a claimed actor.

### Disabling the surface entirely

```yaml
distroq:
  admin:
    enabled: false
```

Every protected endpoint then returns `403 ADMIN_DISABLED`, with or without a token. This is the
right setting for an instance that only submits and executes jobs, with a separate, differently
configured instance used for operator work.

---

## The actuator surface

Exposed:

```
/actuator/health           /actuator/health/liveness   /actuator/health/readiness
/actuator/info             /actuator/metrics           /actuator/prometheus
```

Not exposed, deliberately: `env`, `configprops`, `beans`, `heapdump`, `threaddump`, `loggers`,
`shutdown`, `mappings`. The first two would disclose `distroq.admin.token` and the datasource
password directly. `heapdump` would disclose both indirectly and rather more besides.

**Actuator has no authentication.** `management.endpoint.health.show-details: always` means the
health response names PostgreSQL, its version, the Redis version and the applied schema version.
None of that is a credential, and all of it is reconnaissance.

The network boundary is yours to provide. Choose one:

- Bind actuator to a separate port that is not published outside the pod or host:
  `management.server.port: 8081` plus a firewall or a Kubernetes `NetworkPolicy`.
- Keep it on 8080 and block `/actuator/**` at the ingress for anything but the monitoring system.
- Put an authenticating proxy in front of it.

Doing none of these means anyone who can reach the application port can read the health details
and the metric values. They still cannot read the token.

---

## Data handling

### What is never logged

- Job payloads. Only the job's `type`, which is a caller-chosen label rather than data.
- The administrative token, in whole or in part.
- The database password, the Redis password, or any connection string containing one.
- Raw idempotency keys.
- Effect responses. Only a hash — see `ResponseHash`.
- Exception *messages* on the `errorType` field. Only the exception class name, because a message
  routinely quotes the SQL, the URL or the value that caused it.

Full stack traces *are* logged, in the `stackTrace` field. A log file is an internal artefact; an
HTTP response body is not.

### What is never in an error response

Stack traces, exception types, SQL, Redis commands, connection strings, and the internal detail of
anything the caller could not have caused. An unexpected failure returns
`{"code": "INTERNAL_ERROR", "message": "The request could not be completed"}` and a correlation
ID; the operator finds the rest in the log line carrying the same ID.

An idempotency key *is* echoed in the `IDEMPOTENCY_KEY_CONFLICT` message, because the caller chose
it and already knows it. Nothing about the earlier request is disclosed.

### What is never a metric label

Job IDs, attempt IDs, outbox event IDs, idempotency keys, payloads, error messages, tokens. Labels
are bounded enumerations — priority, status, outcome, event type — plus job type, which is
caller-controlled and therefore capped: the first `distroq.metrics.max-job-type-tags` distinct
values keep their own series and everything after that becomes `other`. Set
`distroq.metrics.job-type-tag: false` to remove the label entirely.

### Correlation IDs

`X-Correlation-Id` is attacker-controlled and ends up in a log file. A value containing a newline
could forge a whole log record; one containing JSON punctuation could break the structured line it
is embedded in. Anything longer than 64 characters, or containing anything but
`[A-Za-z0-9._-]`, is replaced with a generated UUID rather than rejected — failing a request over
a header that only affects observability would be worse than ignoring it.

---

## Transport security

DistroQ terminates plain HTTP. There is no TLS configuration in this repository and none is
planned; terminate TLS at an ingress, a load balancer or a service mesh.

Consequences if you do not:

- The administrative bearer token crosses the network in clear text on every administrative call.
- Job payloads cross the network in clear text.

For Redis, `DISTROQ_REDIS_SSL=true` enables TLS to the Redis server, and `DISTROQ_REDIS_PASSWORD`
sets `AUTH`. For PostgreSQL, append `?ssl=true&sslmode=verify-full` to `DISTROQ_DB_URL`.

---

## Container posture

The production image and compose file provide:

- A non-root user with a fixed uid/gid (`10001:10001`).
- No build tooling, no source and no Maven cache in the runtime layer.
- No secrets in any layer. `.dockerignore` excludes `.env`, key material and local override files
  from the build context entirely, so they cannot be added by accident.
- `read_only: true` with a small tmpfs at `/tmp`, `cap_drop: ALL`, and
  `security_opt: no-new-privileges`.
- Pinned image tags. Pin digests as well for a real deployment — a tag can be moved.

Redis in the production compose runs with `--maxmemory-policy noeviction`. That is not a tuning
choice: any eviction policy would let Redis silently delete a stream entry or a scheduled member
under memory pressure, which is a lost job.

---

## Dependency and secret hygiene

- No credential is committed. `.env.example` contains placeholders only; `.env` is git-ignored and
  docker-ignored.
- Backups contain production data and are git-ignored (`ops/backup/*.dump` and friends). The
  scripts that create them are committed; their output is not.
- `spring.jpa.hibernate.ddl-auto` must be `validate` or `none` under the production profile.
  Anything else is refused at startup.
- `distroq.outbox.fail-after-publish` is an acceptance-test hook that deliberately breaks
  publication. It is refused under the production profile.

---

## Reporting

This is a personal project with no security contact and no disclosure process. Treat it as
unsupported software: read the code before you run it somewhere that matters.
