import { act, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MANUAL, backoffDelay, usePolling } from '../hooks/usePolling';
import { ApiError } from '../api/client';

/**
 * The four failure modes a polling monitor has to get right.
 *
 * <p>Each of these is a way the page could quietly become dangerous: a request storm during an
 * outage, a leaked timer after navigation, a blank page the moment a dependency wobbles, or an
 * old snapshot presented as current.
 */

function Probe({
  fetcher,
  intervalMs = 1000
}: {
  fetcher: (signal: AbortSignal) => Promise<string>;
  intervalMs?: number;
}) {
  const poll = usePolling(fetcher, { intervalMs });
  return (
    <div>
      <span data-testid="data">{poll.data ?? 'none'}</span>
      <span data-testid="loading">{String(poll.loading)}</span>
      <span data-testid="stale">{String(poll.stale)}</span>
      <span data-testid="failures">{poll.consecutiveFailures}</span>
      <span data-testid="unauthorized">{String(poll.unauthorized)}</span>
      <button type="button" onClick={poll.refresh}>
        refresh
      </button>
    </div>
  );
}

describe('backoffDelay', () => {
  it('polls at the configured interval while things are healthy', () => {
    expect(backoffDelay(5000, 0, 60_000)).toBe(5000);
  });

  it('doubles per consecutive failure', () => {
    expect(backoffDelay(5000, 1, 60_000)).toBe(10_000);
    expect(backoffDelay(5000, 2, 60_000)).toBe(20_000);
    expect(backoffDelay(5000, 3, 60_000)).toBe(40_000);
  });

  it('is capped, so an outage does not become an hour-long silence', () => {
    expect(backoffDelay(5000, 10, 60_000)).toBe(60_000);
    expect(backoffDelay(5000, 99, 60_000)).toBe(60_000);
  });

  it('has a floor for manual mode, so a forced refresh still backs off after failures', () => {
    expect(backoffDelay(MANUAL, 1, 60_000)).toBe(10_000);
  });
});

describe('usePolling', () => {
  it('loads once and reports the result', async () => {
    const fetcher = vi.fn().mockResolvedValue('first');

    render(<Probe fetcher={fetcher} />);

    await waitFor(() => expect(screen.getByTestId('data')).toHaveTextContent('first'));
    expect(screen.getByTestId('loading')).toHaveTextContent('false');
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('aborts the in-flight request when the component unmounts', async () => {
    let captured: AbortSignal | undefined;
    const fetcher = vi.fn((signal: AbortSignal) => {
      captured = signal;
      return new Promise<string>(() => undefined);
    });

    const { unmount } = render(<Probe fetcher={fetcher} />);
    await waitFor(() => expect(captured).toBeDefined());

    unmount();

    expect(captured?.aborted).toBe(true);
  });

  it('never runs two requests at once', async () => {
    vi.useFakeTimers();
    let settle: ((value: string) => void) | undefined;
    const fetcher = vi.fn(
      () =>
        new Promise<string>((resolve) => {
          settle = resolve;
        })
    );

    render(<Probe fetcher={fetcher} intervalMs={10} />);
    await vi.advanceTimersByTimeAsync(0);
    expect(fetcher).toHaveBeenCalledTimes(1);

    // several intervals elapse while the first request is still open
    await vi.advanceTimersByTimeAsync(200);
    expect(fetcher).toHaveBeenCalledTimes(1);

    await act(async () => {
      settle?.('done');
    });
    await vi.advanceTimersByTimeAsync(10);
    expect(fetcher).toHaveBeenCalledTimes(2);

    vi.useRealTimers();
  });

  it('keeps the last successful data and marks it stale when a refresh fails', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce('good')
      .mockRejectedValue(new Error('redis is unreachable'));

    render(<Probe fetcher={fetcher} intervalMs={20} />);
    await waitFor(() => expect(screen.getByTestId('data')).toHaveTextContent('good'));

    await waitFor(() => expect(screen.getByTestId('stale')).toHaveTextContent('true'), {
      timeout: 2000
    });
    // the data is still there; the page has not gone blank and has not gone to zero
    expect(screen.getByTestId('data')).toHaveTextContent('good');
    expect(Number(screen.getByTestId('failures').textContent)).toBeGreaterThan(0);
  });

  it('recovers and clears the stale flag once a refresh succeeds again', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce('good')
      .mockRejectedValueOnce(new Error('down'))
      .mockResolvedValue('recovered');

    render(<Probe fetcher={fetcher} intervalMs={20} />);

    await waitFor(() => expect(screen.getByTestId('stale')).toHaveTextContent('true'), {
      timeout: 2000
    });
    await waitFor(() => expect(screen.getByTestId('data')).toHaveTextContent('recovered'), {
      timeout: 5000
    });
    expect(screen.getByTestId('stale')).toHaveTextContent('false');
    expect(screen.getByTestId('failures')).toHaveTextContent('0');
  });

  it('flags an unauthorized failure so the shell can return to the sign-in panel', async () => {
    const fetcher = vi.fn().mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', 'token rejected'));

    render(<Probe fetcher={fetcher} intervalMs={MANUAL} />);

    await waitFor(() => expect(screen.getByTestId('unauthorized')).toHaveTextContent('true'));
  });

  it('does not schedule anything in manual mode', async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn().mockResolvedValue('once');

    render(<Probe fetcher={fetcher} intervalMs={MANUAL} />);
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(120_000);

    expect(fetcher).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });
});
