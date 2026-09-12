/**
 * Where the browser keeps the operator's bearer token.
 *
 * <p>`sessionStorage`, not `localStorage`, and the difference is the whole point: session storage
 * is scoped to the tab and is discarded when that tab closes, so a shared or unattended machine
 * does not keep an administrative credential across browser restarts. `localStorage` would.
 *
 * The token never appears in a URL, a query parameter, a hash fragment, a log line or an error
 * message. It is read only to build an `Authorization` header, and this module is the only place
 * that touches storage — which is what makes that claim checkable.
 *
 * This is a documented local-development and small-deployment compromise. A browser holding a
 * shared operator token is weaker than an identity-aware proxy in front of the dashboard, and
 * README.md says so and recommends the proxy for production.
 */

const STORAGE_KEY = 'distroq.dashboard.token';

type Listener = () => void;

const listeners = new Set<Listener>();

function notify(): void {
  listeners.forEach((listener) => listener());
}

export function getToken(): string | null {
  try {
    const value = sessionStorage.getItem(STORAGE_KEY);
    return value && value.length > 0 ? value : null;
  } catch {
    // Safari in private mode, and any browser with storage disabled. No token is a valid state:
    // the dashboard shows its sign-in panel instead of failing to start.
    return null;
  }
}

export function hasToken(): boolean {
  return getToken() !== null;
}

export function setToken(token: string): void {
  const trimmed = token.trim();
  if (trimmed.length === 0) {
    clearToken();
    return;
  }
  try {
    sessionStorage.setItem(STORAGE_KEY, trimmed);
  } catch {
    // storage unavailable; the caller will observe hasToken() staying false
  }
  notify();
}

/** Sign out. Also what a 401 loop should end in, so a rotated token is not retried forever. */
export function clearToken(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // nothing to clear
  }
  notify();
}

export function subscribeToToken(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
