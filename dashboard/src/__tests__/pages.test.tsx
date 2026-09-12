import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { OverviewPage } from '../pages/OverviewPage';
import { QueuesPage } from '../pages/QueuesPage';
import { WorkersPage } from '../pages/WorkersPage';
import { OutboxPage } from '../pages/OutboxPage';
import { ReconciliationPage } from '../pages/ReconciliationPage';
import { JobsPage } from '../pages/JobsPage';
import { JobDetailPage } from '../pages/JobDetailPage';
import { DlqPage } from '../pages/DlqPage';
import { AnalyticsPage } from '../pages/AnalyticsPage';
import { SystemPage } from '../pages/SystemPage';
import { Nav } from '../components/Nav';
import * as fixtures from './fixtures';

/**
 * Page-level behaviour, with the API mocked.
 *
 * <p>These are the tests that pin the promises the README makes: no payloads, no repair controls,
 * no replay button, an unavailable panel that says so rather than showing zero, and labels that
 * keep the Redis counts apart.
 */

const mocked = vi.hoisted(() => ({
  overview: vi.fn(),
  queues: vi.fn(),
  workers: vi.fn(),
  outbox: vi.fn(),
  reconciliation: vi.fn(),
  jobs: vi.fn(),
  job: vi.fn(),
  dlq: vi.fn(),
  analytics: vi.fn(),
  system: vi.fn(),
  activity: vi.fn()
}));

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: mocked
}));

beforeEach(() => {
  Object.values(mocked).forEach((fn) => fn.mockReset());
  mocked.overview.mockResolvedValue(fixtures.overview());
  mocked.queues.mockResolvedValue(fixtures.queues());
  mocked.workers.mockResolvedValue(fixtures.workers());
  mocked.outbox.mockResolvedValue(fixtures.outbox());
  mocked.reconciliation.mockResolvedValue(fixtures.reconciliation());
  mocked.jobs.mockResolvedValue(fixtures.jobs());
  mocked.job.mockResolvedValue(fixtures.jobDetail());
  mocked.dlq.mockResolvedValue(fixtures.dlq());
  mocked.analytics.mockResolvedValue(fixtures.analytics());
  mocked.system.mockResolvedValue(fixtures.system());
});

describe('Overview page', () => {
  it('shows a loading state before the first response', () => {
    mocked.overview.mockImplementation(() => new Promise(() => undefined));

    render(<OverviewPage />);

    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });

  it('renders every required card', async () => {
    render(<OverviewPage />);

    await screen.findByText('Application readiness');
    [
      'Application readiness',
      'Application liveness',
      'PostgreSQL health',
      'Redis health',
      'Worker concurrency',
      'Active workers',
      'Active leases',
      'Scheduled jobs',
      'Delayed retries',
      'Pending Stream entries',
      'Outbox pending',
      'Outbox terminal failures',
      'Reconciliation findings',
      'Dead-lettered jobs'
    ].forEach((label) => expect(screen.getByText(label)).toBeInTheDocument());
  });

  it('shows a real zero as zero and an unavailable panel as unavailable', async () => {
    render(<OverviewPage />);
    await screen.findByText('Outbox pending');

    // outbox pending really is 0 in the fixture, and that has to keep reading as 0
    expect(cardValue('Outbox pending')).toBe('0');
  });

  it('keeps PostgreSQL-backed cards populated while Redis is unavailable', async () => {
    mocked.overview.mockResolvedValue(fixtures.overview({ queues: fixtures.unavailable() }));

    render(<OverviewPage />);
    await screen.findByText('Dead-lettered jobs');

    // the value line specifically, so "50m ago" in the footer cannot satisfy a substring match
    expect(cardValue('Scheduled jobs')).toBe('Unavailable');
    expect(cardValue('Delayed retries')).toBe('Unavailable');
    expect(cardValue('Dead-lettered jobs')).toBe('1');
    expect(cardValue('Outbox terminal failures')).toBe('1');
  });

  it('reports a permission problem rather than an empty page', async () => {
    mocked.overview.mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', 'token rejected'));

    render(<OverviewPage />);

    expect(await screen.findByText(/Permission denied/)).toBeInTheDocument();
  });

  it('renders the activity feed and links each event to its job', async () => {
    render(<OverviewPage />);

    const link = await screen.findByRole('link', { name: /a4f2c1de/ });
    expect(link).toHaveAttribute(
      'href',
      '#/jobs/a4f2c1de-0000-4000-8000-000000000001'
    );
    expect(screen.getByText('job.dead_lettered')).toBeInTheDocument();
  });

  it('shows an empty activity feed as empty rather than missing', async () => {
    mocked.overview.mockResolvedValue(
      fixtures.overview({ activity: fixtures.available([]) })
    );

    render(<OverviewPage />);

    expect(await screen.findByText('No recorded activity yet.')).toBeInTheDocument();
  });

  function card(label: string) {
    return screen.getAllByText(label)[0].closest('.card') as HTMLElement;
  }

  function cardValue(label: string): string {
    return card(label).querySelector('.card__value')?.textContent ?? '';
  }
});

describe('Queues page', () => {
  it('labels stream length, executable depth and pending delivery as different columns', async () => {
    render(<QueuesPage />);
    await screen.findByText('Queue state by priority');

    const table = screen.getByRole('table', { name: /Queue depth by priority/i });
    expect(within(table).getByText('Stream length')).toBeInTheDocument();
    expect(within(table).getByText('Executable queue depth')).toBeInTheDocument();
    expect(within(table).getByText('Pending delivery count')).toBeInTheDocument();
    expect(within(table).getByText('Scheduled jobs')).toBeInTheDocument();
    expect(within(table).getByText('Delayed retry jobs')).toBeInTheDocument();
  });

  it('carries the explanatory note about stream length', async () => {
    render(<QueuesPage />);

    expect(
      await screen.findByText(/not equivalent to the number of jobs waiting to execute/i)
    ).toBeInTheDocument();
  });

  it('renders an unknown consumer-group lag as no data, not as a drained queue', async () => {
    render(<QueuesPage />);
    await screen.findByText('Queue state by priority');

    const table = screen.getByRole('table', { name: /Queue depth by priority/i });
    const headers = [...table.querySelectorAll('thead th')].map(
      (th) => th.firstChild?.textContent ?? ''
    );
    const row = within(table).getByText('LOW').closest('tr') as HTMLElement;
    const cells = [...row.querySelectorAll('td')].map((td) => td.textContent ?? '');

    expect(cells[headers.indexOf('Stream length')]).toBe('11');
    expect(cells[headers.indexOf('Executable queue depth')]).toBe('No data');
    expect(within(row).getByText('Unknown')).toBeInTheDocument();
  });

  it('reports the queue panel as unavailable when Redis is down', async () => {
    mocked.queues.mockResolvedValue(
      fixtures.queues({ queues: fixtures.unavailable('RedisConnectionFailureException') })
    );

    render(<QueuesPage />);

    const notices = await screen.findAllByText(/RedisConnectionFailureException/);
    expect(notices.length).toBeGreaterThan(0);
    expect(screen.getAllByText(/rather than shown as zero/).length).toBeGreaterThan(0);
  });

  it('gives every chart an accessible data table', async () => {
    render(<QueuesPage />);
    await screen.findByText('Queue state by priority');

    expect(
      screen.getByRole('table', { name: 'Pending entries by priority' })
    ).toBeInTheDocument();
  });
});

describe('Workers page', () => {
  it('shows lease ownership with job, attempt, worker and consumer', async () => {
    render(<WorkersPage />);
    await screen.findByText('Active execution leases');

    const table = screen.getByRole('table', { name: /Active execution leases/i });
    expect(within(table).getByText('send_email')).toBeInTheDocument();
    expect(within(table).getAllByText('worker-a1b2c3').length).toBeGreaterThan(0);
  });

  it('explains that a Redis consumer is not a database owner', async () => {
    render(<WorkersPage />);

    expect(
      await screen.findByText(/does not necessarily mean it owns database execution/i)
    ).toBeInTheDocument();
  });

  it('keeps leases visible and marks the heartbeat unknown when Redis is unreachable', async () => {
    const base = fixtures.workers();
    const leases = base.leases.data ?? [];
    mocked.workers.mockResolvedValue(
      fixtures.workers({
        consumers: fixtures.unavailable(),
        pendingEntries: fixtures.unavailable(),
        leases: fixtures.available([{ ...leases[0], heartbeatStale: null }])
      })
    );

    render(<WorkersPage />);
    await screen.findByText('Active execution leases');

    expect(screen.getByText('Heartbeat unknown')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: /Active execution leases/i })).toBeInTheDocument();
  });

  it('warns about an expired lease', async () => {
    const base = fixtures.workers();
    const leases = base.leases.data ?? [];
    mocked.workers.mockResolvedValue(
      fixtures.workers({
        leases: fixtures.available([
          { ...leases[0], expired: true, remainingLeaseMs: -1500, heartbeatStale: true }
        ])
      })
    );

    render(<WorkersPage />);

    expect(await screen.findByText('Lease expired')).toBeInTheDocument();
    expect(screen.getByText('Heartbeat stale')).toBeInTheDocument();
  });

  it('never renders a job payload', async () => {
    render(<WorkersPage />);
    await screen.findByText('Active execution leases');

    // the fixture's job type and ids are present; nothing resembling a body is
    expect(document.body.textContent).toContain('send_email');
    expect(document.body.textContent).not.toMatch(/[{[]"/);
    expect(screen.queryByText(/payload:/i)).not.toBeInTheDocument();
  });
});

describe('Outbox page', () => {
  it('shows the four lifecycle counts', async () => {
    render(<OutboxPage />);
    await screen.findByText('Lifecycle');

    ['PENDING', 'PUBLISHING', 'PUBLISHED'].forEach((status) =>
      expect(screen.getAllByText(status).length).toBeGreaterThan(0)
    );
    expect(screen.getByText('Terminal failures')).toBeInTheDocument();
  });

  it('renders the redacted error and never a payload', async () => {
    render(<OutboxPage />);
    await screen.findByText('Events');

    expect(screen.getByText(/unable to connect… \(truncated\)/)).toBeInTheDocument();
    expect(screen.queryByText(/Payload/)).not.toBeInTheDocument();
  });

  it('offers no retry or repair control', async () => {
    render(<OutboxPage />);
    await screen.findByText('Events');

    const buttons = screen.getAllByRole('button').map((button) => button.textContent ?? '');
    expect(buttons.join(' ')).not.toMatch(/retry|replay|repair|cleanup/i);
  });

  it('re-queries when a filter changes and resets to the first page', async () => {
    const user = userEvent.setup();
    render(<OutboxPage />);
    await screen.findByText('Events');

    await user.selectOptions(screen.getByLabelText('Status'), 'FAILED');

    await waitFor(() =>
      expect(mocked.outbox).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'FAILED', page: 0 }),
        expect.anything()
      )
    );
  });

  it('paginates', async () => {
    const user = userEvent.setup();
    const base = fixtures.outbox();
    const view = base.outbox.data!;
    mocked.outbox.mockResolvedValue(
      fixtures.outbox({
        outbox: fixtures.available({
          ...view,
          events: { ...view.events, totalElements: 120, totalPages: 3 }
        })
      })
    );
    render(<OutboxPage />);
    await screen.findByText('Events');

    await user.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() =>
      expect(mocked.outbox).toHaveBeenCalledWith(
        expect.objectContaining({ page: 1 }),
        expect.anything()
      )
    );
  });
});

describe('Reconciliation page', () => {
  it('states that it reports without repairing', async () => {
    render(<ReconciliationPage />);

    expect(
      await screen.findByText(/reports reconciliation findings but does not repair them/i)
    ).toBeInTheDocument();
  });

  it('shows preview mode, auto-repair state and operator action', async () => {
    render(<ReconciliationPage />);
    await screen.findByText('Latest run');

    expect(screen.getByText('Preview mode')).toBeInTheDocument();
    expect(screen.getByText('Auto-repair disabled')).toBeInTheDocument();
    expect(screen.getByText('Operator action required')).toBeInTheDocument();
  });

  it('lists findings with severity and repairability', async () => {
    render(<ReconciliationPage />);
    await screen.findByText('Findings');

    const table = screen.getByRole('table', { name: /Reconciliation findings/i });
    expect(within(table).getByText('TERMINAL_OUTBOX_FAILURE')).toBeInTheDocument();
    expect(within(table).getByText('ERROR')).toBeInTheDocument();
    expect(within(table).getAllByText('Needs a decision').length).toBe(2);
  });

  it('offers no repair control', async () => {
    render(<ReconciliationPage />);
    await screen.findByText('Findings');

    const buttons = screen.queryAllByRole('button').map((button) => button.textContent ?? '');
    expect(buttons.join(' ')).not.toMatch(/repair|run|fix/i);
  });
});

describe('Jobs pages', () => {
  it('lists jobs without a payload column', async () => {
    render(<JobsPage />);
    await screen.findByText('Job search');

    const table = screen.getByRole('table', { name: 'Jobs' });
    expect(within(table).queryByText('Payload')).not.toBeInTheDocument();
    expect(within(table).getByText('send_email')).toBeInTheDocument();
  });

  it('keeps the requested time and the retry time as separate columns', async () => {
    render(<JobsPage />);
    await screen.findByText('Job search');

    const table = screen.getByRole('table', { name: 'Jobs' });
    expect(within(table).getByText('Scheduled at')).toBeInTheDocument();
    expect(within(table).getByText('requested time')).toBeInTheDocument();
  });

  it('renders the job timeline in chronological order', async () => {
    render(<JobDetailPage jobId="a4f2c1de-0000-4000-8000-000000000006" />);
    await screen.findByText('Lifecycle timeline');

    const entries = screen
      .getByText('Lifecycle timeline')
      .closest('.panel')!
      .querySelectorAll('.timeline__when');
    const times = [...entries].map((node) => node.textContent ?? '');
    expect(times).toEqual([...times].sort());
  });

  it('distinguishes the requested execution time from the next retry', async () => {
    render(<JobDetailPage jobId="a4f2c1de-0000-4000-8000-000000000006" />);
    await screen.findByText('Scheduling, retry and dead-letter state');

    expect(screen.getAllByText('Requested execution time').length).toBeGreaterThan(0);
    expect(screen.getByText('Next attempt at')).toBeInTheDocument();
  });

  it('says the payload is redacted rather than showing an empty field', async () => {
    render(<JobDetailPage jobId="a4f2c1de-0000-4000-8000-000000000006" />);

    expect(await screen.findByText(/Redacted — not sent to the dashboard/)).toBeInTheDocument();
  });

  it('shows effect keys and their status', async () => {
    render(<JobDetailPage jobId="a4f2c1de-0000-4000-8000-000000000006" />);
    await screen.findByText('Effect ledger');

    expect(screen.getByText('report:2026-09-10')).toBeInTheDocument();
    expect(screen.getByText('COMPLETED')).toBeInTheDocument();
  });

  it('reports a missing job rather than rendering an empty detail page', async () => {
    mocked.job.mockRejectedValue(new ApiError(404, 'JOB_NOT_FOUND', 'No job with id abc'));

    render(<JobDetailPage jobId="abc" />);

    expect(await screen.findByText('No job with id abc')).toBeInTheDocument();
  });
});

describe('DLQ page', () => {
  it('lists dead-lettered jobs', async () => {
    render(<DlqPage />);
    await screen.findByText('Entries');

    const table = screen.getByRole('table', { name: /Dead-lettered jobs/i });
    expect(within(table).getByText('always_fail')).toBeInTheDocument();
  });

  it('offers no replay control and says where replay lives', async () => {
    render(<DlqPage />);
    await screen.findByText('Entries');

    expect(screen.getByText(/administrative API, not through this read-only/i)).toBeInTheDocument();
    const buttons = screen.getAllByRole('button').map((button) => button.textContent ?? '');
    expect(buttons.join(' ')).not.toMatch(/replay|retry/i);
  });

  it('shows an empty DLQ as empty', async () => {
    const base = fixtures.dlq();
    const view = base.dlq.data!;
    mocked.dlq.mockResolvedValue(
      fixtures.dlq({
        dlq: fixtures.available({
          ...view,
          totals: { ...view.totals, deadLettered: 0 },
          entries: { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
        })
      })
    );

    render(<DlqPage />);

    expect(await screen.findByText('No dead-lettered jobs.')).toBeInTheDocument();
  });
});

describe('Analytics page', () => {
  it('shows provenance, and says the data is historical rather than live', async () => {
    render(<AnalyticsPage />);
    await screen.findByText('Report provenance');

    expect(screen.getByText('Historical')).toBeInTheDocument();
    expect(screen.getByText('Not live')).toBeInTheDocument();
    // the run id appears both as the selected export and as a provenance field
    expect(screen.getAllByText('20260101T000000Z__20270101T000000Z').length).toBeGreaterThan(0);
    expect(screen.getByText('Report generated at')).toBeInTheDocument();
  });

  it('renders charts with accessible tables behind them', async () => {
    render(<AnalyticsPage />);
    await screen.findByText('Daily job summary');

    expect(screen.getByRole('table', { name: 'Jobs per day' })).toBeInTheDocument();
    expect(
      screen.getByRole('table', { name: 'Duration percentiles per day (ms)' })
    ).toBeInTheDocument();
  });

  it('renders a missing percentile as no data rather than zero', async () => {
    render(<AnalyticsPage />);
    await screen.findByText('Daily job summary');

    const table = screen.getByRole('table', { name: 'Daily job summary' });
    expect(within(table).getAllByText('No data').length).toBeGreaterThan(0);
  });

  it('shows a clear empty state when no export exists', async () => {
    mocked.analytics.mockResolvedValue(
      fixtures.analytics({
        analytics: fixtures.notConfigured('No analytics export is available for this deployment')
      })
    );

    render(<AnalyticsPage />);

    // every panel on the page says so, rather than one saying so and the rest drawing zeros
    const notices = await screen.findAllByText(
      /No analytics export is available for this deployment/
    );
    expect(notices.length).toBeGreaterThan(1);
    expect(screen.queryByRole('table', { name: 'Jobs per day' })).not.toBeInTheDocument();
  });

  it('passes the date and priority filters to the server', async () => {
    const user = userEvent.setup();
    render(<AnalyticsPage />);
    await screen.findByText('Report provenance');

    await user.selectOptions(screen.getByLabelText('Priority'), 'HIGH');

    await waitFor(() =>
      expect(mocked.analytics).toHaveBeenCalledWith(
        expect.objectContaining({ priority: 'HIGH' }),
        expect.anything()
      )
    );
  });
});

describe('System page', () => {
  it('shows build, runtime and dependency versions', async () => {
    render(<SystemPage />);
    await screen.findByText('Build and runtime');

    expect(screen.getByText('1.1.0')).toBeInTheDocument();
    expect(screen.getByText('7.2.4')).toBeInTheDocument();
    expect(screen.getByText('16.4')).toBeInTheDocument();
    expect(screen.getByText('3.5.16')).toBeInTheDocument();
    expect(screen.getByText('host-abc12345')).toBeInTheDocument();
  });

  it('shows no connection string, URL or credential', async () => {
    render(<SystemPage />);
    await screen.findByText('Build and runtime');

    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(/jdbc:/);
    expect(text).not.toMatch(/redis:\/\//);
    expect(text).not.toMatch(/postgresql:\/\//);
    expect(text).not.toMatch(/password/i);
    // "Bearer token required" is a label; an actual credential after the scheme is not
    expect(text).not.toMatch(/Bearer [A-Za-z0-9._-]{8,}/);
  });

  it('warns when the backend has no administrative token configured', async () => {
    mocked.system.mockResolvedValue(
      fixtures.system({
        system: fixtures.available({ ...fixtures.systemView, authenticationRequired: false })
      })
    );

    render(<SystemPage />);

    expect(await screen.findByText(/Not enforced/)).toBeInTheDocument();
  });
});

describe('Navigation', () => {
  it('lists every section and marks the current one', () => {
    render(<Nav current="queues" />);

    expect(screen.getAllByRole('listitem')).toHaveLength(9);
    expect(screen.getByRole('link', { name: 'Queues' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Overview' })).not.toHaveAttribute('aria-current');
  });

  it('collapses behind a disclosure button that reports its own state', async () => {
    const user = userEvent.setup();
    render(<Nav current="overview" />);

    const toggle = screen.getByRole('button', { name: 'Sections' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');

    // choosing a section closes the menu again, which is what a phone user expects
    await user.click(screen.getByRole('link', { name: 'Workers' }));
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('keeps the same links and semantics at every width', () => {
    render(<Nav current="overview" />);

    const list = screen.getByRole('list');
    expect(within(list).getAllByRole('link')).toHaveLength(9);
  });
});
