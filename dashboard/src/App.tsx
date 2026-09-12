import { useEffect, useState } from 'react';
import { TokenGate } from './components/TokenGate';
import { Nav } from './components/Nav';
import { RefreshControl } from './components/RefreshControl';
import { RefreshContext } from './RefreshContext';
import { useHashRoute } from './hooks/useHashRoute';
import { usePolling } from './hooks/usePolling';
import { api, ApiError } from './api/client';
import { clearToken, hasToken, subscribeToToken } from './api/token';
import { OverviewPage } from './pages/OverviewPage';
import { QueuesPage } from './pages/QueuesPage';
import { WorkersPage } from './pages/WorkersPage';
import { OutboxPage } from './pages/OutboxPage';
import { ReconciliationPage } from './pages/ReconciliationPage';
import { JobsPage } from './pages/JobsPage';
import { JobDetailPage } from './pages/JobDetailPage';
import { DlqPage } from './pages/DlqPage';
import { AnalyticsPage } from './pages/AnalyticsPage';
import { SystemPage } from './pages/SystemPage';

/**
 * The shell: authentication, navigation, the shared refresh interval, and a system poll that
 * doubles as the connection indicator in the header.
 *
 * <p>Signing out clears the token and nothing else. There is no server-side session to end,
 * because there is no server-side session — the token is a shared credential and the honest thing
 * to do is forget it and say so.
 */
export function App() {
  const [authenticated, setAuthenticated] = useState(hasToken);
  const [intervalMs, setIntervalMs] = useState(5_000);
  const [rejected, setRejected] = useState<string | null>(null);
  const route = useHashRoute();

  useEffect(() => subscribeToToken(() => setAuthenticated(hasToken())), []);

  // the header's own poll. It is also the probe that decides whether the token still works, which
  // is why a 401 here sends the whole application back to the gate rather than leaving nine
  // panels each showing their own permission error.
  const system = usePolling((signal) => api.system({ signal }), {
    intervalMs,
    enabled: authenticated
  });

  useEffect(() => {
    if (system.error instanceof ApiError && system.error.unauthorized) {
      setRejected(system.error.message);
      clearToken();
    }
  }, [system.error]);

  if (!authenticated) {
    return <TokenGate reason={rejected} />;
  }

  const environment = system.data?.system.data?.environment ?? null;
  const version = system.data?.system.data?.version ?? null;

  return (
    <RefreshContext.Provider value={intervalMs}>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="masthead">
        <div className="masthead__identity">
          <h1>DistroQ operations</h1>
          <p className="masthead__meta">
            {version ? `v${version}` : 'version unavailable'}
            {environment ? ` · ${environment}` : ''} · read-only
          </p>
        </div>
        <RefreshControl
          intervalMs={intervalMs}
          onIntervalChange={setIntervalMs}
          onRefresh={system.refresh}
          refreshing={system.refreshing}
          lastUpdatedAt={system.lastUpdatedAt}
          stale={system.stale}
          consecutiveFailures={system.consecutiveFailures}
        />
        <button
          type="button"
          className="masthead__signout"
          onClick={() => {
            setRejected(null);
            clearToken();
          }}
        >
          Sign out
        </button>
      </header>
      <Nav current={route.section} />
      <main id="main" className="content">
        {page()}
      </main>
      <footer className="footer">
        <p>
          This dashboard is read-only. Retry, replay, repair and cancellation remain on the
          administrative API, where the reason header and the audit trail are.
        </p>
      </footer>
    </RefreshContext.Provider>
  );

  function page() {
    switch (route.section) {
      case 'queues':
        return <QueuesPage />;
      case 'workers':
        return <WorkersPage />;
      case 'outbox':
        return <OutboxPage />;
      case 'reconciliation':
        return <ReconciliationPage />;
      case 'jobs':
        return route.jobId ? <JobDetailPage jobId={route.jobId} /> : <JobsPage />;
      case 'dlq':
        return <DlqPage />;
      case 'analytics':
        return <AnalyticsPage />;
      case 'system':
        return <SystemPage />;
      default:
        return <OverviewPage />;
    }
  }
}
