import type {
  AnalyticsResponse,
  DlqResponse,
  JobDetailResponse,
  JobsResponse,
  OutboxResponse,
  OverviewResponse,
  QueuesResponse,
  ReconciliationResponse,
  Section,
  SystemResponse,
  WorkersResponse
} from '../api/types';

/**
 * Response fixtures.
 *
 * <p>Deliberately built from the real shapes rather than from `any`, so a backend DTO change that
 * the TypeScript types have not caught up with breaks these first.
 */

export function available<T>(data: T, lastUpdatedAt = '2026-09-10T12:00:00Z'): Section<T> {
  return { availability: 'AVAILABLE', lastUpdatedAt, data };
}

export function unavailable<T>(reason = 'RedisConnectionFailureException'): Section<T> {
  return { availability: 'UNAVAILABLE', reason, lastUpdatedAt: '2026-09-10T12:00:00Z', data: null };
}

export function notConfigured<T>(reason = 'No analytics export is available'): Section<T> {
  return {
    availability: 'NOT_CONFIGURED',
    reason,
    lastUpdatedAt: '2026-09-10T12:00:00Z',
    data: null
  };
}

const ENVELOPE = { timestamp: '2026-09-10T12:00:00Z', correlationId: 'corr-1' };

export const systemView = {
  version: '1.1.0',
  commit: '0a1b2c3d4e5f',
  commitAbbrev: '0a1b2c3',
  branch: 'v1.1-dashboard',
  tags: '',
  buildTime: '2026-09-10T11:00:00Z',
  commitTime: '2026-09-10T10:30:00Z',
  environment: 'local',
  instanceId: 'host-abc12345',
  javaVersion: '21.0.5',
  javaVendorVersion: 'OpenJDK 64-Bit Server VM 21.0.5+11',
  springBootVersion: '3.5.16',
  redisVersion: '7.2.4',
  postgresVersion: '16.4',
  flywayVersion: '8',
  flywayDescription: 'dashboard read indexes',
  flywayPendingMigrations: 0,
  liveness: 'CORRECT',
  readiness: 'ACCEPTING_TRAFFIC',
  startedAt: '2026-09-10T09:00:00Z',
  uptimeMs: 10_800_000,
  lastSuccessfulHealthCheckAt: '2026-09-10T11:59:58Z',
  refreshIntervalMs: 5000,
  authenticationRequired: true
};

export const healthView = {
  liveness: 'CORRECT',
  readiness: 'ACCEPTING_TRAFFIC',
  components: [
    { name: 'db', status: 'UP', detail: 'database=PostgreSQL' },
    { name: 'redis', status: 'UP', detail: 'version=7.2.4' },
    { name: 'flyway', status: 'UP', detail: 'current=8, pending=0' }
  ],
  checkedAt: '2026-09-10T12:00:00Z'
};

export const queueTotals = {
  streamDepthByPriority: { HIGH: 4, NORMAL: 1, LOW: 11 },
  readyDepthByPriority: { HIGH: 2, NORMAL: 0, LOW: null },
  pendingEntriesByPriority: { HIGH: 0, NORMAL: 1, LOW: 0 },
  scheduledDepthByPriority: { HIGH: 1, NORMAL: 1, LOW: 0 },
  delayedDepthByPriority: { HIGH: 0, NORMAL: 1, LOW: 0 },
  streamDepth: 16,
  readyDepth: null,
  pendingEntries: 1,
  scheduledDepth: 2,
  delayedDepth: 1,
  activeConsumers: 2
};

export const workerTotals = {
  configuredConcurrency: 3,
  activeWorkers: 2,
  idleWorkers: 1,
  activeLeases: 2,
  reclaimedEntries: 4,
  abandonedAttempts: 1,
  instanceLocalCounters: true
};

export const outboxTotals = {
  pending: 0,
  publishing: 0,
  published: 26,
  retryableFailed: 0,
  terminalFailed: 1,
  oldestUnpublishedAgeMs: 831_245,
  oldestUnpublishedAt: '2026-09-10T11:46:08Z',
  operatorRetries: 1
};

export const dlqTotals = {
  deadLettered: 1,
  replayedJobs: 2,
  totalReplays: 3,
  oldestDeadLetteredAt: '2026-09-09T10:00:00Z',
  oldestAgeMs: 93_600_000
};

export function overview(overrides: Partial<OverviewResponse> = {}): OverviewResponse {
  return {
    ...ENVELOPE,
    system: available(systemView),
    health: available(healthView),
    queues: available(queueTotals),
    workers: available(workerTotals),
    outbox: available(outboxTotals),
    reconciliation: available({
      findings: 2,
      repairs: 1,
      staleScheduledJobs: 1,
      staleRetryJobs: 0,
      expiredExecutionLeases: 1,
      staleEffects: 0,
      autoRepairAllowed: false,
      enabled: true
    }),
    deadLetters: available(dlqTotals),
    activity: available([
      {
        type: 'job.dead_lettered',
        at: '2026-09-10T11:59:00Z',
        jobId: 'a4f2c1de-0000-4000-8000-000000000001',
        eventId: null,
        jobType: 'always_fail',
        priority: 'LOW',
        status: 'DEAD_LETTERED',
        detail: 'after 3 attempt(s)'
      }
    ]),
    ...overrides
  };
}

export function queues(overrides: Partial<QueuesResponse> = {}): QueuesResponse {
  return {
    ...ENVELOPE,
    note: 'Redis Stream length includes historical acknowledged entries and is not equivalent to the number of jobs waiting to execute.',
    throughputWindowMs: 86_400_000,
    queues: available({
      priorities: [
        {
          priority: 'HIGH',
          streamKey: 'distroq:jobs:stream:high',
          streamLength: 4,
          readyDepth: 2,
          pendingEntries: 0,
          scheduledCount: 1,
          delayedCount: 0,
          oldestPendingEntryAgeMs: null,
          activeConsumers: 1,
          consumers: [
            {
              consumerName: 'worker-a1b2c3',
              streamKey: 'distroq:jobs:stream:high',
              priority: 'HIGH',
              pendingCount: 0,
              idleTimeMs: 1200,
              idle: true
            }
          ]
        },
        {
          priority: 'LOW',
          streamKey: 'distroq:jobs:stream:low',
          streamLength: 11,
          readyDepth: null,
          pendingEntries: 0,
          scheduledCount: 0,
          delayedCount: 0,
          oldestPendingEntryAgeMs: null,
          activeConsumers: 0,
          consumers: []
        }
      ],
      totals: queueTotals
    }),
    throughput: available([
      {
        priority: 'HIGH',
        submitted: 32,
        started: 31,
        succeeded: 31,
        deadLettered: 0,
        successRate: 1
      },
      {
        priority: 'LOW',
        submitted: 21,
        started: 17,
        succeeded: 13,
        deadLettered: 4,
        successRate: 0.7647
      }
    ]),
    ...overrides
  };
}

export function workers(overrides: Partial<WorkersResponse> = {}): WorkersResponse {
  return {
    ...ENVELOPE,
    note: 'A Redis consumer owning a delivery does not necessarily mean it owns database execution. Database execution ownership is controlled by the execution lease.',
    leaseWarningMs: 5000,
    totals: available(workerTotals),
    leases: available([
      {
        jobId: 'a4f2c1de-0000-4000-8000-000000000002',
        attemptId: 'b4f2c1de-0000-4000-8000-000000000003',
        workerId: 'worker-a1b2c3',
        consumerName: 'worker-a1b2c3',
        priority: 'NORMAL',
        jobType: 'send_email',
        status: 'RUNNING',
        startedAt: '2026-09-10T11:59:50Z',
        leaseUntil: '2026-09-10T12:00:20Z',
        remainingLeaseMs: 20_000,
        expiringSoon: false,
        expired: false,
        heartbeatStale: false,
        attemptCount: 1,
        maxAttempts: 3
      }
    ]),
    consumers: available([
      {
        consumerName: 'worker-a1b2c3',
        streamKey: 'distroq:jobs:stream:normal',
        priority: 'NORMAL',
        pendingCount: 1,
        idleTimeMs: 300,
        idle: false
      }
    ]),
    pendingEntries: available([
      {
        entryId: '1789060000000-0',
        consumerName: 'worker-a1b2c3',
        priority: 'NORMAL',
        idleMs: 300,
        deliveryCount: 1,
        ageMs: 4000,
        redelivered: false
      }
    ]),
    ...overrides
  };
}

export function outbox(overrides: Partial<OutboxResponse> = {}): OutboxResponse {
  return {
    ...ENVELOPE,
    outbox: available({
      totals: outboxTotals,
      countsByStatus: { PENDING: 0, PUBLISHING: 0, PUBLISHED: 26, FAILED: 1 },
      countsByEventType: {
        ENQUEUE_SUBMIT: 20,
        SCHEDULE_RETRY: 5,
        SCHEDULE_USER_JOB: 1,
        ENQUEUE_REPLAY: 1
      },
      unpublishedByAge: { under1m: 0, '1mTo5m': 0, '5mTo1h': 0, over1h: 1 },
      latency: { p50Ms: 407, p95Ms: 1200, sampleSize: 26, approximate: true },
      events: {
        content: [
          {
            eventId: 'c4f2c1de-0000-4000-8000-000000000004',
            eventType: 'ENQUEUE_SUBMIT',
            aggregateId: 'a4f2c1de-0000-4000-8000-000000000001',
            status: 'FAILED',
            attemptCount: 100,
            operatorRetryCount: 1,
            createdAt: '2026-09-10T11:46:08Z',
            availableAt: '2026-09-10T11:46:08Z',
            lockedUntil: null,
            publishedAt: null,
            terminalFailedAt: '2026-09-10T11:50:00Z',
            lastError: 'RedisConnectionFailureException: unable to connect… (truncated)',
            ageMs: 831_245,
            payloadRedacted: true
          }
        ],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1
      }
    }),
    ...overrides
  };
}

export function reconciliation(
  overrides: Partial<ReconciliationResponse> = {}
): ReconciliationResponse {
  return {
    ...ENVELOPE,
    warning: 'The dashboard reports reconciliation findings but does not repair them.',
    reconciliation: available({
      summary: {
        startedAt: '2026-09-10T11:59:30Z',
        finishedAt: '2026-09-10T11:59:31Z',
        durationMs: 1000,
        inspected: 3,
        batchSize: 100,
        batchFull: false,
        findings: 2,
        repaired: 0,
        skipped: 0,
        failed: 0,
        unresolved: 2,
        skippedBecauseAnotherRunHoldsTheLock: false
      },
      configuration: {
        enabled: true,
        autoRepairAllowed: false,
        batchSize: 100,
        pollIntervalMs: 30_000,
        staleScheduledAfterMs: 60_000,
        staleOutboxAfterMs: 60_000,
        staleLeaseAfterMs: 60_000,
        requeueFailedOutbox: false
      },
      countsByType: { TERMINAL_OUTBOX_FAILURE: 1, STALE_SCHEDULED_JOB: 1 },
      countsByCategory: {
        OUTBOX: 1,
        SCHEDULED_JOBS: 1,
        RETRIES: 0,
        EXECUTION_LEASES: 0,
        EFFECTS: 0
      },
      countsBySeverity: { ERROR: 1, WARNING: 1, INFO: 0 },
      findings: [
        {
          findingType: 'TERMINAL_OUTBOX_FAILURE',
          category: 'OUTBOX',
          severity: 'ERROR',
          targetType: 'OutboxEvent',
          targetId: 'c4f2c1de-0000-4000-8000-000000000004',
          description: 'FAILED at 2026-09-10T11:50:00Z after 100 attempt(s)',
          repairable: false,
          resolution: 'REPORTED'
        },
        {
          findingType: 'STALE_SCHEDULED_JOB',
          category: 'SCHEDULED_JOBS',
          severity: 'WARNING',
          targetType: 'Job',
          targetId: 'a4f2c1de-0000-4000-8000-000000000005',
          description: 'SCHEDULED since 2026-09-10T11:00:00Z',
          repairable: false,
          resolution: 'REPORTED'
        }
      ],
      generatedAt: '2026-09-10T11:59:31Z',
      previewOnly: true
    }),
    ...overrides
  };
}

export function jobs(overrides: Partial<JobsResponse> = {}): JobsResponse {
  return {
    ...ENVELOPE,
    jobs: available({
      content: [
        {
          jobId: 'a4f2c1de-0000-4000-8000-000000000001',
          type: 'send_email',
          priority: 'HIGH',
          status: 'SUCCEEDED',
          createdAt: '2026-09-10T11:00:00Z',
          updatedAt: '2026-09-10T11:00:04Z',
          scheduledAt: null,
          startedAt: '2026-09-10T11:00:01Z',
          finishedAt: '2026-09-10T11:00:04Z',
          nextAttemptAt: null,
          attemptCount: 1,
          maxAttempts: 3,
          replayCount: null,
          executionOwner: null,
          executionLeaseUntil: null,
          durationMs: 3000,
          errorMessage: null
        }
      ],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1
    }),
    facets: available({
      statuses: ['QUEUED', 'SCHEDULED', 'RUNNING', 'RETRYING', 'SUCCEEDED', 'DEAD_LETTERED'],
      priorities: ['HIGH', 'NORMAL', 'LOW'],
      jobTypes: ['send_email', 'always_fail'],
      countsByStatus: { SUCCEEDED: 1 }
    }),
    ...overrides
  };
}

export function jobDetail(overrides: Partial<JobDetailResponse> = {}): JobDetailResponse {
  return {
    ...ENVELOPE,
    job: available({
      job: {
        jobId: 'a4f2c1de-0000-4000-8000-000000000006',
        type: 'nightly_report',
        priority: 'NORMAL',
        status: 'SUCCEEDED',
        createdAt: '2026-09-10T09:00:00Z',
        updatedAt: '2026-09-10T11:00:20Z',
        scheduledAt: '2026-09-10T11:00:00Z',
        startedAt: '2026-09-10T11:00:15Z',
        finishedAt: '2026-09-10T11:00:20Z',
        nextAttemptAt: null,
        attemptCount: 2,
        maxAttempts: 3,
        replayCount: 0,
        executionOwner: null,
        executionLeaseUntil: null,
        durationMs: 5000,
        errorMessage: null
      },
      timeline: [
        { event: 'job.submitted', at: '2026-09-10T09:00:00Z', detail: 'NORMAL nightly_report' },
        { event: 'outbox.published', at: '2026-09-10T09:00:01Z', detail: 'SCHEDULE_USER_JOB' },
        { event: 'job.scheduled', at: '2026-09-10T11:00:00Z', detail: 'requested execution time' },
        { event: 'job.started', at: '2026-09-10T11:00:02Z', detail: 'attempt 1 on worker-a1' },
        {
          event: 'job.retry_scheduled',
          at: '2026-09-10T11:00:05Z',
          detail: 'attempt 1 FAILURE'
        },
        { event: 'job.started', at: '2026-09-10T11:00:15Z', detail: 'attempt 2 on worker-a1' },
        { event: 'job.succeeded', at: '2026-09-10T11:00:20Z', detail: 'attempt 2 SUCCESS' }
      ],
      attempts: [
        {
          attemptNumber: 1,
          workerId: 'worker-a1',
          startedAt: '2026-09-10T11:00:02Z',
          finishedAt: '2026-09-10T11:00:05Z',
          outcome: 'FAILURE',
          durationMs: 3000,
          errorMessage: 'upstream timed out'
        },
        {
          attemptNumber: 2,
          workerId: 'worker-a1',
          startedAt: '2026-09-10T11:00:15Z',
          finishedAt: '2026-09-10T11:00:20Z',
          outcome: 'SUCCESS',
          durationMs: 5000,
          errorMessage: null
        }
      ],
      outboxEvents: [
        {
          eventId: 'd4f2c1de-0000-4000-8000-000000000007',
          eventType: 'SCHEDULE_USER_JOB',
          status: 'PUBLISHED',
          attemptCount: 1,
          createdAt: '2026-09-10T09:00:00Z',
          availableAt: '2026-09-10T09:00:00Z',
          publishedAt: '2026-09-10T09:00:01Z',
          terminalFailedAt: null,
          lastError: null
        }
      ],
      effects: [
        {
          effectKey: 'report:2026-09-10',
          effectType: 'counter',
          status: 'COMPLETED',
          attemptNumber: 2,
          responseHash: 'e3b0c44298fc1c149afbf4c8996fb924',
          createdAt: '2026-09-10T11:00:16Z',
          completedAt: '2026-09-10T11:00:19Z',
          errorMessage: null
        }
      ],
      leaseHistory: [],
      deadLetter: {
        deadLettered: false,
        movedAt: null,
        replayed: false,
        replayedAt: null,
        replayCount: 0,
        finalError: null
      },
      scheduling: {
        scheduled: true,
        scheduledAt: '2026-09-10T11:00:00Z',
        scheduleDelayMs: 15_000,
        overdue: false
      },
      retry: { retrying: false, nextAttemptAt: null, dueInMs: null, attemptsRemaining: 1 },
      payloadRedacted: true
    }),
    ...overrides
  };
}

export function dlq(overrides: Partial<DlqResponse> = {}): DlqResponse {
  return {
    ...ENVELOPE,
    note: 'DLQ replay is available through the existing administrative API, not through this read-only dashboard phase.',
    dlq: available({
      totals: dlqTotals,
      countsByPriority: { HIGH: 0, NORMAL: 1, LOW: 3 },
      countsByJobType: { always_fail: 4 },
      byDay: [
        { day: '2026-09-09', count: 3 },
        { day: '2026-09-10', count: 1 }
      ],
      sampled: false,
      entries: {
        content: [
          {
            jobId: 'a4f2c1de-0000-4000-8000-000000000001',
            jobType: 'always_fail',
            priority: 'LOW',
            movedAt: '2026-09-10T11:59:00Z',
            attemptCount: 3,
            replayCount: 0,
            scheduledAt: null,
            status: 'DEAD_LETTERED',
            replayed: false,
            finalError: 'always fails'
          }
        ],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1
      }
    }),
    ...overrides
  };
}

export function analytics(overrides: Partial<AnalyticsResponse> = {}): AnalyticsResponse {
  return {
    ...ENVELOPE,
    analytics: available({
      runId: '20260101T000000Z__20270101T000000Z',
      availableRuns: ['20260101T000000Z__20270101T000000Z'],
      windowStart: '2026-01-01T00:00:00Z',
      windowEnd: '2027-01-01T00:00:00Z',
      generatedAt: '2026-09-10T12:51:33Z',
      exportedAt: '2026-09-09T19:11:27Z',
      analyticsVersion: '0.9.0',
      applicationVersion: '1.0.0',
      durationSeconds: 32.492,
      live: false,
      exact: false,
      basis: 'Batch aggregates from the v0.9 analytics export. Historical, not live.',
      headline: { submitted_jobs: 95, succeeded_jobs: 79, success_rate: 0.831579 },
      factRowCounts: { job_facts: 95 },
      dataQuality: { total_findings: 20, worst_severity: 'WARNING' },
      reports: {
        daily_job_summary: [
          {
            day: '2026-09-07',
            priority: 'HIGH',
            submitted_jobs: 11,
            started_jobs: 11,
            succeeded_jobs: 11,
            failed_jobs: 0,
            dead_lettered_jobs: 0,
            p50_duration_ms: 122,
            p95_duration_ms: 4014,
            p99_duration_ms: null,
            average_queue_delay_ms: 1369,
            average_schedule_delay_ms: 1351
          }
        ],
        priority_summary: [
          { priority: 'HIGH', submitted_jobs: 32, succeeded_jobs: 31, success_rate: 0.96875 }
        ],
        job_type_summary: [{ job_type: 'always_fail', submitted_jobs: 6, success_rate: 0 }],
        outbox_summary: [
          {
            day: '2026-09-08',
            event_type: 'ENQUEUE_REPLAY',
            total_events: 1,
            average_publication_latency_ms: 407
          }
        ],
        effect_summary: [
          { day: '2026-09-08', effect_type: 'counter', total_effects: 5, deduplication_hits: 1 }
        ],
        reliability_summary: [
          { day: '2026-09-08', action_type: 'LEASE_REPAIR', action_count: 1 }
        ],
        data_quality_summary: []
      },
      filters: { startDate: null, endDate: null, priority: null, jobType: null }
    }),
    ...overrides
  };
}

export function system(overrides: Partial<SystemResponse> = {}): SystemResponse {
  return {
    ...ENVELOPE,
    system: available(systemView),
    health: available(healthView),
    ...overrides
  };
}
