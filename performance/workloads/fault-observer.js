import http from 'k6/http';
import { sleep } from 'k6';

export const options = { scenarios: { observe: { executor: 'constant-vus', vus: 1,
  duration: `${Number(__ENV.SECONDS || 10)}s`, gracefulStop: '10s' } },
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'] };

export default function () {
  const started = Date.now();
  const observations = http.batch([
    ['GET', 'http://app:8080/actuator/health/readiness', null, { timeout: '2s' }],
    ['GET', 'http://app:8080/actuator/health/liveness', null, { timeout: '2s' }],
  ]);
  observations.forEach((response, index) => console.log(JSON.stringify({ atMs: Date.now(),
    probe: index === 0 ? 'readiness' : 'liveness', status: response.status,
    errorCode: response.error_code || null, durationMs: response.timings.duration })));
  sleep(Math.max(0, 1 - (Date.now() - started) / 1000));
}