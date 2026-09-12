import { api } from '../api/client';
import type { OverviewResponse, Section } from '../api/types';
import { usePolling } from '../hooks/usePolling';
import { useRefreshInterval } from '../RefreshContext';
import { MetricCard } from '../components/MetricCard';
import { SectionPanel } from '../components/SectionPanel';
import { ActivityFeed } from '../components/ActivityFeed';
import { renderMetric, toneForStatus, type Tone } from '../format';

/**
 * The page an operator leaves open.
 *
 * <p>Fourteen cards, each of which knows whether its own dependency answered. The cards backed by
 * Redis go grey when Redis goes away while the PostgreSQL-backed cards keep counting, because
 * that is the shape of the outage and the dashboard's job is to show it rather than to average
 * over it.
 */
export function OverviewPage() {
  const intervalMs = useRefreshInterval();
  const poll = usePolling<OverviewResponse>((signal) => api.overview({ signal }), { intervalMs });
  const data = poll.data;

  const health = data?.health;
  const queues = data?.queues;
  const workers = data?.workers;
  const outbox = data?.outbox;
  const reconciliation = data?.reconciliation;
  const deadLetters = data?.deadLetters;
  const updated = data?.timestamp;

  return (
    <>
      <h2 className="page-title">Overview</h2>
      {poll.stale ? (
        <p className="notice notice--warn" role="status">
          The last refresh did not succeed. Everything below is the last state that was
          confirmed, not the current one.
        </p>
      ) : null}

      <div className="cards" role="list">
        <div role="listitem">
          <MetricCard
            label="Application readiness"
            metric={textMetric(health?.data?.readiness, health)}
            tone={toneForStatus(health?.data?.readiness)}
            explanation="Whether this instance should be given work. Depends on PostgreSQL, Redis, Flyway and the background loops."
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="Application liveness"
            metric={textMetric(health?.data?.liveness, health)}
            tone={toneForStatus(health?.data?.liveness)}
            explanation="Whether the JVM is healthy. Deliberately independent of PostgreSQL and Redis."
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="PostgreSQL health"
            metric={textMetric(componentStatus(data, 'db'), health)}
            tone={toneForStatus(componentStatus(data, 'db'))}
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="Redis health"
            metric={textMetric(componentStatus(data, 'redis'), health)}
            tone={toneForStatus(componentStatus(data, 'redis'))}
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="Worker concurrency"
            metric={renderMetric(workers?.data?.configuredConcurrency, workers?.availability)}
            explanation="Configured worker threads on the instance serving this request, not across the cluster."
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="Active workers"
            metric={renderMetric(workers?.data?.activeWorkers, workers?.availability)}
            explanation="Threads currently executing a job on this instance."
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="Active leases"
            metric={renderMetric(workers?.data?.activeLeases, workers?.availability)}
            explanation="Database execution leases that have not expired. Cluster-wide, and the authority on who owns a job."
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="Scheduled jobs"
            metric={renderMetric(queues?.data?.scheduledDepth, queues?.availability)}
            explanation="Jobs waiting for a time the submitter asked for. Not backlog: no worker could run them yet."
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="Delayed retries"
            metric={renderMetric(queues?.data?.delayedDepth, queues?.availability)}
            explanation="Jobs waiting on a retry backoff. A different sorted set from scheduled jobs, on purpose."
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="Pending Stream entries"
            metric={renderMetric(queues?.data?.pendingEntries, queues?.availability)}
            tone={pendingTone(queues?.data?.pendingEntries)}
            explanation="Entries delivered to a consumer and not yet acknowledged. In-flight work, plus anything abandoned and not yet reclaimed."
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="Outbox pending"
            metric={renderMetric(outbox?.data?.pending, outbox?.availability)}
            explanation="Durable intent the relay has not yet published to Redis."
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="Outbox terminal failures"
            metric={renderMetric(outbox?.data?.terminalFailed, outbox?.availability)}
            tone={countTone(outbox?.data?.terminalFailed, 'error')}
            explanation="Events that spent their whole retry budget. They will not move again until an operator re-arms them through the administrative API."
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="Reconciliation findings"
            metric={renderMetric(reconciliation?.data?.findings, reconciliation?.availability)}
            tone={countTone(reconciliation?.data?.findings, 'warn')}
            explanation="Live recount of the findings expressible as a single indexed query. The Reconciliation page runs the full preview."
            lastUpdatedAt={updated}
          />
        </div>
        <div role="listitem">
          <MetricCard
            label="Dead-lettered jobs"
            metric={renderMetric(deadLetters?.data?.deadLettered, deadLetters?.availability)}
            tone={countTone(deadLetters?.data?.deadLettered, 'error')}
            explanation="Jobs that exhausted their retries and have not been replayed."
            lastUpdatedAt={updated}
          />
        </div>
      </div>

      <SectionPanel
        title="Recent activity"
        section={data?.activity}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(events) => events.length === 0}
        emptyMessage="No recorded activity yet."
        description="Reconstructed from database rows. Readiness changes come from this instance only and are lost on restart."
      >
        {(events) => <ActivityFeed events={events} />}
      </SectionPanel>
    </>
  );
}

function componentStatus(data: OverviewResponse | null, name: string): string | undefined {
  return data?.health.data?.components.find((component) => component.name === name)?.status;
}

/** A status string reuses the metric machinery so "unavailable" stays distinct from a value. */
function textMetric(value: string | undefined, section: Section<unknown> | undefined) {
  if (section?.availability === 'UNAVAILABLE') {
    return { text: 'Unavailable', state: 'unavailable' as const };
  }
  if (section?.availability === 'NOT_CONFIGURED') {
    return { text: 'Not configured', state: 'not-configured' as const };
  }
  if (!value) {
    return { text: 'No data', state: 'no-data' as const };
  }
  return { text: value, state: 'value' as const };
}

function pendingTone(pending: number | null | undefined): Tone {
  if (pending === null || pending === undefined) {
    return 'unknown';
  }
  return pending > 0 ? 'info' : 'ok';
}

function countTone(count: number | null | undefined, whenNonZero: Tone): Tone {
  if (count === null || count === undefined) {
    return 'unknown';
  }
  return count > 0 ? whenNonZero : 'ok';
}
