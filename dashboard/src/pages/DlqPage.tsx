import { useState } from 'react';
import { api } from '../api/client';
import type { DlqResponse } from '../api/types';
import { usePolling } from '../hooks/usePolling';
import { useRefreshInterval } from '../RefreshContext';
import { SectionPanel } from '../components/SectionPanel';
import { DataTable } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { KeyValue } from '../components/KeyValue';
import { BarChart } from '../components/BarChart';
import { StatusBadge } from '../components/StatusBadge';
import { formatDuration, formatNumber, formatTimestamp, shortId, toneForStatus } from '../format';
import { hrefFor } from '../hooks/useHashRoute';

/**
 * Dead-lettered jobs, and a note saying where replay lives.
 *
 * <p>There is no replay button and no replay affordance of any kind. Replay is a mutation with a
 * reason header and an audit row behind it; it stays on `POST /api/jobs/{id}/retry`, and the note
 * on this page says so rather than leaving an operator hunting for a control that was
 * deliberately not built.
 */
export function DlqPage() {
  const intervalMs = useRefreshInterval();
  const [replayed, setReplayed] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const query = { replayed: triState(replayed), page, size };
  const poll = usePolling<DlqResponse>((signal) => api.dlq(query, { signal }), {
    intervalMs,
    key: JSON.stringify(query)
  });
  const data = poll.data;

  return (
    <>
      <h2 className="page-title">Dead-letter queue</h2>
      <p className="notice notice--warn" role="status">
        {data?.note ??
          'DLQ replay is available through the existing administrative API, not through this read-only dashboard phase.'}
      </p>

      <SectionPanel
        title="Totals"
        section={data?.dlq}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
      >
        {(dlq) => (
          <>
            <KeyValue
              items={[
                {
                  label: 'Dead-lettered and not replayed',
                  value: (
                    <StatusBadge tone={dlq.totals.deadLettered > 0 ? 'error' : 'ok'}>
                      {formatNumber(dlq.totals.deadLettered)}
                    </StatusBadge>
                  )
                },
                { label: 'Jobs replayed at least once', value: formatNumber(dlq.totals.replayedJobs) },
                { label: 'Total replays', value: formatNumber(dlq.totals.totalReplays) },
                {
                  label: 'Oldest dead-lettered job',
                  value:
                    dlq.totals.oldestDeadLetteredAt === null
                      ? 'None'
                      : `${formatTimestamp(dlq.totals.oldestDeadLetteredAt)} (${formatDuration(
                          dlq.totals.oldestAgeMs
                        )} ago)`
                }
              ]}
            />
            <div className="chart-row">
              <BarChart
                title="Dead letters by priority"
                categories={Object.keys(dlq.countsByPriority)}
                series={[
                  {
                    label: 'Dead letters',
                    tone: 'error',
                    values: Object.values(dlq.countsByPriority)
                  }
                ]}
              />
              <BarChart
                title="Dead letters by job type"
                categories={Object.keys(dlq.countsByJobType)}
                series={[
                  {
                    label: 'Dead letters',
                    tone: 'error',
                    values: Object.values(dlq.countsByJobType)
                  }
                ]}
              />
              <BarChart
                title="Dead letters over time"
                categories={dlq.byDay.map((row) => row.day)}
                series={[
                  { label: 'Dead letters', tone: 'error', values: dlq.byDay.map((row) => row.count) }
                ]}
              />
            </div>
            {dlq.sampled ? (
              <p className="notice notice--muted">
                The breakdowns above are built from the most recent sample of dead letters rather
                than the whole table, and the sample is full — older entries are not included.
              </p>
            ) : null}
          </>
        )}
      </SectionPanel>

      <SectionPanel
        title="Entries"
        section={data?.dlq}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        actions={
          <form className="filters" onSubmit={(event) => event.preventDefault()}>
            <label>
              Replayed
              <select
                value={replayed}
                onChange={(event) => {
                  setReplayed(event.target.value);
                  setPage(0);
                }}
              >
                <option value="">All</option>
                <option value="false">Not replayed</option>
                <option value="true">Replayed</option>
              </select>
            </label>
          </form>
        }
      >
        {(dlq) => (
          <>
            <DataTable
              caption="Dead-lettered jobs"
              columns={[
                {
                  key: 'jobId',
                  header: 'Job',
                  render: (row) => (
                    <a href={hrefFor('jobs', row.jobId)} title={row.jobId}>
                      {shortId(row.jobId)}
                    </a>
                  )
                },
                { key: 'jobType', header: 'Type', render: (row) => row.jobType ?? 'No data' },
                { key: 'priority', header: 'Priority', render: (row) => row.priority ?? 'No data' },
                { key: 'movedAt', header: 'Moved at', render: (row) => formatTimestamp(row.movedAt) },
                {
                  key: 'attemptCount',
                  header: 'Attempts',
                  numeric: true,
                  render: (row) => formatNumber(row.attemptCount)
                },
                {
                  key: 'replayCount',
                  header: 'Replays',
                  numeric: true,
                  render: (row) => formatNumber(row.replayCount)
                },
                {
                  key: 'scheduledAt',
                  header: 'Scheduled at',
                  render: (row) => formatTimestamp(row.scheduledAt)
                },
                {
                  key: 'status',
                  header: 'Status',
                  render: (row) =>
                    row.status ? (
                      <StatusBadge tone={toneForStatus(row.status)}>{row.status}</StatusBadge>
                    ) : (
                      'No data'
                    )
                },
                {
                  key: 'finalError',
                  header: 'Final error',
                  render: (row) => <span className="error-cell">{row.finalError ?? '—'}</span>
                }
              ]}
              rows={dlq.entries.content}
              rowKey={(row) => row.jobId}
              emptyMessage="No dead-lettered jobs."
            />
            <Pagination
              page={dlq.entries.page}
              size={dlq.entries.size}
              totalElements={dlq.entries.totalElements}
              totalPages={dlq.entries.totalPages}
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

function triState(value: string): boolean | undefined {
  if (value === 'true') return true;
  if (value === 'false') return false;
  return undefined;
}
