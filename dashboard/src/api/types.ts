/**
 * Response shapes for `/api/dashboard`.
 *
 * <p>These mirror `com.distroq.dashboard.dto.DashboardResponse` and its neighbours. Two things
 * matter more than the field lists.
 *
 * The first is {@link Section}. Every panel arrives wrapped in one, carrying its own
 * availability, so a Redis outage is a value the UI can render rather than an exception it has to
 * guess at. `data` is `null` unless `availability` is `AVAILABLE`, which is why every read of it
 * goes through a component that checks first.
 *
 * The second is what is absent. There is no `payload` on a job, no `payload` on an outbox event
 * and no response body on an effect, because the server does not send them. The dashboard cannot
 * leak what it never receives.
 */

export type Availability = 'AVAILABLE' | 'UNAVAILABLE' | 'NOT_CONFIGURED';

export interface Section<T> {
  availability: Availability;
  reason?: string | null;
  lastUpdatedAt: string;
  data?: T | null;
}

export interface SystemView {
  version: string | null;
  commit: string | null;
  commitAbbrev: string | null;
  branch: string | null;
  tags: string | null;
  buildTime: string | null;
  commitTime: string | null;
  environment: string | null;
  instanceId: string | null;
  javaVersion: string | null;
  javaVendorVersion: string | null;
  springBootVersion: string | null;
  redisVersion: string | null;
  postgresVersion: string | null;
  flywayVersion: string | null;
  flywayDescription: string | null;
  flywayPendingMigrations: number | null;
  liveness: string | null;
  readiness: string | null;
  startedAt: string | null;
  uptimeMs: number;
  lastSuccessfulHealthCheckAt: string | null;
  refreshIntervalMs: number;
  authenticationRequired: boolean;
}

export interface ComponentHealth {
  name: string;
  status: string;
  detail: string | null;
}

export interface HealthView {
  liveness: string;
  readiness: string;
  components: ComponentHealth[];
  checkedAt: string;
}

export interface QueueConsumerRow {
  consumerName: string;
  streamKey: string;
  priority: string;
  pendingCount: number;
  idleTimeMs: number;
  idle: boolean;
}

export interface QueuePriorityRow {
  priority: string;
  streamKey: string;
  streamLength: number;
  readyDepth: number | null;
  pendingEntries: number;
  scheduledCount: number;
  delayedCount: number;
  oldestPendingEntryAgeMs: number | null;
  activeConsumers: number;
  consumers: QueueConsumerRow[];
}

export interface QueueTotals {
  streamDepthByPriority: Record<string, number | null>;
  readyDepthByPriority: Record<string, number | null>;
  pendingEntriesByPriority: Record<string, number>;
  scheduledDepthByPriority: Record<string, number>;
  delayedDepthByPriority: Record<string, number>;
  streamDepth: number;
  readyDepth: number | null;
  pendingEntries: number;
  scheduledDepth: number;
  delayedDepth: number;
  activeConsumers: number;
}

export interface QueueView {
  priorities: QueuePriorityRow[];
  totals: QueueTotals;
}

export interface ThroughputRow {
  priority: string;
  submitted: number;
  started: number;
  succeeded: number;
  deadLettered: number;
  successRate: number | null;
}

export interface WorkerTotals {
  configuredConcurrency: number;
  activeWorkers: number;
  idleWorkers: number;
  activeLeases: number;
  reclaimedEntries: number;
  abandonedAttempts: number;
  instanceLocalCounters: boolean;
}

export interface LeaseRow {
  jobId: string;
  attemptId: string | null;
  workerId: string | null;
  consumerName: string | null;
  priority: string;
  jobType: string;
  status: string;
  startedAt: string | null;
  leaseUntil: string | null;
  remainingLeaseMs: number;
  expiringSoon: boolean;
  expired: boolean;
  heartbeatStale: boolean | null;
  attemptCount: number;
  maxAttempts: number;
}

export interface PendingEntryRow {
  entryId: string;
  consumerName: string;
  priority: string;
  idleMs: number;
  deliveryCount: number;
  ageMs: number | null;
  redelivered: boolean;
}

export interface OutboxTotals {
  pending: number;
  publishing: number;
  published: number;
  retryableFailed: number;
  terminalFailed: number;
  oldestUnpublishedAgeMs: number | null;
  oldestUnpublishedAt: string | null;
  operatorRetries: number;
}

export interface OutboxLatency {
  p50Ms: number | null;
  p95Ms: number | null;
  sampleSize: number;
  approximate: boolean;
}

export interface OutboxEventRow {
  eventId: string;
  eventType: string;
  aggregateId: string | null;
  status: string;
  attemptCount: number;
  operatorRetryCount: number;
  createdAt: string;
  availableAt: string | null;
  lockedUntil: string | null;
  publishedAt: string | null;
  terminalFailedAt: string | null;
  lastError: string | null;
  ageMs: number;
  payloadRedacted: boolean;
}

export interface PageView<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface OutboxView {
  totals: OutboxTotals;
  countsByStatus: Record<string, number>;
  countsByEventType: Record<string, number>;
  unpublishedByAge: Record<string, number>;
  latency: OutboxLatency;
  events: PageView<OutboxEventRow>;
}

export interface FindingRow {
  findingType: string;
  category: string;
  severity: 'ERROR' | 'WARNING' | 'INFO';
  targetType: string;
  targetId: string;
  description: string;
  repairable: boolean;
  resolution: string;
}

export interface ReconciliationView {
  summary: {
    startedAt: string;
    finishedAt: string;
    durationMs: number;
    inspected: number;
    batchSize: number;
    batchFull: boolean;
    findings: number;
    repaired: number;
    skipped: number;
    failed: number;
    unresolved: number;
    skippedBecauseAnotherRunHoldsTheLock: boolean;
  };
  configuration: {
    enabled: boolean;
    autoRepairAllowed: boolean;
    batchSize: number;
    pollIntervalMs: number;
    staleScheduledAfterMs: number;
    staleOutboxAfterMs: number;
    staleLeaseAfterMs: number;
    requeueFailedOutbox: boolean;
  };
  countsByType: Record<string, number>;
  countsByCategory: Record<string, number>;
  countsBySeverity: Record<string, number>;
  findings: FindingRow[];
  generatedAt: string;
  previewOnly: boolean;
}

export interface JobRow {
  jobId: string;
  type: string;
  priority: string;
  status: string;
  createdAt: string;
  updatedAt: string | null;
  scheduledAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  nextAttemptAt: string | null;
  attemptCount: number;
  maxAttempts: number;
  replayCount: number | null;
  executionOwner: string | null;
  executionLeaseUntil: string | null;
  durationMs: number | null;
  errorMessage: string | null;
}

export interface TimelineEntry {
  event: string;
  at: string;
  detail: string | null;
}

export interface AttemptRow {
  attemptNumber: number;
  workerId: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  outcome: string;
  durationMs: number | null;
  errorMessage: string | null;
}

export interface OutboxRefRow {
  eventId: string;
  eventType: string;
  status: string;
  attemptCount: number;
  createdAt: string;
  availableAt: string | null;
  publishedAt: string | null;
  terminalFailedAt: string | null;
  lastError: string | null;
}

export interface EffectRow {
  effectKey: string;
  effectType: string;
  status: string;
  attemptNumber: number;
  responseHash: string | null;
  createdAt: string;
  completedAt: string | null;
  errorMessage: string | null;
}

export interface LeaseHistoryRow {
  actionType: string;
  actor: string;
  createdAt: string;
  beforeState: string | null;
  afterState: string | null;
}

export interface JobDetail {
  job: JobRow;
  timeline: TimelineEntry[];
  attempts: AttemptRow[];
  outboxEvents: OutboxRefRow[];
  effects: EffectRow[];
  leaseHistory: LeaseHistoryRow[];
  deadLetter: {
    deadLettered: boolean;
    movedAt: string | null;
    replayed: boolean;
    replayedAt: string | null;
    replayCount: number;
    finalError: string | null;
  };
  scheduling: {
    scheduled: boolean;
    scheduledAt: string | null;
    scheduleDelayMs: number | null;
    overdue: boolean;
  };
  retry: {
    retrying: boolean;
    nextAttemptAt: string | null;
    dueInMs: number | null;
    attemptsRemaining: number;
  };
  payloadRedacted: boolean;
}

export interface DlqEntryRow {
  jobId: string;
  jobType: string | null;
  priority: string | null;
  movedAt: string;
  attemptCount: number;
  replayCount: number;
  scheduledAt: string | null;
  status: string | null;
  replayed: boolean;
  finalError: string | null;
}

export interface DlqView {
  totals: {
    deadLettered: number;
    replayedJobs: number;
    totalReplays: number;
    oldestDeadLetteredAt: string | null;
    oldestAgeMs: number | null;
  };
  countsByPriority: Record<string, number>;
  countsByJobType: Record<string, number>;
  byDay: { day: string; count: number }[];
  sampled: boolean;
  entries: PageView<DlqEntryRow>;
}

export type AnalyticsRow = Record<string, string | number | null>;

export interface AnalyticsView {
  runId: string;
  availableRuns: string[];
  windowStart: string | null;
  windowEnd: string | null;
  generatedAt: string | null;
  exportedAt: string | null;
  analyticsVersion: string | null;
  applicationVersion: string | null;
  durationSeconds: number;
  live: boolean;
  exact: boolean;
  basis: string;
  headline: Record<string, number | string | null>;
  factRowCounts: Record<string, number>;
  dataQuality: Record<string, unknown>;
  reports: Record<string, AnalyticsRow[]>;
  filters: {
    startDate: string | null;
    endDate: string | null;
    priority: string | null;
    jobType: string | null;
  };
}

export interface ActivityEvent {
  type: string;
  at: string;
  jobId: string | null;
  eventId: string | null;
  jobType: string | null;
  priority: string | null;
  status: string | null;
  detail: string | null;
}

export interface ReconciliationCounts {
  findings: number;
  repairs: number;
  staleScheduledJobs: number;
  staleRetryJobs: number;
  expiredExecutionLeases: number;
  staleEffects: number;
  autoRepairAllowed: boolean;
  enabled: boolean;
}

interface Envelope {
  timestamp: string;
  correlationId: string | null;
}

export interface OverviewResponse extends Envelope {
  system: Section<SystemView>;
  health: Section<HealthView>;
  queues: Section<QueueTotals>;
  workers: Section<WorkerTotals>;
  outbox: Section<OutboxTotals>;
  reconciliation: Section<ReconciliationCounts>;
  deadLetters: Section<DlqView['totals']>;
  activity: Section<ActivityEvent[]>;
}

export interface QueuesResponse extends Envelope {
  note: string;
  throughputWindowMs: number;
  queues: Section<QueueView>;
  throughput: Section<ThroughputRow[]>;
}

export interface WorkersResponse extends Envelope {
  note: string;
  leaseWarningMs: number;
  totals: Section<WorkerTotals>;
  leases: Section<LeaseRow[]>;
  consumers: Section<QueueConsumerRow[]>;
  pendingEntries: Section<PendingEntryRow[]>;
}

export interface OutboxResponse extends Envelope {
  outbox: Section<OutboxView>;
}

export interface ReconciliationResponse extends Envelope {
  warning: string;
  reconciliation: Section<ReconciliationView>;
}

export interface JobsResponse extends Envelope {
  jobs: Section<PageView<JobRow>>;
  facets: Section<{
    statuses: string[];
    priorities: string[];
    jobTypes: string[];
    countsByStatus: Record<string, number>;
  }>;
}

export interface JobDetailResponse extends Envelope {
  job: Section<JobDetail>;
}

export interface DlqResponse extends Envelope {
  note: string;
  dlq: Section<DlqView>;
}

export interface AnalyticsResponse extends Envelope {
  analytics: Section<AnalyticsView>;
}

export interface SystemResponse extends Envelope {
  system: Section<SystemView>;
  health: Section<HealthView>;
}

export interface ActivityResponse extends Envelope {
  activity: Section<ActivityEvent[]>;
}
