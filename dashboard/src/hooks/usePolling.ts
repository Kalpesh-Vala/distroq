import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';

/**
 * Polling, with the four failure modes a monitoring page has to get right.
 *
 * <p>**No overlap.** The next request is scheduled after the previous one settles, using a
 * `setTimeout` chain rather than `setInterval`. An interval fires on a wall clock and does not
 * care that the last request is still in flight, which is how a slow backend turns one tab into
 * a queue of requests that grows until something falls over.
 *
 * <p>**Abort on unmount and on navigation.** The in-flight request is aborted when the effect
 * tears down, so leaving a page does not leave a timer or a fetch behind and does not produce a
 * `setState` on an unmounted component.
 *
 * <p>**Back off, then recover.** Consecutive failures double the delay up to a cap, so a
 * dependency outage does not become a request storm from every open tab. One success resets it.
 *
 * <p>**Keep the last good answer.** A failure does not clear `data`. The page keeps rendering what
 * it last knew, marked stale and stamped with the time it was true, because during an incident
 * five-minute-old queue depths are worth a great deal more than an empty screen.
 */

export const MANUAL = 0;

export interface PollingOptions {
  /** Milliseconds between polls, or {@link MANUAL} for refresh-on-demand only. */
  intervalMs: number;
  /** False parks the poller without unmounting it — used while the token gate is open. */
  enabled?: boolean;
  /** Changing this restarts the poller and discards the previous request. */
  key?: string;
  maxBackoffMs?: number;
  /** Data older than this is flagged stale even if no request has failed. */
  staleAfterMs?: number;
}

export interface PollingState<T> {
  data: T | null;
  error: Error | null;
  /** True only before the first successful load; a refresh does not blank the page. */
  loading: boolean;
  refreshing: boolean;
  lastUpdatedAt: number | null;
  consecutiveFailures: number;
  stale: boolean;
  unauthorized: boolean;
  refresh: () => void;
}

const DEFAULT_MAX_BACKOFF_MS = 60_000;

export function backoffDelay(intervalMs: number, failures: number, maxMs: number): number {
  if (failures <= 0) {
    return intervalMs;
  }
  const base = intervalMs > 0 ? intervalMs : 5_000;
  const scaled = base * 2 ** Math.min(failures, 6);
  return Math.min(scaled, maxMs);
}

export function usePolling<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  options: PollingOptions
): PollingState<T> {
  const {
    intervalMs,
    enabled = true,
    key = '',
    maxBackoffMs = DEFAULT_MAX_BACKOFF_MS,
    staleAfterMs
  } = options;

  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null);
  const [failures, setFailures] = useState(0);
  const [manualTick, setManualTick] = useState(0);

  // refs rather than state: the running loop must not be a dependency of itself
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  const inFlight = useRef(false);
  const failuresRef = useRef(0);
  failuresRef.current = failures;

  const refresh = useCallback(() => setManualTick((tick) => tick + 1), []);

  useEffect(() => {
    if (!enabled) {
      return undefined;
    }

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const controller = new AbortController();

    const schedule = () => {
      if (cancelled || intervalMs === MANUAL) {
        return;
      }
      timer = setTimeout(
        run,
        backoffDelay(intervalMs, failuresRef.current, maxBackoffMs)
      );
    };

    const run = async () => {
      // the guard that makes overlap impossible: a tick that arrives while a request is still
      // running is dropped, not queued
      if (inFlight.current || cancelled) {
        return;
      }
      inFlight.current = true;
      setRefreshing(true);
      try {
        const result = await fetcherRef.current(controller.signal);
        if (cancelled) {
          return;
        }
        setData(result);
        setError(null);
        setFailures(0);
        failuresRef.current = 0;
        setLastUpdatedAt(Date.now());
      } catch (cause) {
        if (cancelled || controller.signal.aborted) {
          return;
        }
        // data is deliberately untouched: the last good answer stays on screen, marked stale
        setError(cause instanceof Error ? cause : new Error(String(cause)));
        failuresRef.current += 1;
        setFailures(failuresRef.current);
      } finally {
        inFlight.current = false;
        if (!cancelled) {
          setRefreshing(false);
          setLoading(false);
          schedule();
        }
      }
    };

    void run();

    return () => {
      cancelled = true;
      controller.abort();
      inFlight.current = false;
      if (timer !== undefined) {
        clearTimeout(timer);
      }
    };
  }, [enabled, intervalMs, key, maxBackoffMs, manualTick]);

  const staleByAge =
    staleAfterMs !== undefined &&
    lastUpdatedAt !== null &&
    Date.now() - lastUpdatedAt > staleAfterMs;

  return {
    data,
    error,
    loading,
    refreshing,
    lastUpdatedAt,
    consecutiveFailures: failures,
    stale: (failures > 0 && data !== null) || staleByAge,
    unauthorized: error instanceof ApiError && error.unauthorized,
    refresh
  };
}
