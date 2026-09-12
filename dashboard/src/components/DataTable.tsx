import type { ReactNode } from 'react';

/**
 * A table with headers that mean something to a screen reader.
 *
 * <p>`<caption>` names it, `scope="col"` ties every cell to its column, and the whole thing sits
 * in a scroll container with `tabIndex={0}` so a keyboard user can reach the overflow on a narrow
 * viewport. Wide operational tables on a phone are the case where a purely visual scroll region
 * becomes unreachable without a mouse.
 */

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  numeric?: boolean;
  /** A short note rendered under the header, for columns whose name is not self-explanatory. */
  hint?: string;
}

interface Props<T> {
  caption: string;
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  emptyMessage?: string;
  captionVisible?: boolean;
}

export function DataTable<T>({
  caption,
  columns,
  rows,
  rowKey,
  emptyMessage = 'No rows.',
  captionVisible = false
}: Props<T>) {
  if (rows.length === 0) {
    return (
      <p className="notice notice--muted" role="status">
        {emptyMessage}
      </p>
    );
  }
  return (
    <div className="table-scroll" tabIndex={0} role="region" aria-label={caption}>
      <table className="table">
        <caption className={captionVisible ? undefined : 'visually-hidden'}>{caption}</caption>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.key} scope="col" className={column.numeric ? 'numeric' : undefined}>
                {column.header}
                {column.hint ? <span className="th__hint">{column.hint}</span> : null}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={rowKey(row)}>
              {columns.map((column) => (
                <td key={column.key} className={column.numeric ? 'numeric' : undefined}>
                  {column.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
