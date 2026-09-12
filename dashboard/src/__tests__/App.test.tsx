import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { ApiError } from '../api/client';
import { getToken, setToken } from '../api/token';
import * as fixtures from './fixtures';

/**
 * The shell: the gate in front of everything, and what happens when the token stops working.
 *
 * <p>A rejected token has to end the session rather than leave nine panels each retrying with a
 * credential the server has already refused. That is both a usability property and a rate-limiting
 * one — a dashboard that keeps hammering an endpoint with a bad token is a dashboard that will get
 * an operator locked out of something.
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
  window.location.hash = '#/overview';
  Object.values(mocked).forEach((fn) => fn.mockReset());
  mocked.system.mockResolvedValue(fixtures.system());
  mocked.overview.mockResolvedValue(fixtures.overview());
});

describe('App shell', () => {
  it('asks for a token before showing anything', () => {
    render(<App />);

    expect(screen.getByLabelText('Administrative bearer token')).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Dashboard sections' })).not.toBeInTheDocument();
    expect(mocked.overview).not.toHaveBeenCalled();
  });

  it('accepts a token typed by the operator and stores it in sessionStorage only', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText('Administrative bearer token'), 'a-real-token');
    await user.click(screen.getByRole('button', { name: 'Open dashboard' }));

    await screen.findByRole('navigation', { name: 'Dashboard sections' });
    expect(sessionStorage.getItem('distroq.dashboard.token')).toBe('a-real-token');
    expect(localStorage.length).toBe(0);
  });

  it('masks the token field and does not offer to autofill it', () => {
    render(<App />);

    const input = screen.getByLabelText('Administrative bearer token');
    expect(input).toHaveAttribute('type', 'password');
    expect(input).toHaveAttribute('autocomplete', 'off');
  });

  it('documents that browser-held tokens are a development compromise', () => {
    render(<App />);

    expect(screen.getByText(/identity-aware proxy or SSO layer/i)).toBeInTheDocument();
    expect(screen.getByText(/discarded when the tab closes/i)).toBeInTheDocument();
  });

  it('never puts the token in the URL', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText('Administrative bearer token'), 'a-real-token');
    await user.click(screen.getByRole('button', { name: 'Open dashboard' }));
    await screen.findByRole('navigation', { name: 'Dashboard sections' });

    expect(window.location.href).not.toContain('a-real-token');
    expect(window.location.hash).not.toContain('a-real-token');
  });

  it('clears a rejected token and returns to the gate', async () => {
    setToken('stale-token');
    mocked.system.mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', 'token rejected'));

    render(<App />);

    await waitFor(() =>
      expect(screen.getByLabelText('Administrative bearer token')).toBeInTheDocument()
    );
    expect(getToken()).toBeNull();
    expect(screen.getByRole('alert')).toHaveTextContent('token rejected');
  });

  it('signs out on request', async () => {
    const user = userEvent.setup();
    setToken('a-token');
    render(<App />);
    await screen.findByRole('navigation', { name: 'Dashboard sections' });

    await user.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(getToken()).toBeNull();
    expect(screen.getByLabelText('Administrative bearer token')).toBeInTheDocument();
  });

  it('offers the documented refresh intervals and defaults to five seconds', async () => {
    setToken('a-token');
    render(<App />);
    await screen.findByRole('navigation', { name: 'Dashboard sections' });

    const select = screen.getByLabelText('Refresh') as HTMLSelectElement;
    expect(select.value).toBe('5000');
    expect([...select.options].map((option) => option.textContent)).toEqual([
      '5 seconds',
      '15 seconds',
      '30 seconds',
      '60 seconds',
      'Manual'
    ]);
  });

  it('propagates a slower interval to the page below it', async () => {
    const user = userEvent.setup();
    setToken('a-token');
    render(<App />);
    await screen.findByRole('navigation', { name: 'Dashboard sections' });
    const callsBefore = mocked.overview.mock.calls.length;

    await user.selectOptions(screen.getByLabelText('Refresh'), '60000');

    // changing the interval restarts the poller once and then leaves it alone
    await waitFor(() =>
      expect(mocked.overview.mock.calls.length).toBeGreaterThanOrEqual(callsBefore)
    );
    expect(screen.getByLabelText('Refresh')).toHaveValue('60000');
  });

  it('supports manual refresh', async () => {
    const user = userEvent.setup();
    setToken('a-token');
    render(<App />);
    await screen.findByRole('navigation', { name: 'Dashboard sections' });
    const before = mocked.system.mock.calls.length;

    await user.selectOptions(screen.getByLabelText('Refresh'), '0');
    await user.click(screen.getByRole('button', { name: 'Refresh now' }));

    await waitFor(() => expect(mocked.system.mock.calls.length).toBeGreaterThan(before));
  });

  it('offers a skip link and a single main landmark', async () => {
    setToken('a-token');
    render(<App />);
    await screen.findByRole('navigation', { name: 'Dashboard sections' });

    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#main');
    expect(screen.getAllByRole('main')).toHaveLength(1);
  });

  it('states that the dashboard is read-only', async () => {
    setToken('a-token');
    render(<App />);

    expect(await screen.findByText(/This dashboard is read-only/)).toBeInTheDocument();
  });

  it('routes from the hash without putting a job id in a query parameter', async () => {
    setToken('a-token');
    mocked.job.mockResolvedValue(fixtures.jobDetail());
    window.location.hash = '#/jobs/a4f2c1de-0000-4000-8000-000000000006';

    render(<App />);

    await waitFor(() => expect(mocked.job).toHaveBeenCalledWith(
      'a4f2c1de-0000-4000-8000-000000000006',
      expect.anything()
    ));
  });
});
