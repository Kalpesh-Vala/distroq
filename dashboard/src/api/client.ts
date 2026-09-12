import { getToken } from './token';
import type {
  ActivityResponse,
  AnalyticsResponse,
  DlqResponse,
  JobDetailResponse,
  JobsResponse,
  OutboxResponse,
  OverviewResponse,
  QueuesResponse,
  ReconciliationResponse,
  SystemResponse,
  WorkersResponse
} from './types';

/**
 * The only way this application talks to the server.
 *
 * <p>Three rules are enforced here rather than remembered at call sites.
 *
 * Every request goes to a path this module built. There is no `request(path)` export and no way
 * to pass an arbitrary URL in: the exported functions below are the whole surface, and each one
 * hardcodes its endpoint under `/api/dashboard`. A generic proxy would let a bug — or a crafted
 * route parameter — point an authenticated request at `POST /api/jobs/{id}/retry`.
 *
 * Every request is a GET. `method` is not a parameter.
 *
 * The token is attached as an `Authorization` header and never as a query parameter. Query
 * strings end up in browser history, in `Referer`, and in every access log between here and the
 * server. {@link ApiError} carries the server's message and status and never the header value.
 */

export const DEFAULT_TIMEOUT_MS = 15_000;

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId: string | null;

  constructor(status: number, code: string, message: string, correlationId: string | null = null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
  }

  get unauthorized(): boolean {
    return this.status === 401 || this.status === 403;
  }
}

export class TimeoutError extends Error {
  constructor(readonly timeoutMs: number) {
    super(`The request did not complete within ${timeoutMs}ms`);
    this.name = 'TimeoutError';
  }
}

export type QueryValue = string | number | boolean | null | undefined;

export interface RequestOptions {
  signal?: AbortSignal;
  timeoutMs?: number;
}

interface ErrorBody {
  code?: string;
  message?: string;
  correlationId?: string;
}

function buildQuery(params: Record<string, QueryValue> | undefined): string {
  if (!params) {
    return '';
  }
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.append(key, String(value));
    }
  });
  const query = search.toString();
  return query.length > 0 ? `?${query}` : '';
}

/**
 * Aborts on whichever comes first: the caller's signal, or the timeout.
 *
 * Written out rather than using `AbortSignal.any`, which is recent enough that a browser an
 * operations team actually has installed may not have it, and this is the one page they open when
 * things are already going wrong.
 */
function withTimeout(external: AbortSignal | undefined, timeoutMs: number) {
  const controller = new AbortController();
  let timedOut = false;

  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);

  const forward = () => controller.abort();
  external?.addEventListener('abort', forward);

  return {
    signal: controller.signal,
    timedOut: () => timedOut,
    release: () => {
      clearTimeout(timer);
      external?.removeEventListener('abort', forward);
    }
  };
}

async function getJson<T>(
  path: string,
  params?: Record<string, QueryValue>,
  options: RequestOptions = {}
): Promise<T> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const guard = withTimeout(options.signal, timeoutMs);
  const token = getToken();

  const headers: Record<string, string> = { Accept: 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  let response: Response;
  try {
    response = await fetch(`${path}${buildQuery(params)}`, {
      method: 'GET',
      headers,
      signal: guard.signal,
      credentials: 'same-origin',
      cache: 'no-store'
    });
  } catch (cause) {
    if (guard.timedOut()) {
      throw new TimeoutError(timeoutMs);
    }
    throw cause;
  } finally {
    guard.release();
  }

  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as T;
}

async function toApiError(response: Response): Promise<ApiError> {
  let body: ErrorBody = {};
  try {
    body = (await response.json()) as ErrorBody;
  } catch {
    // a proxy or a container can answer with HTML; the status is still the useful part
  }
  const code = body.code ?? defaultCode(response.status);
  const message = body.message ?? defaultMessage(response.status);
  return new ApiError(response.status, code, message, body.correlationId ?? null);
}

function defaultCode(status: number): string {
  if (status === 401) return 'UNAUTHORIZED';
  if (status === 403) return 'FORBIDDEN';
  if (status === 404) return 'NOT_FOUND';
  if (status === 503) return 'DEPENDENCY_UNAVAILABLE';
  return 'INTERNAL_ERROR';
}

function defaultMessage(status: number): string {
  if (status === 401) return 'An administrative bearer token is required';
  if (status === 403) return 'The dashboard API is not available to this caller';
  if (status === 404) return 'Not found';
  if (status === 503) return 'A required dependency is unavailable';
  return `The request failed with status ${status}`;
}

const BASE = '/api/dashboard';

export interface JobsQuery {
  status?: string;
  priority?: string;
  jobType?: string;
  createdAfter?: string;
  createdBefore?: string;
  scheduled?: boolean;
  hasAttempts?: boolean;
  jobId?: string;
  page?: number;
  size?: number;
  sort?: string;
  direction?: 'asc' | 'desc';
}

export interface OutboxQuery {
  status?: string;
  eventType?: string;
  aggregateId?: string;
  page?: number;
  size?: number;
  sort?: string;
  direction?: 'asc' | 'desc';
}

export interface AnalyticsQuery {
  run?: string;
  startDate?: string;
  endDate?: string;
  priority?: string;
  jobType?: string;
}

export const api = {
  overview: (options?: RequestOptions) =>
    getJson<OverviewResponse>(`${BASE}/overview`, undefined, options),

  queues: (options?: RequestOptions) =>
    getJson<QueuesResponse>(`${BASE}/queues`, undefined, options),

  workers: (options?: RequestOptions) =>
    getJson<WorkersResponse>(`${BASE}/workers`, undefined, options),

  outbox: (query: OutboxQuery, options?: RequestOptions) =>
    getJson<OutboxResponse>(`${BASE}/outbox`, { ...query }, options),

  reconciliation: (options?: RequestOptions) =>
    getJson<ReconciliationResponse>(`${BASE}/reconciliation`, undefined, options),

  jobs: (query: JobsQuery, options?: RequestOptions) =>
    getJson<JobsResponse>(`${BASE}/jobs`, { ...query }, options),

  // encodeURIComponent, because a job id reaches this from the URL hash and is not to be trusted
  // to be a UUID just because it usually is
  job: (jobId: string, options?: RequestOptions) =>
    getJson<JobDetailResponse>(`${BASE}/jobs/${encodeURIComponent(jobId)}`, undefined, options),

  dlq: (query: { replayed?: boolean; page?: number; size?: number }, options?: RequestOptions) =>
    getJson<DlqResponse>(`${BASE}/dlq`, { ...query }, options),

  analytics: (query: AnalyticsQuery, options?: RequestOptions) =>
    getJson<AnalyticsResponse>(`${BASE}/analytics`, { ...query }, options),

  system: (options?: RequestOptions) =>
    getJson<SystemResponse>(`${BASE}/system`, undefined, options),

  activity: (limit: number | undefined, options?: RequestOptions) =>
    getJson<ActivityResponse>(`${BASE}/activity`, { limit }, options)
};
