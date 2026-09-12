import type { ReactNode } from 'react';
import { StatusBadge } from './StatusBadge';
import { formatRelative, toneForMetricState, type RenderedMetric, type Tone } from '../format';

/**
 * One number on the overview, with everything needed to trust it.
 *
 * <p>A value, a label, when it was last true, and — where the metric is not self-explanatory — a
 * short explanation. `Stream length` and `ready depth` are the reason the explanation exists:
 * both are integers about a Redis stream and they mean entirely different things.
 *
 * <p>The tone follows the metric's state rather than its value when the value is missing, so an
 * unavailable card is grey or red and never a green zero.
 */

interface Props {
  label: string;
  metric: RenderedMetric;
  tone?: Tone;
  explanation?: string;
  lastUpdatedAt?: string | number | null;
  footer?: ReactNode;
}

export function MetricCard({ label, metric, tone, explanation, lastUpdatedAt, footer }: Props) {
  const resolved: Tone = metric.state === 'value' ? (tone ?? 'info') : toneForMetricState(metric.state);
  const describedBy = explanation ? `${slug(label)}-explanation` : undefined;

  return (
    <article className="card" aria-labelledby={`${slug(label)}-label`} aria-describedby={describedBy}>
      <h3 className="card__label" id={`${slug(label)}-label`}>
        {label}
      </h3>
      <p className={`card__value card__value--${resolved}`}>{metric.text}</p>
      <StatusBadge tone={resolved}>{stateText(metric)}</StatusBadge>
      {explanation ? (
        <p className="card__explanation" id={describedBy}>
          {explanation}
        </p>
      ) : null}
      {lastUpdatedAt !== undefined ? (
        <p className="card__updated">Updated {formatRelative(lastUpdatedAt)}</p>
      ) : null}
      {footer}
    </article>
  );
}

function stateText(metric: RenderedMetric): string {
  switch (metric.state) {
    case 'unavailable':
      return 'Unavailable';
    case 'not-configured':
      return 'Not configured';
    case 'no-data':
      return 'No data';
    case 'stale':
      return 'Stale';
    default:
      return 'Current';
  }
}

function slug(label: string): string {
  return label.toLowerCase().replace(/[^a-z0-9]+/g, '-');
}
