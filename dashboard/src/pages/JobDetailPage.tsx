import { api } from '../api/client';
import type { JobDetailResponse } from '../api/types';
import { usePolling } from '../hooks/usePolling';
import { useRefreshInterval } from '../RefreshContext';
import { SectionPanel } from '../components/SectionPanel';
import { DataTable } from '../components/DataTable';
import { KeyValue } from '../components/KeyValue';
import { StatusBadge } from '../components/StatusBadge';
import {
  formatBoolean,
  formatDuration,
  formatNumber,
  formatTimestamp,
  shortId,
  toneForStatus
} from '../format';
import { hrefFor } from '../hooks/useHashRoute';

/**
 * One job, end to end.
 *
 * <p>The timeline is the reason this page exists. Attempts, outbox publications, dead-lettering
 * and replay are four separate records with four separate clocks, and reading them as one ordered
 * story is otherwise a manual join across four API calls.
 *
 * <p>Four kinds of time stay distinguishable throughout: the requested `scheduledAt`, the retry
 * `nextAttemptAt`, each attempt's own start and finish, and the outbox event's publication. So do
 * the two kinds of identity: a stream entry is a physical delivery, an effect key is a logical
 * one, and the whole point of the effect ledger is that they do not match one to one.
 */
export function JobDetailPage({ jobId }: { jobId: string }) {
  const intervalMs = useRefreshInterval();
  const poll = usePolling<JobDetailResponse>((signal) => api.job(jobId, { signal }), {
    intervalMs,
    key: jobId
  });
  const data = poll.data;
  const notFound = poll.error && !poll.unauthorized && data === null;

  return (
    <>
      <p className="breadcrumb">
        <a href={hrefFor('jobs')}>Jobs</a> / <span title={jobId}>{shortId(jobId, 12)}</span>
      </p>
      <h2 className="page-title">Job detail</h2>

      {notFound ? (
        <p className="notice notice--error" role="alert">
          {poll.error?.message}
        </p>
      ) : null}

      <SectionPanel
        title="Summary"
        section={data?.job}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
      >
        {(detail) => (
          <KeyValue
            items={[
              { label: 'Job ID', value: detail.job.jobId },
              { label: 'Type', value: detail.job.type },
              { label: 'Priority', value: detail.job.priority },
              {
                label: 'Status',
                value: (
                  <StatusBadge tone={toneForStatus(detail.job.status)}>
                    {detail.job.status}
                  </StatusBadge>
                )
              },
              { label: 'Created', value: formatTimestamp(detail.job.createdAt) },
              {
                label: 'Requested execution time',
                value: detail.job.scheduledAt
                  ? formatTimestamp(detail.job.scheduledAt)
                  : 'Immediate'
              },
              { label: 'Current attempt started', value: formatTimestamp(detail.job.startedAt) },
              { label: 'Finished', value: formatTimestamp(detail.job.finishedAt) },
              {
                label: 'Next automatic retry',
                value: detail.job.nextAttemptAt
                  ? formatTimestamp(detail.job.nextAttemptAt)
                  : 'None scheduled'
              },
              {
                label: 'Attempts',
                value: `${formatNumber(detail.job.attemptCount)} of ${formatNumber(
                  detail.job.maxAttempts
                )}`
              },
              { label: 'Duration', value: formatDuration(detail.job.durationMs) },
              { label: 'Execution owner', value: detail.job.executionOwner ?? 'None' },
              {
                label: 'Execution lease until',
                value: formatTimestamp(detail.job.executionLeaseUntil)
              },
              { label: 'Last error', value: detail.job.errorMessage ?? 'None' },
              {
                label: 'Payload',
                value: <StatusBadge tone="info">Redacted — not sent to the dashboard</StatusBadge>
              }
            ]}
          />
        )}
      </SectionPanel>

      <SectionPanel
        title="Lifecycle timeline"
        section={data?.job}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(detail) => detail.timeline.length === 0}
        emptyMessage="No timestamped transitions are recorded for this job."
      >
        {(detail) => (
          <ol className="timeline">
            {detail.timeline.map((entry, index) => (
              <li className="timeline__item" key={`${entry.event}-${entry.at}-${index}`}>
                <span className="timeline__when">{formatTimestamp(entry.at)}</span>
                <StatusBadge tone={toneForStatus(entry.event)}>{entry.event}</StatusBadge>
                {entry.detail ? <span className="timeline__detail">{entry.detail}</span> : null}
              </li>
            ))}
          </ol>
        )}
      </SectionPanel>

      <SectionPanel
        title="Attempts"
        section={data?.job}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(detail) => detail.attempts.length === 0}
        emptyMessage="This job has not been attempted."
      >
        {(detail) => (
          <DataTable
            caption="Attempt history"
            columns={[
              { key: 'n', header: 'Attempt', numeric: true, render: (row) => row.attemptNumber },
              { key: 'worker', header: 'Worker', render: (row) => row.workerId ?? 'No data' },
              { key: 'started', header: 'Started', render: (row) => formatTimestamp(row.startedAt) },
              {
                key: 'finished',
                header: 'Finished',
                render: (row) => formatTimestamp(row.finishedAt)
              },
              {
                key: 'outcome',
                header: 'Outcome',
                render: (row) => (
                  <StatusBadge tone={toneForStatus(row.outcome)}>{row.outcome}</StatusBadge>
                )
              },
              {
                key: 'duration',
                header: 'Duration',
                numeric: true,
                render: (row) => formatDuration(row.durationMs)
              },
              {
                key: 'error',
                header: 'Error',
                render: (row) => <span className="error-cell">{row.errorMessage ?? '—'}</span>
              }
            ]}
            rows={detail.attempts}
            rowKey={(row) => String(row.attemptNumber)}
          />
        )}
      </SectionPanel>

      <SectionPanel
        title="Outbox events"
        section={data?.job}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(detail) => detail.outboxEvents.length === 0}
        emptyMessage="No outbox events reference this job."
      >
        {(detail) => (
          <DataTable
            caption="Outbox events for this job"
            columns={[
              {
                key: 'eventId',
                header: 'Event',
                render: (row) => <span title={row.eventId}>{shortId(row.eventId)}</span>
              },
              { key: 'type', header: 'Type', render: (row) => row.eventType },
              {
                key: 'status',
                header: 'Status',
                render: (row) => (
                  <StatusBadge tone={toneForStatus(row.status)}>{row.status}</StatusBadge>
                )
              },
              {
                key: 'attempts',
                header: 'Attempts',
                numeric: true,
                render: (row) => formatNumber(row.attemptCount)
              },
              { key: 'created', header: 'Created', render: (row) => formatTimestamp(row.createdAt) },
              {
                key: 'published',
                header: 'Published',
                render: (row) => formatTimestamp(row.publishedAt)
              },
              {
                key: 'terminal',
                header: 'Terminal at',
                render: (row) => formatTimestamp(row.terminalFailedAt)
              },
              {
                key: 'error',
                header: 'Last error',
                render: (row) => <span className="error-cell">{row.lastError ?? '—'}</span>
              }
            ]}
            rows={detail.outboxEvents}
            rowKey={(row) => row.eventId}
          />
        )}
      </SectionPanel>

      <SectionPanel
        title="Effect ledger"
        section={data?.job}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(detail) => detail.effects.length === 0}
        emptyMessage="This job claimed no protected side effects."
        description="An effect key is a logical identity that survives redelivery, which is why the effect count and the attempt count differ when a delivery is repeated. The ledger stores a digest of the response, never the response."
      >
        {(detail) => (
          <DataTable
            caption="Effect ledger entries"
            columns={[
              { key: 'key', header: 'Effect key', render: (row) => row.effectKey },
              { key: 'type', header: 'Type', render: (row) => row.effectType },
              {
                key: 'status',
                header: 'Status',
                render: (row) => (
                  <StatusBadge tone={toneForStatus(row.status)}>{row.status}</StatusBadge>
                )
              },
              { key: 'attempt', header: 'Attempt', numeric: true, render: (row) => row.attemptNumber },
              {
                key: 'hash',
                header: 'Response digest',
                render: (row) => <span title={row.responseHash ?? ''}>{shortId(row.responseHash)}</span>
              },
              { key: 'created', header: 'Created', render: (row) => formatTimestamp(row.createdAt) },
              {
                key: 'completed',
                header: 'Completed',
                render: (row) => formatTimestamp(row.completedAt)
              },
              {
                key: 'error',
                header: 'Error',
                render: (row) => <span className="error-cell">{row.errorMessage ?? '—'}</span>
              }
            ]}
            rows={detail.effects}
            rowKey={(row) => row.effectKey}
          />
        )}
      </SectionPanel>

      <SectionPanel
        title="Scheduling, retry and dead-letter state"
        section={data?.job}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
      >
        {(detail) => (
          <KeyValue
            items={[
              { label: 'Scheduled', value: formatBoolean(detail.scheduling.scheduled) },
              {
                label: 'Requested execution time',
                value: formatTimestamp(detail.scheduling.scheduledAt)
              },
              {
                label: 'Schedule delay (requested to start)',
                value: formatDuration(detail.scheduling.scheduleDelayMs)
              },
              { label: 'Overdue promotion', value: formatBoolean(detail.scheduling.overdue) },
              { label: 'Retrying', value: formatBoolean(detail.retry.retrying) },
              { label: 'Next attempt at', value: formatTimestamp(detail.retry.nextAttemptAt) },
              {
                label: 'Next attempt due in',
                value: formatDuration(detail.retry.dueInMs)
              },
              {
                label: 'Attempts remaining',
                value: formatNumber(detail.retry.attemptsRemaining)
              },
              { label: 'Dead-lettered', value: formatBoolean(detail.deadLetter.deadLettered) },
              { label: 'Moved to DLQ at', value: formatTimestamp(detail.deadLetter.movedAt) },
              { label: 'Replayed', value: formatBoolean(detail.deadLetter.replayed) },
              { label: 'Replayed at', value: formatTimestamp(detail.deadLetter.replayedAt) },
              { label: 'Replay count', value: formatNumber(detail.deadLetter.replayCount) },
              { label: 'Final error', value: detail.deadLetter.finalError ?? 'None' }
            ]}
          />
        )}
      </SectionPanel>

      <SectionPanel
        title="Lease and repair history"
        section={data?.job}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(detail) => detail.leaseHistory.length === 0}
        emptyMessage="No operator or reconciliation action has touched this job."
        description="Execution leases are mutable columns rather than an append-only table, so what survives is the audit trail of the times something intervened."
      >
        {(detail) => (
          <DataTable
            caption="Reliability actions recorded against this job"
            columns={[
              { key: 'action', header: 'Action', render: (row) => row.actionType },
              { key: 'actor', header: 'Actor', render: (row) => row.actor },
              { key: 'at', header: 'At', render: (row) => formatTimestamp(row.createdAt) },
              { key: 'before', header: 'Before', render: (row) => row.beforeState ?? '—' },
              { key: 'after', header: 'After', render: (row) => row.afterState ?? '—' }
            ]}
            rows={detail.leaseHistory}
            rowKey={(row) => `${row.actionType}-${row.createdAt}`}
          />
        )}
      </SectionPanel>
    </>
  );
}
