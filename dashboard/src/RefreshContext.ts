import { createContext, useContext } from 'react';

/**
 * The refresh interval, shared by every page.
 *
 * <p>One setting rather than one per page. An operator who slows the dashboard down because the
 * backend is struggling means all of it, and a per-page interval would quietly leave the tab they
 * are not looking at polling every five seconds.
 */
export const RefreshContext = createContext<number>(5_000);

export function useRefreshInterval(): number {
  return useContext(RefreshContext);
}
