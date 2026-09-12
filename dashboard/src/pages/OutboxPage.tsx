import { useState } from 'react';
import { api } from '../api/client';
import type { OutboxEventRow, OutboxResponse } from '../api/types';
import { usePolling } from '../hooks/usePolling';
import { useRefreshInterval } from '../RefreshContext';
import { SectionPanel } from '../components/SectionPanel';
import { DataTable, type Column } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { StatusBadge } from '../components/StatusBadge';
import { BarChart } from '../components/BarChart';
import { KeyValue } from '../components/KeyValue';
import { formatDuration, formatNumber, formatTimestamp, shortId, toneForStatus } from '../format';
import { hrefFor } from '../hooks/useHashRoute';

const STATUSES = ['PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED'];
const EVENT_TYPES = ['ENQUEUE_SUBMIT', 'SCHEDULE_RETRY', 'SCHEDULE_USER_JOB', 'ENQUEUE_REPLAY'];
const AGE_BUCKETS: { key: string; label: string }[] = [
  { key: 'under1m', label: 'Under 1m' },
  { key: '1mTo5m', label: '1–5m' },
  { key: '5mTo1h', label: '5m–1h' },
  { key: 'over1h', label: 'Over 1h' }
];

/**
 * Outbox lifecycle, read-only.
 *
 * <p>No retry control. Re-arming a terminal event is an operator decision with a reason header
 * and an audit row behind it, and both of those live on the administrative API; putting a button
 * here would move an audited mutation onto a page that refreshes itself every five seconds.
 *
 * <p>No payload column, and no way to ask for one. `lastError` is shown because the relay wrote
 * it, and it arrives already truncated and stripped by the server.
 */
export function OutboxPage() {
  const intervalMs = useRefreshInterval();
  const [status, setStatus] = useState('');
  const [eventType, setEventType] = useState('');
  const [aggregateId, setAggregateId] = useState('');
  const [sort, setSort] = useState('createdAt');
  const [direction, setDirection] = useState<'asc' | 'desc'>('desc');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const query = { status, eventType, aggregateId, sort, direction, page, size };
  const poll = usePolling<OutboxResponse>(
    (signal) =>
      api.outbox(
        {
          status: status || undefined,
          eventType: eventType || undefined,
          aggregateId: aggregateId.trim() || undefined,
          sort,
          direction,
          page,
          size
        },
        { signal }
      ),
    { intervalMs, key: JSON.stringify(query) }
  );

  const data = poll.data;

  const columns: Column<OutboxEventRow>[] = [
    {
      key: 'eventId',
      header: 'Event',
      render: (row) => <span title={row.eventId}>{shortId(row.eventId)}</span>
    },
    { key: 'eventType', header: 'Type', render: (row) => row.eventType },
    {
      key: 'aggregateId',
      header: 'Job',
      render: (row) =>
        row.aggregateId ? (
          <a href={hrefFor('jobs', row.aggregateId)} title={row.aggregateId}>
            {shortId(row.aggregateId)}
          </a>
        ) : (
          'No data'
        )
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => <StatusBadge tone={toneForStatus(row.status)}>{row.status}</StatusBadge>
    },
    {
      key: 'attemptCount',
      header: 'Attempts',
      numeric: true,
      render: (row) => formatNumber(row.attemptCount)
    },
    {
      key: 'operatorRetryCount',
      header: 'Operator retries',
      numeric: true,
      render: (row) => formatNumber(row.operatorRetryCount)
    },
    { key: 'createdAt', header: 'Created', render: (row) => formatTimestamp(row.createdAt) },
    { key: 'availableAt', header: 'Available', render: (row) => formatTimestamp(row.availableAt) },
    { key: 'lockedUntil', header: 'Locked until', render: (row) => formatTimestamp(row.lockedUntil) },
    { key: 'publishedAt', header: 'Published', render: (row) => formatTimestamp(row.publishedAt) },
    {
      key: 'terminalFailedAt',
      header: 'Terminal at',
      render: (row) => formatTimestamp(row.terminalFailedAt)
    },
    { key: 'age', header: 'Age', numeric: true, render: (row) => formatDuration(row.ageMs) },
    {
      key: 'lastError',
      header: 'Last error',
      hint: 'redacted and truncated',
      render: (row) => <span className="error-cell">{row.lastError ?? '—'}</span>
    }
  ];

  return (
    <>
      <h2 className="page-title">Outbox</h2>
      <p className="notice notice--muted">
        Event payloads are never shown. Retry and cleanup remain on the administrative API.
      </p>

      <SectionPanel
        title="Lifecycle"
        section={data?.outbox}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
      >
        {(outbox) => (
          <>
            <KeyValue
              items={[
                { label: 'PENDING', value: formatNumber(outbox.totals.pending) },
                { label: 'PUBLISHING', value: formatNumber(outbox.totals.publishing) },
                { label: 'PUBLISHED', value: formatNumber(outbox.totals.published) },
                {
                  label: 'Retryable failures',
                  value: formatNumber(outbox.totals.retryableFailed)
                },
                {
                  label: 'Terminal failures',
                  value: (
                    <StatusBadge tone={outbox.totals.terminalFailed > 0 ? 'error' : 'ok'}>
                      {formatNumber(outbox.totals.terminalFailed)}
                    </StatusBadge>
                  )
                },
                {
                  label: 'Oldest unpublished',
                  value:
                    outbox.totals.oldestUnpublishedAgeMs === null
                      ? 'No unpublished events'
                      : `${formatDuration(outbox.totals.oldestUnpublishedAgeMs)} (${formatTimestamp(
                          outbox.totals.oldestUnpublishedAt
                        )})`
                },
                {
                  label: 'Publication latency p50',
                  value:
                    outbox.latency.p50Ms === null
                      ? 'No data'
                      : `${formatDuration(outbox.latency.p50Ms)} (approximate)`
                },
                {
                  label: 'Publication latency p95',
                  value:
                    outbox.latency.p95Ms === null
                      ? 'No data'
                      : `${formatDuration(outbox.latency.p95Ms)} (approximate)`
                },
                {
                  label: 'Latency sample size',
                  value: `${formatNumber(outbox.latency.sampleSize)} most recent publications`
                },
                { label: 'Operator retries', value: formatNumber(outbox.totals.operatorRetries) }
              ]}
            />
            <div className="chart-row">
              <BarChart
                title="Events by type"
                categories={EVENT_TYPES}
                series={[
                  {
                    label: 'Events',
                    tone: 'info',
                    values: EVENT_TYPES.map((type) => outbox.countsByEventType[type] ?? 0)
                  }
                ]}
              />
              <BarChart
                title="Unpublished events by age"
                categories={AGE_BUCKETS.map((bucket) => bucket.label)}
                series={[
                  {
                    label: 'Unpublished',
                    tone: 'warn',
                    values: AGE_BUCKETS.map((bucket) => outbox.unpublishedByAge[bucket.key] ?? 0)
                  }
                ]}
              />
            </div>
          </>
        )}
      </SectionPanel>

      <SectionPanel
        title="Events"
        section={data?.outbox}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        actions={
          <form className="filters" onSubmit={(event) => event.preventDefault()}>
            <label>
              Status
              <select
                value={status}
                onChange={(event) => {
                  setStatus(event.target.value);
                  setPage(0);
                }}
              >
                <option value="">All</option>
                {STATUSES.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Event type
              <select
                value={eventType}
                onChange={(event) => {
                  setEventType(event.target.value);
                  setPage(0);
                }}
              >
                <option value="">All</option>
                {EVENT_TYPES.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Job ID
              <input
                type="search"
                value={aggregateId}
                placeholder="exact job id"
                onChange={(event) => {
                  setAggregateId(event.target.value);
                  setPage(0);
                }}
              />
            </label>
            <label>
              Sort
              <select value={sort} onChange={(event) => setSort(event.target.value)}>
                <option value="createdAt">Created</option>
                <option value="availableAt">Available</option>
                <option value="publishedAt">Published</option>
                <option value="terminalFailedAt">Terminal failure</option>
                <option value="status">Status</option>
              </select>
            </label>
            <label>
              Order
              <select
                value={direction}
                onChange={(event) => setDirection(event.target.value as 'asc' | 'desc')}
              >
                <option value="desc">Newest first</option>
                <option value="asc">Oldest first</option>
              </select>
            </label>
          </form>
        }
      >
        {(outbox) => (
          <>
            <DataTable
              caption="Outbox events"
              columns={columns}
              rows={outbox.events.content}
              rowKey={(row) => row.eventId}
              emptyMessage="No outbox events match these filters."
            />
            <Pagination
              page={outbox.events.page}
              size={outbox.events.size}
              totalElements={outbox.events.totalElements}
              totalPages={outbox.events.totalPages}
              onPage={setPage}
              onSize={(next) => {
                setSize(next);
                setPage(0);
              }}
            />
          </>
        )}
      </SectionPanel>
    </>
  );
}
