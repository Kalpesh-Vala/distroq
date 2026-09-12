import type { ActivityEvent } from '../api/types';
import { formatRelative, formatTimestamp, shortId, toneForStatus } from '../format';
import { StatusBadge } from './StatusBadge';
import { hrefFor } from '../hooks/useHashRoute';

/**
 * Recent transitions, newest first.
 *
 * <p>Every line is reconstructed from a database row rather than from a log file — the dashboard
 * has no log-reading endpoint and is not getting one — so the feed shows transitions that left a
 * durable trace. Readiness changes are the exception and come from an in-process ring in the
 * instance serving the request, which is why they vanish on restart.
 */
export function ActivityFeed({ events }: { events: ActivityEvent[] }) {
  if (events.length === 0) {
    return (
      <p className="notice notice--muted" role="status">
        No recorded activity in the window the feed covers.
      </p>
    );
  }
  return (
    <ol className="feed">
      {events.map((event, index) => (
        <li className="feed__item" key={`${event.type}-${event.at}-${event.jobId ?? index}`}>
          <StatusBadge tone={toneForEvent(event.type)}>{event.type}</StatusBadge>
          <span className="feed__when" title={formatTimestamp(event.at)}>
            {formatRelative(event.at)}
          </span>
          {event.jobId ? (
            <a className="feed__target" href={hrefFor('jobs', event.jobId)}>
              {shortId(event.jobId)}
            </a>
          ) : null}
          {event.jobType ? <span className="feed__type">{event.jobType}</span> : null}
          {event.priority ? <span className="feed__priority">{event.priority}</span> : null}
          {event.status && !event.jobType ? (
            <StatusBadge tone={toneForStatus(event.status)}>{event.status}</StatusBadge>
          ) : null}
          {event.detail ? <span className="feed__detail">{event.detail}</span> : null}
        </li>
      ))}
    </ol>
  );
}

function toneForEvent(type: string) {
  if (type.endsWith('dead_lettered') || type.endsWith('failed')) {
    return 'error' as const;
  }
  if (type.endsWith('succeeded') || type.endsWith('published')) {
    return 'ok' as const;
  }
  if (type.includes('retry') || type.includes('reclaimed') || type.includes('finding')) {
    return 'warn' as const;
  }
  return 'info' as const;
}
