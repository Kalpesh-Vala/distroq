import { describe, expect, it } from 'vitest';
import {
  formatBoolean,
  formatDuration,
  formatNumber,
  formatPercent,
  formatRelative,
  formatTimestamp,
  renderMetric,
  shortId,
  toneForSeverity,
  toneForStatus
} from '../format';

/**
 * The rule that matters most is in {@link renderMetric}: zero, no data, unavailable and not
 * configured are four different answers, and only the first one is a number.
 */
describe('renderMetric', () => {
  it('renders a real zero as zero', () => {
    expect(renderMetric(0, 'AVAILABLE')).toEqual({ text: '0', state: 'value' });
  });

  it('never renders an unavailable panel as zero', () => {
    expect(renderMetric(undefined, 'UNAVAILABLE')).toEqual({
      text: 'Unavailable',
      state: 'unavailable'
    });
    // even when the server sent a number, an unavailable section is unavailable
    expect(renderMetric(7, 'UNAVAILABLE').text).toBe('Unavailable');
  });

  it('distinguishes not-configured from unavailable', () => {
    expect(renderMetric(null, 'NOT_CONFIGURED')).toEqual({
      text: 'Not configured',
      state: 'not-configured'
    });
  });

  it('reports a missing value as no data', () => {
    expect(renderMetric(null, 'AVAILABLE').text).toBe('No data');
    expect(renderMetric(undefined, 'AVAILABLE').text).toBe('No data');
    expect(renderMetric(Number.NaN, 'AVAILABLE').text).toBe('No data');
  });

  it('groups thousands so a six-figure backlog is readable at a glance', () => {
    expect(renderMetric(1_234_567, 'AVAILABLE').text).toBe('1,234,567');
  });
});

describe('formatNumber', () => {
  it('renders no data rather than NaN', () => {
    expect(formatNumber(null)).toBe('No data');
    expect(formatNumber(Number.NaN)).toBe('No data');
  });
});

describe('formatDuration', () => {
  it('keeps a zero duration distinct from an absent one', () => {
    expect(formatDuration(0)).toBe('0ms');
    expect(formatDuration(null)).toBe('No data');
  });

  it('scales its unit with the magnitude', () => {
    expect(formatDuration(450)).toBe('450ms');
    expect(formatDuration(4500)).toBe('4.5s');
    expect(formatDuration(90_000)).toBe('1m 30s');
    expect(formatDuration(3_930_000)).toBe('1h 5m');
    expect(formatDuration(93_600_000)).toBe('1d 2h');
  });

  it('signs a negative duration, because an expired lease is a real state', () => {
    expect(formatDuration(-4500)).toBe('-4.5s');
  });
});

describe('formatTimestamp', () => {
  it('renders UTC with the Z, so it lines up with the log lines', () => {
    expect(formatTimestamp('2026-09-10T12:00:04.123Z')).toBe('2026-09-10 12:00:04Z');
  });

  it('normalises an offset timestamp to UTC rather than showing it as written', () => {
    expect(formatTimestamp('2026-09-10T14:00:00+02:00')).toBe('2026-09-10 12:00:00Z');
  });

  it('reports no data for a missing or unparseable value', () => {
    expect(formatTimestamp(null)).toBe('No data');
    expect(formatTimestamp('not a date')).toBe('No data');
  });
});

describe('formatRelative', () => {
  const now = Date.parse('2026-09-10T12:00:00Z');

  it('describes the past and the future differently', () => {
    expect(formatRelative('2026-09-10T11:59:00Z', now)).toBe('1m 0s ago');
    expect(formatRelative('2026-09-10T12:01:00Z', now)).toBe('in 1m 0s');
  });

  it('collapses sub-second differences', () => {
    expect(formatRelative('2026-09-10T11:59:59.900Z', now)).toBe('just now');
  });
});

describe('formatPercent and formatBoolean', () => {
  it('renders a rate to one decimal place', () => {
    expect(formatPercent(0.831579)).toBe('83.2%');
    expect(formatPercent(1)).toBe('100.0%');
  });

  it('never renders an unknown rate as zero percent', () => {
    expect(formatPercent(null)).toBe('No data');
  });

  it('keeps false and unknown apart', () => {
    expect(formatBoolean(false)).toBe('No');
    expect(formatBoolean(null)).toBe('No data');
  });
});

describe('shortId', () => {
  it('keeps both ends of a long identifier', () => {
    expect(shortId('a4f2c1de-0000-4000-8000-000000000001')).toBe('a4f2c1de…0001');
  });

  it('leaves a short identifier alone', () => {
    expect(shortId('worker-a1')).toBe('worker-a1');
  });

  it('reports no data rather than an empty cell', () => {
    expect(shortId(null)).toBe('No data');
  });
});

describe('tones', () => {
  it('does not treat an unknown status as healthy', () => {
    expect(toneForStatus('UNKNOWN')).toBe('unknown');
    expect(toneForStatus(null)).toBe('unknown');
    expect(toneForStatus('UP')).toBe('ok');
    expect(toneForStatus('DOWN')).toBe('error');
    expect(toneForStatus('OUT_OF_SERVICE')).toBe('warn');
  });

  it('maps finding severity onto the shared palette', () => {
    expect(toneForSeverity('ERROR')).toBe('error');
    expect(toneForSeverity('WARNING')).toBe('warn');
    expect(toneForSeverity('INFO')).toBe('info');
  });
});
