import { api } from '../api/client';
import type { LeaseRow, WorkersResponse } from '../api/types';
import { usePolling } from '../hooks/usePolling';
import { useRefreshInterval } from '../RefreshContext';
import { SectionPanel } from '../components/SectionPanel';
import { DataTable, type Column } from '../components/DataTable';
import { KeyValue } from '../components/KeyValue';
import { StatusBadge } from '../components/StatusBadge';
import { formatDuration, formatNumber, formatTimestamp, shortId } from '../format';
import { hrefFor } from '../hooks/useHashRoute';

/**
 * Consumers and leases, side by side and never merged.
 *
 * <p>Redis says who was handed a delivery; PostgreSQL says who is allowed to run the job. When
 * the two disagree the lease is right, and the disagreement itself is the interesting signal —
 * so the page shows both lists and flags a lease whose consumer has gone quiet rather than
 * silently preferring one source.
 */
export function WorkersPage() {
  const intervalMs = useRefreshInterval();
  const poll = usePolling<WorkersResponse>((signal) => api.workers({ signal }), { intervalMs });
  const data = poll.data;

  const leaseColumns: Column<LeaseRow>[] = [
    {
      key: 'jobId',
      header: 'Job',
      render: (row) => (
        <a href={hrefFor('jobs', row.jobId)} title={row.jobId}>
          {shortId(row.jobId)}
        </a>
      )
    },
    { key: 'jobType', header: 'Type', render: (row) => row.jobType },
    { key: 'priority', header: 'Priority', render: (row) => row.priority },
    { key: 'status', header: 'Status', render: (row) => row.status },
    {
      key: 'attemptId',
      header: 'Attempt',
      render: (row) => <span title={row.attemptId ?? ''}>{shortId(row.attemptId)}</span>
    },
    { key: 'workerId', header: 'Worker', render: (row) => row.workerId ?? 'No data' },
    { key: 'consumerName', header: 'Consumer', render: (row) => row.consumerName ?? 'No data' },
    {
      key: 'startedAt',
      header: 'Started',
      render: (row) => formatTimestamp(row.startedAt)
    },
    {
      key: 'leaseUntil',
      header: 'Lease until',
      render: (row) => formatTimestamp(row.leaseUntil)
    },
    {
      key: 'remaining',
      header: 'Lease remaining',
      numeric: true,
      render: (row) => formatDuration(row.remainingLeaseMs)
    },
    { key: 'warnings', header: 'Warnings', render: (row) => warnings(row) }
  ];

  return (
    <>
      <h2 className="page-title">Workers</h2>
      <p className="notice notice--muted">{data?.note ?? 'Loading worker definitions…'}</p>

      <SectionPanel
        title="Worker totals"
        section={data?.totals}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
      >
        {(totals) => (
          <KeyValue
            items={[
              {
                label: 'Configured concurrency (this instance)',
                value: formatNumber(totals.configuredConcurrency)
              },
              { label: 'Active workers (this instance)', value: formatNumber(totals.activeWorkers) },
              { label: 'Idle workers (this instance)', value: formatNumber(totals.idleWorkers) },
              { label: 'Active execution leases (cluster)', value: formatNumber(totals.activeLeases) },
              {
                label: 'Reclaimed entries (this instance, since start)',
                value: formatNumber(totals.reclaimedEntries)
              },
              {
                label: 'Abandoned attempts (cluster, all time)',
                value: formatNumber(totals.abandonedAttempts)
              }
            ]}
          />
        )}
      </SectionPanel>

      <SectionPanel
        title="Active execution leases"
        section={data?.leases}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(rows) => rows.length === 0}
        emptyMessage="No job currently holds a database execution lease."
        description="From PostgreSQL. This is the authority on who may run a job; it stays available when Redis does not."
      >
        {(rows) => (
          <DataTable
            caption="Active execution leases"
            columns={leaseColumns}
            rows={rows}
            rowKey={(row) => row.jobId}
          />
        )}
      </SectionPanel>

      <SectionPanel
        title="Stream consumers"
        section={data?.consumers}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(rows) => rows.length === 0}
        emptyMessage="No consumers are registered in the group."
        description="From Redis. Registered consumers, whether or not they currently hold a delivery."
      >
        {(rows) => (
          <DataTable
            caption="Consumers registered in the group"
            columns={[
              { key: 'name', header: 'Consumer', render: (row) => row.consumerName },
              { key: 'stream', header: 'Stream', render: (row) => row.streamKey },
              { key: 'priority', header: 'Priority', render: (row) => row.priority },
              {
                key: 'pending',
                header: 'Holding',
                numeric: true,
                render: (row) => formatNumber(row.pendingCount)
              },
              {
                key: 'idle',
                header: 'Last seen',
                numeric: true,
                render: (row) => `${formatDuration(row.idleTimeMs)} ago`
              },
              {
                key: 'state',
                header: 'State',
                render: (row) =>
                  row.idle ? (
                    <StatusBadge tone="ok">Idle</StatusBadge>
                  ) : (
                    <StatusBadge tone="info">Holding work</StatusBadge>
                  )
              }
            ]}
            rows={rows}
            rowKey={(row) => `${row.streamKey}:${row.consumerName}`}
          />
        )}
      </SectionPanel>

      <SectionPanel
        title="Unacknowledged deliveries"
        section={data?.pendingEntries}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(rows) => rows.length === 0}
        emptyMessage="Every delivery has been acknowledged."
        description="Stream entry metadata only. No payload field is read or shown."
      >
        {(rows) => (
          <DataTable
            caption="Unacknowledged stream deliveries"
            columns={[
              { key: 'entryId', header: 'Entry', render: (row) => row.entryId },
              { key: 'consumer', header: 'Consumer', render: (row) => row.consumerName },
              { key: 'priority', header: 'Priority', render: (row) => row.priority },
              {
                key: 'idle',
                header: 'Idle',
                numeric: true,
                render: (row) => formatDuration(row.idleMs)
              },
              {
                key: 'age',
                header: 'Age',
                numeric: true,
                render: (row) => (row.ageMs === null ? 'No data' : formatDuration(row.ageMs))
              },
              {
                key: 'deliveries',
                header: 'Deliveries',
                numeric: true,
                render: (row) =>
                  row.redelivered ? (
                    <StatusBadge tone="warn">{formatNumber(row.deliveryCount)}</StatusBadge>
                  ) : (
                    formatNumber(row.deliveryCount)
                  )
              }
            ]}
            rows={rows}
            rowKey={(row) => `${row.priority}:${row.entryId}`}
          />
        )}
      </SectionPanel>
    </>
  );
}

function warnings(row: LeaseRow) {
  const badges = [];
  if (row.expired) {
    badges.push(
      <StatusBadge key="expired" tone="error">
        Lease expired
      </StatusBadge>
    );
  } else if (row.expiringSoon) {
    badges.push(
      <StatusBadge key="soon" tone="warn">
        Expiring soon
      </StatusBadge>
    );
  }
  if (row.heartbeatStale === null) {
    badges.push(
      <StatusBadge key="unknown" tone="unknown">
        Heartbeat unknown
      </StatusBadge>
    );
  } else if (row.heartbeatStale) {
    badges.push(
      <StatusBadge key="stale" tone="warn">
        Heartbeat stale
      </StatusBadge>
    );
  }
  if (row.attemptCount > 1) {
    badges.push(
      <StatusBadge key="retried" tone="info">
        Attempt {row.attemptCount} of {row.maxAttempts}
      </StatusBadge>
    );
  }
  return badges.length > 0 ? <>{badges}</> : <StatusBadge tone="ok">Healthy</StatusBadge>;
}
