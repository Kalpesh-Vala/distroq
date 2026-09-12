import { useState } from 'react';
import { api } from '../api/client';
import type { JobRow, JobsResponse } from '../api/types';
import { usePolling } from '../hooks/usePolling';
import { useRefreshInterval } from '../RefreshContext';
import { SectionPanel } from '../components/SectionPanel';
import { DataTable, type Column } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { StatusBadge } from '../components/StatusBadge';
import { formatNumber, formatTimestamp, shortId, toneForStatus } from '../format';
import { hrefFor } from '../hooks/useHashRoute';

/**
 * A searchable job table with no payload column.
 *
 * <p>The three "waiting for" columns stay separate — `Scheduled at` is what the submitter asked
 * for, `Next attempt at` is when the retry is due, `Started at` is when the current attempt
 * began — because a job that was scheduled and has since failed once has all three and they are
 * different times.
 */
export function JobsPage() {
  const intervalMs = useRefreshInterval();
  const [status, setStatus] = useState('');
  const [priority, setPriority] = useState('');
  const [jobType, setJobType] = useState('');
  const [jobId, setJobId] = useState('');
  const [createdAfter, setCreatedAfter] = useState('');
  const [createdBefore, setCreatedBefore] = useState('');
  const [scheduled, setScheduled] = useState('');
  const [hasAttempts, setHasAttempts] = useState('');
  const [sort, setSort] = useState('createdAt');
  const [direction, setDirection] = useState<'asc' | 'desc'>('desc');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const query = {
    status: status || undefined,
    priority: priority || undefined,
    jobType: jobType.trim() || undefined,
    jobId: jobId.trim() || undefined,
    createdAfter: toInstant(createdAfter),
    createdBefore: toInstant(createdBefore),
    scheduled: triState(scheduled),
    hasAttempts: triState(hasAttempts),
    sort,
    direction,
    page,
    size
  };

  const poll = usePolling<JobsResponse>((signal) => api.jobs(query, { signal }), {
    intervalMs,
    key: JSON.stringify(query)
  });
  const data = poll.data;
  const facets = data?.facets.data;

  const columns: Column<JobRow>[] = [
    {
      key: 'jobId',
      header: 'Job',
      render: (row) => (
        <a href={hrefFor('jobs', row.jobId)} title={row.jobId}>
          {shortId(row.jobId)}
        </a>
      )
    },
    { key: 'type', header: 'Type', render: (row) => row.type },
    { key: 'priority', header: 'Priority', render: (row) => row.priority },
    {
      key: 'status',
      header: 'Status',
      render: (row) => <StatusBadge tone={toneForStatus(row.status)}>{row.status}</StatusBadge>
    },
    { key: 'createdAt', header: 'Created', render: (row) => formatTimestamp(row.createdAt) },
    {
      key: 'scheduledAt',
      header: 'Scheduled at',
      hint: 'requested time',
      render: (row) => formatTimestamp(row.scheduledAt)
    },
    { key: 'startedAt', header: 'Started', render: (row) => formatTimestamp(row.startedAt) },
    { key: 'finishedAt', header: 'Finished', render: (row) => formatTimestamp(row.finishedAt) },
    {
      key: 'attempts',
      header: 'Attempts',
      numeric: true,
      render: (row) => `${formatNumber(row.attemptCount)} / ${formatNumber(row.maxAttempts)}`
    },
    {
      key: 'replayCount',
      header: 'Replays',
      numeric: true,
      render: (row) => (row.replayCount === null ? '—' : formatNumber(row.replayCount))
    },
    {
      key: 'executionOwner',
      header: 'Execution owner',
      render: (row) => row.executionOwner ?? '—'
    },
    {
      key: 'executionLeaseUntil',
      header: 'Lease until',
      render: (row) => formatTimestamp(row.executionLeaseUntil)
    }
  ];

  return (
    <>
      <h2 className="page-title">Jobs</h2>
      <p className="notice notice--muted">
        Job payloads are not shown. Open a job for its lifecycle, attempts, outbox events and
        effect ledger entries.
      </p>

      <SectionPanel
        title="Job search"
        section={data?.jobs}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        actions={
          <form className="filters" onSubmit={(event) => event.preventDefault()}>
            <label>
              Status
              <select value={status} onChange={reset(setStatus)}>
                <option value="">All</option>
                {(facets?.statuses ?? []).map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Priority
              <select value={priority} onChange={reset(setPriority)}>
                <option value="">All</option>
                {(facets?.priorities ?? []).map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Job type
              <select value={jobType} onChange={reset(setJobType)}>
                <option value="">All</option>
                {(facets?.jobTypes ?? []).map((option) => (
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
                value={jobId}
                placeholder="exact job id"
                onChange={reset(setJobId)}
              />
            </label>
            <label>
              Created after
              <input type="datetime-local" value={createdAfter} onChange={reset(setCreatedAfter)} />
            </label>
            <label>
              Created before
              <input
                type="datetime-local"
                value={createdBefore}
                onChange={reset(setCreatedBefore)}
              />
            </label>
            <label>
              Scheduled
              <select value={scheduled} onChange={reset(setScheduled)}>
                <option value="">Any</option>
                <option value="true">Scheduled only</option>
                <option value="false">Immediate only</option>
              </select>
            </label>
            <label>
              Has attempts
              <select value={hasAttempts} onChange={reset(setHasAttempts)}>
                <option value="">Any</option>
                <option value="true">Attempted</option>
                <option value="false">Never attempted</option>
              </select>
            </label>
            <label>
              Sort
              <select value={sort} onChange={(event) => setSort(event.target.value)}>
                <option value="createdAt">Created</option>
                <option value="scheduledAt">Scheduled</option>
                <option value="startedAt">Started</option>
                <option value="finishedAt">Finished</option>
                <option value="updatedAt">Updated</option>
                <option value="priority">Priority</option>
                <option value="status">Status</option>
                <option value="type">Type</option>
                <option value="attemptCount">Attempts</option>
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
            <button type="button" onClick={poll.refresh}>
              Refresh
            </button>
          </form>
        }
      >
        {(jobs) => (
          <>
            <DataTable
              caption="Jobs"
              columns={columns}
              rows={jobs.content}
              rowKey={(row) => row.jobId}
              emptyMessage="No jobs match these filters."
            />
            <Pagination
              page={jobs.page}
              size={jobs.size}
              totalElements={jobs.totalElements}
              totalPages={jobs.totalPages}
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

  function reset(setter: (value: string) => void) {
    return (event: { target: { value: string } }) => {
      setter(event.target.value);
      setPage(0);
    };
  }
}

/** `datetime-local` has no zone. The dashboard is UTC everywhere, so it is read as UTC. */
function toInstant(local: string): string | undefined {
  if (!local) {
    return undefined;
  }
  return `${local.length === 16 ? `${local}:00` : local}Z`;
}

function triState(value: string): boolean | undefined {
  if (value === 'true') return true;
  if (value === 'false') return false;
  return undefined;
}
