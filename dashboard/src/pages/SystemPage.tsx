import { api } from '../api/client';
import type { SystemResponse } from '../api/types';
import { usePolling } from '../hooks/usePolling';
import { useRefreshInterval } from '../RefreshContext';
import { SectionPanel } from '../components/SectionPanel';
import { KeyValue } from '../components/KeyValue';
import { DataTable } from '../components/DataTable';
import { StatusBadge } from '../components/StatusBadge';
import { formatDuration, formatTimestamp, toneForStatus } from '../format';

/**
 * What this build is and where it is running.
 *
 * <p>The list of things not here is as deliberate as the list of things that are. No datasource
 * URL, no Redis host, no connection string, no filesystem path, no token: a JDBC URL carries a
 * username and often a password, and this page is the one most likely to be screenshotted into a
 * ticket.
 */
export function SystemPage() {
  const intervalMs = useRefreshInterval();
  const poll = usePolling<SystemResponse>((signal) => api.system({ signal }), { intervalMs });
  const data = poll.data;

  return (
    <>
      <h2 className="page-title">System</h2>

      <SectionPanel
        title="Build and runtime"
        section={data?.system}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
      >
        {(system) => (
          <KeyValue
            items={[
              { label: 'Application version', value: system.version ?? 'No data' },
              { label: 'Git commit', value: system.commit ?? 'No data' },
              { label: 'Git branch', value: system.branch ?? 'No data' },
              { label: 'Release tags', value: system.tags || 'None' },
              { label: 'Build time', value: formatTimestamp(system.buildTime) },
              { label: 'Commit time', value: formatTimestamp(system.commitTime) },
              { label: 'Environment profile', value: system.environment ?? 'No data' },
              { label: 'Instance ID', value: system.instanceId ?? 'No data' },
              { label: 'JVM version', value: system.javaVersion ?? 'No data' },
              { label: 'JVM', value: system.javaVendorVersion ?? 'No data' },
              { label: 'Spring Boot version', value: system.springBootVersion ?? 'No data' },
              { label: 'Redis version', value: system.redisVersion ?? 'Unavailable' },
              { label: 'PostgreSQL version', value: system.postgresVersion ?? 'Unavailable' },
              {
                label: 'Flyway schema version',
                value: system.flywayVersion
                  ? `${system.flywayVersion}${
                      system.flywayDescription ? ` — ${system.flywayDescription}` : ''
                    }`
                  : 'No data'
              },
              {
                label: 'Pending migrations',
                value:
                  system.flywayPendingMigrations === null
                    ? 'No data'
                    : String(system.flywayPendingMigrations)
              }
            ]}
          />
        )}
      </SectionPanel>

      <SectionPanel
        title="Availability"
        section={data?.system}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
      >
        {(system) => (
          <KeyValue
            items={[
              {
                label: 'Readiness',
                value: (
                  <StatusBadge tone={toneForStatus(system.readiness)}>
                    {system.readiness ?? 'No data'}
                  </StatusBadge>
                )
              },
              {
                label: 'Liveness',
                value: (
                  <StatusBadge tone={toneForStatus(system.liveness)}>
                    {system.liveness ?? 'No data'}
                  </StatusBadge>
                )
              },
              { label: 'Started at', value: formatTimestamp(system.startedAt) },
              { label: 'Uptime', value: formatDuration(system.uptimeMs) },
              {
                label: 'Last successful background cycle',
                value: system.lastSuccessfulHealthCheckAt
                  ? formatTimestamp(system.lastSuccessfulHealthCheckAt)
                  : 'No successful cycle yet'
              },
              {
                label: 'Dashboard refresh interval (server default)',
                value: formatDuration(system.refreshIntervalMs)
              },
              {
                label: 'Dashboard authentication',
                value: system.authenticationRequired ? (
                  <StatusBadge tone="ok">Bearer token required</StatusBadge>
                ) : (
                  <StatusBadge tone="error">
                    Not enforced — no administrative token is configured
                  </StatusBadge>
                )
              }
            ]}
          />
        )}
      </SectionPanel>

      <SectionPanel
        title="Health indicators"
        section={data?.health}
        loading={poll.loading}
        unauthorized={poll.unauthorized}
        stale={poll.stale}
        isEmpty={(health) => health.components.length === 0}
        emptyMessage="No health indicators are registered."
        description="Indicator details are allowlisted before they leave the server; raw failure messages are never included."
      >
        {(health) => (
          <DataTable
            caption="Health indicators"
            columns={[
              { key: 'name', header: 'Component', render: (row) => row.name },
              {
                key: 'status',
                header: 'Status',
                render: (row) => (
                  <StatusBadge tone={toneForStatus(row.status)}>{row.status}</StatusBadge>
                )
              },
              { key: 'detail', header: 'Detail', render: (row) => row.detail ?? '—' }
            ]}
            rows={health.components}
            rowKey={(row) => row.name}
          />
        )}
      </SectionPanel>
    </>
  );
}
