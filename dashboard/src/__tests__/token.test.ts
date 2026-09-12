import { describe, expect, it } from 'vitest';
import { clearToken, getToken, hasToken, setToken, subscribeToToken } from '../api/token';

/**
 * Where the token lives, and where it must not.
 *
 * <p>`localStorage` survives the browser closing. On a shared or unattended machine that turns a
 * borrowed operator credential into a permanent one, which is the reason this module uses session
 * storage and the reason there is a test asserting the other one stays empty.
 */
describe('token storage', () => {
  it('starts with no token', () => {
    expect(getToken()).toBeNull();
    expect(hasToken()).toBe(false);
  });

  it('keeps the token in sessionStorage and never in localStorage', () => {
    setToken('correct-horse-battery-staple');

    expect(getToken()).toBe('correct-horse-battery-staple');
    expect(sessionStorage.length).toBe(1);
    expect(localStorage.length).toBe(0);
    expect(JSON.stringify(localStorage)).not.toContain('correct-horse');
  });

  it('trims surrounding whitespace from a pasted token', () => {
    setToken('  spaced-token \n');

    expect(getToken()).toBe('spaced-token');
  });

  it('treats a blank token as a sign-out rather than storing an empty credential', () => {
    setToken('a-token');
    setToken('   ');

    expect(getToken()).toBeNull();
    expect(hasToken()).toBe(false);
  });

  it('clears the token on sign-out', () => {
    setToken('a-token');

    clearToken();

    expect(getToken()).toBeNull();
    expect(sessionStorage.length).toBe(0);
  });

  it('notifies subscribers so the shell can return to the sign-in panel', () => {
    const seen: (string | null)[] = [];
    const unsubscribe = subscribeToToken(() => seen.push(getToken()));

    setToken('a-token');
    clearToken();
    unsubscribe();
    setToken('ignored-after-unsubscribe');

    expect(seen).toEqual(['a-token', null]);
  });
});
