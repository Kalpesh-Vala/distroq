import { useState } from 'react';
import { api } from '../api/client';
import type { AnalyticsResponse, AnalyticsRow } from '../api/types';
import { usePolling } from '../hooks/usePolling';
import { MANUAL } from '../hooks/usePolling';
import { SectionPanel } from '../components/SectionPanel';
import { DataTable, type Column } from '../components/DataTable';
import { KeyValue } from '../components/KeyValue';
import { BarChart } from '../components/BarChart';
import { StatusBadge } from '../components/StatusBadge';
import { formatNumber, formatTimestamp } from '../format';

/**
 * The v0.9 export, rendered.
 *
 * <p>Nothing on this page runs Spark and nothing queries PostgreSQL for an aggregate: the numbers
 * were computed once, offline, against a read-only connection, and the server reads the files.
 *
 * <p>It also polls far more slowly than the rest of the dashboard — a batch export does not change
 * between two five-second ticks, and re-reading it at that rate would be a pointless cost. The
 * provenance block above the charts states the window, the generation time, the versions, and in
 * plain words that this is historical rather than live, because a bar chart of yesterday's success
 * rate looks exactly like a bar chart of this minute's.
 */

const ANALYTICS_INTERVAL_MS = 60_000;

export function AnalyticsPage() {
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [priority, setPriority] = useState('');
  const [jobType, setJobType] = useState('');
  const [run, setRun] = useState('');
  const [live, setLive] = useState(true);

  const query = { run, startDate, endDate, priority, jobType };
  const poll = usePolling<AnalyticsResponse>(
    (signal) =>
      api.analytics(
        {
          run: run || undefined,
          startDate: startDate || undefined,
          endDate: endDate || undefined,
          priority: priority || undefined,
          jobType: jobType || undefined
        },
        { signal }
      ),
    { intervalMs: live ? ANALYTICS_INTERVAL_MS : MANUAL, key: JSON.stringify(query) }
  );

  const data = poll.data;
  const view = data?.analytics.data ?? null;
  const daily = view?.reports.daily_job_summary ?? [];
  const byPriority = view?.reports.priority_summary ?? [];
  const byJobType = view?.reports.job_type_summary ?? [];
  const outbox = view?.reports.outbox_summary ?? [];
  const effects = view?.reports.effect_summary ?? [];
  const reliability = view?.reports.reliability_summary ?? [];
  const days = uniqueDays(daily);

  return (
    <>
      <h2 className="page-title">Analytics</h2>
      <p className="notice notice--muted">
        Batch aggregates from the v0.9 export. Historical, not live, and never recomputed by
        opening this page.
      </p>

      <SectionPanel
        title="Report provenance"
        section={data?.analytics}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        actions={
          <form className="filters" onSubmit={(event) => event.preventDefault()}>
            <label>
              Export window
              <select value={run} onChange={(event) => setRun(event.target.value)}>
                <option value="">Most recent</option>
                {(view?.availableRuns ?? []).map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Start date
              <input
                type="date"
                value={startDate}
                onChange={(event) => setStartDate(event.target.value)}
              />
            </label>
            <label>
              End date
              <input
                type="date"
                value={endDate}
                onChange={(event) => setEndDate(event.target.value)}
              />
            </label>
            <label>
              Priority
              <select value={priority} onChange={(event) => setPriority(event.target.value)}>
                <option value="">All</option>
                <option value="HIGH">HIGH</option>
                <option value="NORMAL">NORMAL</option>
                <option value="LOW">LOW</option>
              </select>
            </label>
            <label>
              Job type
              <select value={jobType} onChange={(event) => setJobType(event.target.value)}>
                <option value="">All</option>
                {jobTypesIn(byJobType).map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </label>
            <label className="filters__check">
              <input
                type="checkbox"
                checked={live}
                onChange={(event) => setLive(event.target.checked)}
              />
              Refresh every minute
            </label>
            <button type="button" onClick={poll.refresh}>
              Reload report
            </button>
          </form>
        }
      >
        {(analytics) => (
          <>
            <p className="badge-row">
              <StatusBadge tone="info">Historical</StatusBadge>
              <StatusBadge tone="warn">Not live</StatusBadge>
              <StatusBadge tone="info">Batch aggregate</StatusBadge>
            </p>
            <KeyValue
              items={[
                { label: 'Export window', value: analytics.runId },
                {
                  label: 'Source window',
                  value: `${formatTimestamp(analytics.windowStart)} → ${formatTimestamp(
                    analytics.windowEnd
                  )}`
                },
                { label: 'Report generated at', value: formatTimestamp(analytics.generatedAt) },
                { label: 'Data extracted at', value: formatTimestamp(analytics.exportedAt) },
                { label: 'Analytics version', value: analytics.analyticsVersion ?? 'No data' },
                {
                  label: 'Application version at export',
                  value: analytics.applicationVersion ?? 'No data'
                },
                { label: 'Basis', value: analytics.basis },
                {
                  label: 'Data quality findings',
                  value: String(
                    (analytics.dataQuality as { total_findings?: number }).total_findings ??
                      'No data'
                  )
                }
              ]}
            />
          </>
        )}
      </SectionPanel>

      <SectionPanel
        title="Headline"
        section={data?.analytics}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(analytics) => Object.keys(analytics.headline).length === 0}
        emptyMessage="This export contains no headline summary."
      >
        {(analytics) => (
          <KeyValue
            items={Object.entries(analytics.headline).map(([label, value]) => ({
              label: humanise(label),
              value: typeof value === 'number' ? formatNumber(value) : String(value ?? 'No data')
            }))}
          />
        )}
      </SectionPanel>

      <SectionPanel
        title="Daily job summary"
        section={data?.analytics}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={() => daily.length === 0}
        emptyMessage="No daily rows match these filters."
      >
        {() => (
          <>
            <BarChart
              title="Jobs per day"
              categories={days}
              series={[
                { label: 'Submitted', tone: 'info', values: sumByDay(daily, days, 'submitted_jobs') },
                { label: 'Started', tone: 'info', values: sumByDay(daily, days, 'started_jobs') },
                { label: 'Succeeded', tone: 'ok', values: sumByDay(daily, days, 'succeeded_jobs') },
                { label: 'Failed', tone: 'warn', values: sumByDay(daily, days, 'failed_jobs') },
                {
                  label: 'Dead-lettered',
                  tone: 'error',
                  values: sumByDay(daily, days, 'dead_lettered_jobs')
                }
              ]}
            />
            <BarChart
              title="Duration percentiles per day (ms)"
              categories={days}
              series={[
                { label: 'p50', tone: 'ok', values: maxByDay(daily, days, 'p50_duration_ms') },
                { label: 'p95', tone: 'warn', values: maxByDay(daily, days, 'p95_duration_ms') },
                { label: 'p99', tone: 'error', values: maxByDay(daily, days, 'p99_duration_ms') }
              ]}
            />
            <BarChart
              title="Queue and schedule delay per day (ms)"
              categories={days}
              series={[
                {
                  label: 'Average queue delay',
                  tone: 'info',
                  values: maxByDay(daily, days, 'average_queue_delay_ms')
                },
                {
                  label: 'Average schedule delay',
                  tone: 'warn',
                  values: maxByDay(daily, days, 'average_schedule_delay_ms')
                }
              ]}
            />
            {table('Daily job summary', daily)}
          </>
        )}
      </SectionPanel>

      <SectionPanel
        title="By priority"
        section={data?.analytics}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={() => byPriority.length === 0}
        emptyMessage="No priority rows match these filters."
      >
        {() => table('Priority summary', byPriority)}
      </SectionPanel>

      <SectionPanel
        title="By job type"
        section={data?.analytics}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={() => byJobType.length === 0}
        emptyMessage="No job-type rows match these filters."
      >
        {() => table('Job type summary', byJobType)}
      </SectionPanel>

      <SectionPanel
        title="Outbox publication"
        section={data?.analytics}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={() => outbox.length === 0}
        emptyMessage="No outbox rows match these filters."
      >
        {() => table('Outbox summary', outbox)}
      </SectionPanel>

      <SectionPanel
        title="Effects"
        section={data?.analytics}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={() => effects.length === 0}
        emptyMessage="No effect rows match these filters."
      >
        {() => table('Effect summary', effects)}
      </SectionPanel>

      <SectionPanel
        title="Reconciliation actions"
        section={data?.analytics}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={() => reliability.length === 0}
        emptyMessage="No reconciliation rows match these filters."
      >
        {() => table('Reliability summary', reliability)}
      </SectionPanel>
    </>
  );
}

/** Report shapes come from the pipeline, so the columns are whatever the CSV header said. */
function table(caption: string, rows: AnalyticsRow[]) {
  if (rows.length === 0) {
    return (
      <p className="notice notice--muted" role="status">
        No rows.
      </p>
    );
  }
  const columns: Column<AnalyticsRow>[] = Object.keys(rows[0]).map((key) => ({
    key,
    header: humanise(key),
    numeric: typeof rows[0][key] === 'number',
    render: (row) => cell(row[key])
  }));
  return (
    <DataTable
      caption={caption}
      columns={columns}
      rows={rows}
      rowKey={(row) => Object.values(row).join('|')}
    />
  );
}

/** Null renders as "No data" rather than 0: a missing percentile is not a fast day. */
function cell(value: string | number | null): string {
  if (value === null || value === undefined) {
    return 'No data';
  }
  return typeof value === 'number' ? formatNumber(value) : value;
}

function uniqueDays(rows: AnalyticsRow[]): string[] {
  return [...new Set(rows.map((row) => String(row.day)))].sort();
}

function sumByDay(rows: AnalyticsRow[], days: string[], column: string): (number | null)[] {
  return days.map((day) => {
    const matching = rows.filter((row) => String(row.day) === day);
    const values = matching
      .map((row) => row[column])
      .filter((value): value is number => typeof value === 'number');
    return values.length === 0 ? null : values.reduce((total, value) => total + value, 0);
  });
}

/** Percentiles do not add up, so the tiers are combined by taking the worst rather than summing. */
function maxByDay(rows: AnalyticsRow[], days: string[], column: string): (number | null)[] {
  return days.map((day) => {
    const values = rows
      .filter((row) => String(row.day) === day)
      .map((row) => row[column])
      .filter((value): value is number => typeof value === 'number');
    return values.length === 0 ? null : Math.max(...values);
  });
}

function jobTypesIn(rows: AnalyticsRow[]): string[] {
  return [...new Set(rows.map((row) => String(row.job_type)))].filter(
    (value) => value && value !== 'undefined'
  );
}

function humanise(key: string): string {
  return key.replace(/_/g, ' ').replace(/^./, (character) => character.toUpperCase());
}
