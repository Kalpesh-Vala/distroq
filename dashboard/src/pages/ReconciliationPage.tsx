import { api } from '../api/client';
import type { FindingRow, ReconciliationResponse } from '../api/types';
import { usePolling } from '../hooks/usePolling';
import { useRefreshInterval } from '../RefreshContext';
import { SectionPanel } from '../components/SectionPanel';
import { DataTable, type Column } from '../components/DataTable';
import { KeyValue } from '../components/KeyValue';
import { StatusBadge } from '../components/StatusBadge';
import { BarChart } from '../components/BarChart';
import { formatBoolean, formatDuration, formatNumber, formatTimestamp, toneForSeverity } from '../format';

const CATEGORIES = ['OUTBOX', 'SCHEDULED_JOBS', 'RETRIES', 'EXECUTION_LEASES', 'EFFECTS'];

/**
 * What reconciliation found, and an explicit statement that nothing was done about it.
 *
 * <p>The most dangerous way to read this page is to assume something is fixing these. It is not:
 * the dashboard runs a preview, every finding is REPORTED, and a deployment with auto-repair off
 * has nothing else repairing them either. The banner and the `Preview mode` /
 * `Auto-repair disabled` / `Operator action required` badges are there to say so before an
 * operator scrolls past the numbers.
 */
export function ReconciliationPage() {
  const intervalMs = useRefreshInterval();
  const poll = usePolling<ReconciliationResponse>((signal) => api.reconciliation({ signal }), {
    intervalMs
  });
  const data = poll.data;

  const columns: Column<FindingRow>[] = [
    { key: 'findingType', header: 'Finding', render: (row) => row.findingType },
    {
      key: 'severity',
      header: 'Severity',
      render: (row) => <StatusBadge tone={toneForSeverity(row.severity)}>{row.severity}</StatusBadge>
    },
    { key: 'category', header: 'Category', render: (row) => row.category },
    { key: 'targetType', header: 'Target type', render: (row) => row.targetType },
    { key: 'targetId', header: 'Target', render: (row) => <span title={row.targetId}>{row.targetId}</span> },
    { key: 'description', header: 'Description', render: (row) => row.description },
    {
      key: 'repairable',
      header: 'Repairable',
      render: (row) =>
        row.repairable ? (
          <StatusBadge tone="info">Auto-repairable</StatusBadge>
        ) : (
          <StatusBadge tone="warn">Needs a decision</StatusBadge>
        )
    },
    { key: 'resolution', header: 'Resolution', render: (row) => row.resolution }
  ];

  return (
    <>
      <h2 className="page-title">Reconciliation</h2>
      <p className="notice notice--warn" role="status">
        {data?.warning ?? 'The dashboard reports reconciliation findings but does not repair them.'}
      </p>

      <SectionPanel
        title="Latest run"
        section={data?.reconciliation}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
      >
        {(view) => (
          <>
            <p className="badge-row">
              <StatusBadge tone="info">Preview mode</StatusBadge>
              {view.configuration.autoRepairAllowed ? (
                <StatusBadge tone="warn">Auto-repair allowed by configuration</StatusBadge>
              ) : (
                <StatusBadge tone="warn">Auto-repair disabled</StatusBadge>
              )}
              {view.summary.unresolved > 0 ? (
                <StatusBadge tone="error">Operator action required</StatusBadge>
              ) : (
                <StatusBadge tone="ok">No unresolved findings</StatusBadge>
              )}
              {view.summary.skippedBecauseAnotherRunHoldsTheLock ? (
                <StatusBadge tone="unknown">Another instance held the lock</StatusBadge>
              ) : null}
            </p>
            <KeyValue
              items={[
                { label: 'Generated at', value: formatTimestamp(view.generatedAt) },
                { label: 'Run started', value: formatTimestamp(view.summary.startedAt) },
                { label: 'Run duration', value: formatDuration(view.summary.durationMs) },
                {
                  label: 'Findings inspected',
                  value: `${formatNumber(view.summary.inspected)} of a batch of ${formatNumber(
                    view.summary.batchSize
                  )}${view.summary.batchFull ? ' — batch full, there is probably more' : ''}`
                },
                { label: 'Findings', value: formatNumber(view.summary.findings) },
                { label: 'Repairs performed', value: formatNumber(view.summary.repaired) },
                { label: 'Findings skipped', value: formatNumber(view.summary.skipped) },
                { label: 'Findings failed', value: formatNumber(view.summary.failed) },
                { label: 'Unresolved', value: formatNumber(view.summary.unresolved) }
              ]}
            />
          </>
        )}
      </SectionPanel>

      <SectionPanel
        title="Configuration"
        section={data?.reconciliation}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
      >
        {(view) => (
          <KeyValue
            items={[
              { label: 'Enabled', value: formatBoolean(view.configuration.enabled) },
              { label: 'Auto-repair', value: formatBoolean(view.configuration.autoRepairAllowed) },
              {
                label: 'Re-arm terminal outbox events',
                value: formatBoolean(view.configuration.requeueFailedOutbox)
              },
              { label: 'Batch size', value: formatNumber(view.configuration.batchSize) },
              { label: 'Poll interval', value: formatDuration(view.configuration.pollIntervalMs) },
              {
                label: 'Stale scheduled threshold',
                value: formatDuration(view.configuration.staleScheduledAfterMs)
              },
              {
                label: 'Stale outbox threshold',
                value: formatDuration(view.configuration.staleOutboxAfterMs)
              },
              {
                label: 'Stale lease threshold',
                value: formatDuration(view.configuration.staleLeaseAfterMs)
              }
            ]}
          />
        )}
      </SectionPanel>

      <SectionPanel
        title="Findings"
        section={data?.reconciliation}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(view) => view.findings.length === 0}
        emptyMessage="Reconciliation found nothing to report."
      >
        {(view) => (
          <>
            <div className="chart-row">
              <BarChart
                title="Findings by category"
                categories={CATEGORIES}
                series={[
                  {
                    label: 'Findings',
                    tone: 'warn',
                    values: CATEGORIES.map((category) => view.countsByCategory[category] ?? 0)
                  }
                ]}
              />
              <BarChart
                title="Findings by severity"
                categories={['ERROR', 'WARNING', 'INFO']}
                series={[
                  {
                    label: 'Findings',
                    tone: 'error',
                    values: ['ERROR', 'WARNING', 'INFO'].map(
                      (severity) => view.countsBySeverity[severity] ?? 0
                    )
                  }
                ]}
              />
            </div>
            <DataTable
              caption="Reconciliation findings"
              columns={columns}
              rows={view.findings}
              rowKey={(row) => `${row.findingType}:${row.targetId}`}
            />
          </>
        )}
      </SectionPanel>
    </>
  );
}
