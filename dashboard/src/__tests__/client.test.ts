import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, TimeoutError, api } from '../api/client';
import { setToken } from '../api/token';
import { overview } from './fixtures';

/**
 * The client, and the three things it must never do: send a token in a URL, echo a token into an
 * error, or issue anything but a GET.
 */
describe('dashboard API client', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    fetchMock.mockReset();
  });

  function respond(body: unknown, status = 200) {
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => body
    } as unknown as Response;
  }

  it('returns the parsed body on success', async () => {
    fetchMock.mockResolvedValue(respond(overview()));

    const response = await api.overview();

    expect(response.correlationId).toBe('corr-1');
    expect(response.queues.availability).toBe('AVAILABLE');
  });

  it('sends the token as an Authorization header and never in the URL', async () => {
    setToken('correct-horse-battery-staple');
    fetchMock.mockResolvedValue(respond(overview()));

    await api.overview();

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/dashboard/overview');
    expect(url).not.toContain('correct-horse');
    expect(init.headers.Authorization).toBe('Bearer correct-horse-battery-staple');
    expect(init.method).toBe('GET');
  });

  it('omits the Authorization header entirely when there is no token', async () => {
    fetchMock.mockResolvedValue(respond(overview()));

    await api.overview();

    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  it('only ever issues GET requests', async () => {
    fetchMock.mockResolvedValue(respond(overview()));

    await api.overview();
    await api.queues();
    await api.jobs({ status: 'QUEUED' });
    await api.dlq({ replayed: false });
    await api.analytics({});

    fetchMock.mock.calls.forEach(([, init]) => expect(init.method).toBe('GET'));
  });

  it('builds every path under /api/dashboard', async () => {
    fetchMock.mockResolvedValue(respond(overview()));

    await api.overview();
    await api.workers();
    await api.job('a4f2c1de-0000-4000-8000-000000000001');
    await api.activity(25);

    fetchMock.mock.calls.forEach(([url]) => expect(url).toMatch(/^\/api\/dashboard\//));
  });

  it('escapes a job id taken from the URL hash', async () => {
    fetchMock.mockResolvedValue(respond(overview()));

    await api.job('../../admin/outbox');

    expect(fetchMock.mock.calls[0][0]).toBe('/api/dashboard/jobs/..%2F..%2Fadmin%2Foutbox');
  });

  it('drops empty query parameters instead of sending blanks', async () => {
    fetchMock.mockResolvedValue(respond(overview()));

    await api.jobs({ status: 'QUEUED', jobType: '', priority: undefined, page: 0 });

    expect(fetchMock.mock.calls[0][0]).toBe('/api/dashboard/jobs?status=QUEUED&page=0');
  });

  it('turns a 401 into an unauthorized ApiError carrying the server code', async () => {
    setToken('a-token');
    fetchMock.mockResolvedValue(
      respond(
        {
          code: 'UNAUTHORIZED',
          message: 'A valid administrative bearer token is required',
          correlationId: 'corr-9'
        },
        401
      )
    );

    const failure = await api.overview().catch((cause: unknown) => cause);

    expect(failure).toBeInstanceOf(ApiError);
    const error = failure as ApiError;
    expect(error.status).toBe(401);
    expect(error.code).toBe('UNAUTHORIZED');
    expect(error.unauthorized).toBe(true);
    expect(error.correlationId).toBe('corr-9');
  });

  it('never puts the token into an error message', async () => {
    setToken('correct-horse-battery-staple');
    fetchMock.mockResolvedValue(respond({ code: 'UNAUTHORIZED', message: 'nope' }, 401));

    const failure = (await api.overview().catch((cause: unknown) => cause)) as ApiError;

    expect(`${failure.message} ${failure.stack ?? ''}`).not.toContain('correct-horse');
  });

  it('falls back to a status-derived code when the body is not the error contract', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 503,
      json: async () => {
        throw new Error('not json');
      }
    } as unknown as Response);

    const failure = (await api.queues().catch((cause: unknown) => cause)) as ApiError;

    expect(failure.code).toBe('DEPENDENCY_UNAVAILABLE');
    expect(failure.status).toBe(503);
  });

  it('gives up on a request that never answers', async () => {
    fetchMock.mockImplementation(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => reject(new Error('aborted')));
        })
    );

    const failure = await api.overview({ timeoutMs: 10 }).catch((cause: unknown) => cause);

    expect(failure).toBeInstanceOf(TimeoutError);
  });

  it("forwards the caller's abort signal so a poll can be cancelled", async () => {
    const controller = new AbortController();
    let aborted = false;
    fetchMock.mockImplementation(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => {
            aborted = true;
            reject(new DOMException('aborted', 'AbortError'));
          });
        })
    );

    const pending = api.overview({ signal: controller.signal }).catch(() => 'cancelled');
    controller.abort();

    await expect(pending).resolves.toBe('cancelled');
    expect(aborted).toBe(true);
  });
});
