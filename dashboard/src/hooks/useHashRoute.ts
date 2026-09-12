import { useEffect, useState } from 'react';

/**
 * Hash routing, in thirty lines instead of a dependency.
 *
 * <p>A router library would be a reasonable choice and is not needed for nine static sections and
 * one detail view. Hash routing in particular keeps the dashboard working when it is served by
 * anything at all — a static file server with no SPA fallback, a preview build, a file:// open
 * during an incident — because the server never sees the part after the `#`.
 *
 * <p>The route holds a section name and, at most, a job id. It never holds a token: everything
 * after `#` is in browser history, in bookmarks and in whatever the operator pastes into a ticket.
 */

export const SECTIONS = [
  'overview',
  'queues',
  'workers',
  'outbox',
  'reconciliation',
  'jobs',
  'dlq',
  'analytics',
  'system'
] as const;

export type SectionName = (typeof SECTIONS)[number];

export interface Route {
  section: SectionName;
  jobId: string | null;
}

export function parseHash(hash: string): Route {
  const path = hash.replace(/^#\/?/, '').split('?')[0];
  const [head, tail] = path.split('/');
  const section = (SECTIONS as readonly string[]).includes(head)
    ? (head as SectionName)
    : 'overview';
  return { section, jobId: section === 'jobs' && tail ? decodeURIComponent(tail) : null };
}

export function hrefFor(section: SectionName, jobId?: string): string {
  return jobId ? `#/${section}/${encodeURIComponent(jobId)}` : `#/${section}`;
}

export function useHashRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parseHash(window.location.hash));

  useEffect(() => {
    const onChange = () => setRoute(parseHash(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  return route;
}
