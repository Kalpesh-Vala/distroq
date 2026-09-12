/**
 * Formatting, in one place, so a number means the same thing on every page.
 *
 * <p>The most important function here is {@link renderMetric}. Every other formatter turns a value
 * into text; that one decides whether there is a value at all. "0", "no data", "unavailable" and
 * "not configured" are four different answers, and a dashboard that renders the last three as `0`
 * will eventually persuade an operator that an outage is an idle queue.
 *
 * <p>Times are UTC, always, with the `Z` shown. An operations team spans time zones and reads logs
 * that are already UTC; rendering a browser-local timestamp next to a UTC log line is how two
 * people end up looking at events an hour apart and agreeing they match.
 */

export type MetricState = 'value' | 'no-data' | 'unavailable' | 'not-configured' | 'stale';

export interface RenderedMetric {
  text: string;
  state: MetricState;
}

const NO_DATA = 'No data';
const UNAVAILABLE = 'Unavailable';
const NOT_CONFIGURED = 'Not configured';

export function renderMetric(
  value: number | null | undefined,
  availability: 'AVAILABLE' | 'UNAVAILABLE' | 'NOT_CONFIGURED' | undefined
): RenderedMetric {
  if (availability === 'UNAVAILABLE') {
    return { text: UNAVAILABLE, state: 'unavailable' };
  }
  if (availability === 'NOT_CONFIGURED') {
    return { text: NOT_CONFIGURED, state: 'not-configured' };
  }
  if (value === null || value === undefined || Number.isNaN(value)) {
    return { text: NO_DATA, state: 'no-data' };
  }
  return { text: formatNumber(value), state: 'value' };
}

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return NO_DATA;
  }
  return new Intl.NumberFormat('en-GB').format(value);
}

/** Null is "no data"; zero is a duration of zero. They are not the same and never collapse. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || Number.isNaN(ms)) {
    return NO_DATA;
  }
  const negative = ms < 0;
  const total = Math.abs(ms);
  const rendered = renderDuration(total);
  return negative ? `-${rendered}` : rendered;
}

function renderDuration(total: number): string {
  if (total < 1000) {
    return `${Math.round(total)}ms`;
  }
  const seconds = Math.floor(total / 1000) % 60;
  const minutes = Math.floor(total / 60_000) % 60;
  const hours = Math.floor(total / 3_600_000) % 24;
  const days = Math.floor(total / 86_400_000);

  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${(total / 1000).toFixed(1)}s`;
}

/** `2026-09-10 12:00:04Z`. Deliberately not locale-formatted; see the module comment. */
export function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) {
    return NO_DATA;
  }
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) {
    return NO_DATA;
  }
  return `${parsed.toISOString().slice(0, 19).replace('T', ' ')}Z`;
}

export function formatRelative(
  iso: string | number | null | undefined,
  now: number = Date.now()
): string {
  if (iso === null || iso === undefined) {
    return NO_DATA;
  }
  const millis = typeof iso === 'number' ? iso : new Date(iso).getTime();
  if (Number.isNaN(millis)) {
    return NO_DATA;
  }
  const delta = now - millis;
  if (Math.abs(delta) < 1000) {
    return 'just now';
  }
  return delta >= 0 ? `${formatDuration(delta)} ago` : `in ${formatDuration(-delta)}`;
}

export function formatPercent(rate: number | null | undefined): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) {
    return NO_DATA;
  }
  return `${(rate * 100).toFixed(1)}%`;
}

export function formatBoolean(value: boolean | null | undefined): string {
  if (value === null || value === undefined) {
    return NO_DATA;
  }
  return value ? 'Yes' : 'No';
}

/** Long identifiers get an ellipsis in the middle; the ends are what an operator matches on. */
export function shortId(id: string | null | undefined, keep = 8): string {
  if (!id) {
    return NO_DATA;
  }
  return id.length <= keep * 2 + 1 ? id : `${id.slice(0, keep)}…${id.slice(-4)}`;
}

export type Tone = 'ok' | 'info' | 'warn' | 'error' | 'unknown';

/** Health status to tone. `UNKNOWN` is grey, not green: not asked is not healthy. */
export function toneForStatus(status: string | null | undefined): Tone {
  switch ((status ?? '').toUpperCase()) {
    case 'UP':
    case 'CORRECT':
    case 'ACCEPTING_TRAFFIC':
    case 'PUBLISHED':
    case 'SUCCEEDED':
      return 'ok';
    case 'DOWN':
    case 'BROKEN':
    case 'FAILED':
    case 'DEAD_LETTERED':
      return 'error';
    case 'OUT_OF_SERVICE':
    case 'REFUSING_TRAFFIC':
    case 'RETRYING':
    case 'PUBLISHING':
      return 'warn';
    case 'RUNNING':
    case 'QUEUED':
    case 'SCHEDULED':
    case 'PENDING':
      return 'info';
    default:
      return 'unknown';
  }
}

export function toneForMetricState(state: MetricState): Tone {
  switch (state) {
    case 'unavailable':
      return 'error';
    case 'not-configured':
    case 'no-data':
      return 'unknown';
    case 'stale':
      return 'warn';
    default:
      return 'ok';
  }
}

export function toneForSeverity(severity: string): Tone {
  switch (severity) {
    case 'ERROR':
      return 'error';
    case 'WARNING':
      return 'warn';
    case 'INFO':
      return 'info';
    default:
      return 'unknown';
  }
}
