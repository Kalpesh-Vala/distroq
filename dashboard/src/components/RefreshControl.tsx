import { MANUAL } from '../hooks/usePolling';
import { formatRelative } from '../format';

/**
 * The refresh interval, the last successful update, and the connection state.
 *
 * <p>The last-updated stamp is the important part. Every number on the page is a snapshot, and a
 * page that keeps rendering a snapshot without saying how old it is will be read as live long
 * after it has stopped being live. During a failure the stamp stops advancing and the warning
 * appears, and the data underneath stays exactly where it was.
 */

export const INTERVALS: { label: string; value: number }[] = [
  { label: '5 seconds', value: 5_000 },
  { label: '15 seconds', value: 15_000 },
  { label: '30 seconds', value: 30_000 },
  { label: '60 seconds', value: 60_000 },
  { label: 'Manual', value: MANUAL }
];

interface Props {
  intervalMs: number;
  onIntervalChange: (intervalMs: number) => void;
  onRefresh: () => void;
  refreshing: boolean;
  lastUpdatedAt: number | null;
  stale: boolean;
  consecutiveFailures: number;
}

export function RefreshControl({
  intervalMs,
  onIntervalChange,
  onRefresh,
  refreshing,
  lastUpdatedAt,
  stale,
  consecutiveFailures
}: Props) {
  return (
    <div className="refresh">
      <label className="refresh__interval">
        Refresh
        <select
          value={intervalMs}
          onChange={(event) => onIntervalChange(Number(event.target.value))}
        >
          {INTERVALS.map((interval) => (
            <option key={interval.value} value={interval.value}>
              {interval.label}
            </option>
          ))}
        </select>
      </label>
      <button type="button" onClick={onRefresh} disabled={refreshing}>
        {refreshing ? 'Refreshing…' : 'Refresh now'}
      </button>
      <p className="refresh__status" role="status" aria-live="polite">
        {lastUpdatedAt === null
          ? 'Not yet loaded'
          : `Last updated ${formatRelative(lastUpdatedAt)}`}
        {stale
          ? ` — showing the last successful update; ${consecutiveFailures} refresh attempt(s) have failed`
          : ''}
      </p>
    </div>
  );
}
