import type { ReactNode } from 'react';
import type { Section } from '../api/types';
import { formatTimestamp } from '../format';

/**
 * The component that decides whether a panel has anything to show.
 *
 * <p>Every section of every page goes through here, which is how the seven UI states stay
 * consistent: loading, loaded, empty, partial failure, unavailable, unauthorized, stale. A page
 * never reads `section.data` directly, so it cannot accidentally render `undefined` as a blank
 * table that looks like "nothing is wrong".
 *
 * <p>Note the ordering. Unauthorized beats unavailable, because an expired token is an action the
 * operator can take; unavailable beats empty, because an unreachable dependency is not an empty
 * one; and empty is only reported when the server actually said so.
 */

interface Props<T> {
  title: string;
  section: Section<T> | undefined;
  loading?: boolean;
  unauthorized?: boolean;
  stale?: boolean;
  /** Renders the empty state instead of children when the data arrived but holds nothing. */
  isEmpty?: (data: T) => boolean;
  emptyMessage?: string;
  description?: ReactNode;
  actions?: ReactNode;
  children: (data: T, section: Section<T>) => ReactNode;
}

export function SectionPanel<T>({
  title,
  section,
  loading = false,
  unauthorized = false,
  stale = false,
  isEmpty,
  emptyMessage = 'Nothing to show.',
  description,
  actions,
  children
}: Props<T>) {
  return (
    <section className="panel" aria-labelledby={`${slug(title)}-heading`}>
      <header className="panel__header">
        <h2 id={`${slug(title)}-heading`}>{title}</h2>
        {actions ? <div className="panel__actions">{actions}</div> : null}
      </header>
      {description ? <p className="panel__description">{description}</p> : null}
      {stale && section?.availability === 'AVAILABLE' ? (
        <p className="notice notice--warn" role="status">
          Showing the last successful update from {formatTimestamp(section.lastUpdatedAt)}. The
          most recent refresh did not succeed.
        </p>
      ) : null}
      <div className="panel__body">{body()}</div>
    </section>
  );

  function body(): ReactNode {
    if (unauthorized) {
      return (
        <p className="notice notice--error" role="status">
          Permission denied. The dashboard token was rejected for this panel.
        </p>
      );
    }
    if (!section) {
      return loading ? (
        <p className="notice" role="status">
          Loading…
        </p>
      ) : (
        <p className="notice notice--muted" role="status">
          No data.
        </p>
      );
    }
    if (section.availability === 'NOT_CONFIGURED') {
      return (
        <p className="notice notice--muted" role="status">
          Not configured. {section.reason}
        </p>
      );
    }
    if (section.availability === 'UNAVAILABLE') {
      return (
        <p className="notice notice--error" role="status">
          Unavailable. This panel&apos;s data source could not be reached
          {section.reason ? ` (${section.reason})` : ''}. Values are not shown rather than shown as
          zero.
        </p>
      );
    }
    const data = section.data;
    if (data === null || data === undefined) {
      return (
        <p className="notice notice--muted" role="status">
          No data.
        </p>
      );
    }
    if (isEmpty?.(data)) {
      return (
        <p className="notice notice--muted" role="status">
          {emptyMessage}
        </p>
      );
    }
    return children(data, section);
  }
}

function slug(title: string): string {
  return title.toLowerCase().replace(/[^a-z0-9]+/g, '-');
}
