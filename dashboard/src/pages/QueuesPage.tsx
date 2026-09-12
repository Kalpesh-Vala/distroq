import { api } from '../api/client';
import type { QueuePriorityRow, QueuesResponse, ThroughputRow } from '../api/types';
import { usePolling } from '../hooks/usePolling';
import { useRefreshInterval } from '../RefreshContext';
import { SectionPanel } from '../components/SectionPanel';
import { DataTable, type Column } from '../components/DataTable';
import { BarChart } from '../components/BarChart';
import { StatusBadge } from '../components/StatusBadge';
import {
  formatDuration,
  formatNumber,
  formatPercent,
  formatTimestamp,
  renderMetric
} from '../format';

/**
 * Queue state per tier, with the five counts kept apart and labelled.
 *
 * <p>The column names are the point of this page. `Stream length` and `Ready depth` are both
 * integers about the same Redis key and they mean opposite things — one is everything that has
 * ever been added, the other is what has not been handed out — and the single most common way to
 * misread a Streams-backed queue is to alert on the first.
 */
export function QueuesPage() {
  const intervalMs = useRefreshInterval();
  const poll = usePolling<QueuesResponse>((signal) => api.queues({ signal }), { intervalMs });
  const data = poll.data;

  const columns: Column<QueuePriorityRow>[] = [
    {
      key: 'priority',
      header: 'Priority',
      render: (row) => <StatusBadge tone="info">{row.priority}</StatusBadge>
    },
    {
      key: 'streamLength',
      header: 'Stream length',
      hint: 'XLEN — history, not backlog',
      numeric: true,
      render: (row) => formatNumber(row.streamLength)
    },
    {
      key: 'readyDepth',
      header: 'Executable queue depth',
      hint: 'consumer group lag',
      numeric: true,
      render: (row) => renderMetric(row.readyDepth, 'AVAILABLE').text
    },
    {
      key: 'pending',
      header: 'Pending delivery count',
      hint: 'delivered, unacknowledged',
      numeric: true,
      render: (row) => formatNumber(row.pendingEntries)
    },
    {
      key: 'scheduled',
      header: 'Scheduled jobs',
      hint: 'waiting on a requested time',
      numeric: true,
      render: (row) => formatNumber(row.scheduledCount)
    },
    {
      key: 'delayed',
      header: 'Delayed retry jobs',
      hint: 'waiting on a backoff',
      numeric: true,
      render: (row) => formatNumber(row.delayedCount)
    },
    {
      key: 'oldest',
      header: 'Oldest pending entry',
      numeric: true,
      render: (row) =>
        row.oldestPendingEntryAgeMs === null ? 'No data' : formatDuration(row.oldestPendingEntryAgeMs)
    },
    {
      key: 'consumers',
      header: 'Active consumers',
      numeric: true,
      render: (row) => formatNumber(row.activeConsumers)
    },
    {
      key: 'backlog',
      header: 'Backlog',
      render: (row) => backlogIndicator(row)
    }
  ];

  const throughputColumns: Column<ThroughputRow>[] = [
    { key: 'priority', header: 'Priority', render: (row) => row.priority },
    { key: 'submitted', header: 'Submitted', numeric: true, render: (row) => formatNumber(row.submitted) },
    { key: 'started', header: 'Started', numeric: true, render: (row) => formatNumber(row.started) },
    { key: 'succeeded', header: 'Succeeded', numeric: true, render: (row) => formatNumber(row.succeeded) },
    {
      key: 'deadLettered',
      header: 'Dead-lettered',
      numeric: true,
      render: (row) => formatNumber(row.deadLettered)
    },
    {
      key: 'successRate',
      header: 'Success rate',
      numeric: true,
      render: (row) => formatPercent(row.successRate)
    }
  ];

  const priorities = data?.queues.data?.priorities ?? [];
  const throughput = data?.throughput.data ?? [];

  return (
    <>
      <h2 className="page-title">Queues</h2>
      <p className="notice notice--muted">{data?.note ?? 'Loading queue definitions…'}</p>

      <SectionPanel
        title="Queue state by priority"
        section={data?.queues}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        description={
          data ? `Last updated ${formatTimestamp(data.queues.lastUpdatedAt)}` : undefined
        }
      >
        {(queues) => (
          <>
            <DataTable
              caption="Queue depth by priority"
              columns={columns}
              rows={queues.priorities}
              rowKey={(row) => row.priority}
            />
            <BarChart
              title="Pending entries by priority"
              categories={queues.priorities.map((row) => row.priority)}
              series={[
                {
                  label: 'Pending entries',
                  tone: 'warn',
                  values: queues.priorities.map((row) => row.pendingEntries)
                },
                {
                  label: 'Executable queue depth',
                  tone: 'info',
                  values: queues.priorities.map((row) => row.readyDepth)
                }
              ]}
            />
          </>
        )}
      </SectionPanel>

      <SectionPanel
        title="Recent throughput"
        section={data?.throughput}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(rows) => rows.length === 0}
        description={
          data
            ? `Approximate, over the last ${formatDuration(data.throughputWindowMs)}. Derived from job timestamps; the exact history is in the analytics export.`
            : undefined
        }
      >
        {(rows) => (
          <>
            <DataTable
              caption="Recent throughput by priority"
              columns={throughputColumns}
              rows={rows}
              rowKey={(row) => row.priority}
            />
            <BarChart
              title="Job outcomes by priority"
              categories={rows.map((row) => row.priority)}
              series={[
                { label: 'Submitted', tone: 'info', values: rows.map((row) => row.submitted) },
                { label: 'Started', tone: 'info', values: rows.map((row) => row.started) },
                { label: 'Succeeded', tone: 'ok', values: rows.map((row) => row.succeeded) },
                {
                  label: 'Dead-lettered',
                  tone: 'error',
                  values: rows.map((row) => row.deadLettered)
                }
              ]}
            />
          </>
        )}
      </SectionPanel>

      <SectionPanel
        title="Consumers"
        section={data?.queues}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(queues) => queues.priorities.every((row) => row.consumers.length === 0)}
        emptyMessage="No consumers are registered in the group."
      >
        {() => (
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
                header: 'Idle for',
                numeric: true,
                render: (row) => formatDuration(row.idleTimeMs)
              }
            ]}
            rows={priorities.flatMap((row) => row.consumers)}
            rowKey={(row) => `${row.streamKey}:${row.consumerName}`}
          />
        )}
      </SectionPanel>

      {throughput.length === 0 ? null : null}
    </>
  );
}

/**
 * A word, not just a colour, and derived from executable depth rather than stream length.
 * "Unknown" when Redis could not derive the lag, because a missing lag is not an empty queue.
 */
function backlogIndicator(row: QueuePriorityRow) {
  if (row.readyDepth === null) {
    return <StatusBadge tone="unknown">Unknown</StatusBadge>;
  }
  if (row.readyDepth === 0) {
    return <StatusBadge tone="ok">Drained</StatusBadge>;
  }
  if (row.readyDepth < 100) {
    return <StatusBadge tone="info">Working</StatusBadge>;
  }
  return <StatusBadge tone="warn">Building</StatusBadge>;
}
