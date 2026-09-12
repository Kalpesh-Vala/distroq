import { formatNumber } from '../format';

/**
 * A bar chart that a screen reader can read, because it is also a table.
 *
 * <p>The SVG is `aria-hidden` and the same numbers are rendered underneath in a real `<table>`,
 * visually hidden by default. That is more reliable than decorating the SVG with ARIA: the table
 * is a data structure assistive technology already understands, and it stays correct if the chart
 * is ever restyled.
 *
 * <p>No charting dependency. A grouped bar chart over three to ten categories is a handful of
 * rectangles, and a library would be a larger download than this whole application.
 */

export interface Series {
  label: string;
  values: (number | null)[];
  tone?: 'ok' | 'info' | 'warn' | 'error';
}

interface Props {
  title: string;
  categories: string[];
  series: Series[];
  /** Rendered in place of the chart when every value is null. */
  emptyMessage?: string;
}

export function BarChart({ title, categories, series, emptyMessage = 'No data.' }: Props) {
  const everyValue = series.flatMap((entry) => entry.values).filter((v): v is number => v !== null);
  if (everyValue.length === 0) {
    return (
      <figure className="chart">
        <figcaption>{title}</figcaption>
        <p className="notice notice--muted" role="status">
          {emptyMessage}
        </p>
      </figure>
    );
  }

  const max = Math.max(...everyValue, 1);
  const groupWidth = 100 / Math.max(categories.length, 1);
  const barWidth = groupWidth / (series.length + 0.5);

  return (
    <figure className="chart">
      <figcaption>{title}</figcaption>
      <svg
        className="chart__svg"
        viewBox="0 0 100 44"
        preserveAspectRatio="none"
        role="presentation"
        aria-hidden="true"
        focusable="false"
      >
        {categories.map((category, categoryIndex) =>
          series.map((entry, seriesIndex) => {
            const value = entry.values[categoryIndex] ?? 0;
            const height = (value / max) * 40;
            return (
              <rect
                key={`${category}-${entry.label}`}
                className={`chart__bar chart__bar--${entry.tone ?? 'info'}`}
                x={categoryIndex * groupWidth + seriesIndex * barWidth + barWidth * 0.25}
                y={40 - height}
                width={barWidth * 0.8}
                height={Math.max(height, value > 0 ? 0.6 : 0)}
              />
            );
          })
        )}
        <line className="chart__axis" x1="0" y1="40" x2="100" y2="40" />
      </svg>
      <ul className="chart__legend">
        {series.map((entry) => (
          <li key={entry.label} className={`chart__key chart__key--${entry.tone ?? 'info'}`}>
            {entry.label}
          </li>
        ))}
      </ul>
      <table className="visually-hidden">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">Category</th>
            {series.map((entry) => (
              <th key={entry.label} scope="col">
                {entry.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {categories.map((category, index) => (
            <tr key={category}>
              <th scope="row">{category}</th>
              {series.map((entry) => (
                <td key={entry.label}>
                  {entry.values[index] === null ? 'No data' : formatNumber(entry.values[index])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}
