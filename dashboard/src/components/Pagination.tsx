import { formatNumber } from '../format';

/** Page controls that say where you are, because "Next" alone is not enough on a long table. */

interface Props {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  onPage: (page: number) => void;
  onSize?: (size: number) => void;
  sizes?: number[];
}

export function Pagination({
  page,
  size,
  totalElements,
  totalPages,
  onPage,
  onSize,
  sizes = [25, 50, 100, 200]
}: Props) {
  const first = totalElements === 0 ? 0 : page * size + 1;
  const last = Math.min((page + 1) * size, totalElements);

  return (
    <nav className="pagination" aria-label="Pagination">
      <p className="pagination__status" role="status">
        {totalElements === 0
          ? 'No rows'
          : `Rows ${formatNumber(first)}–${formatNumber(last)} of ${formatNumber(totalElements)}`}
      </p>
      <div className="pagination__controls">
        <button type="button" onClick={() => onPage(0)} disabled={page === 0}>
          First
        </button>
        <button type="button" onClick={() => onPage(page - 1)} disabled={page === 0}>
          Previous
        </button>
        <span className="pagination__page">
          Page {page + 1} of {Math.max(totalPages, 1)}
        </span>
        <button
          type="button"
          onClick={() => onPage(page + 1)}
          disabled={page + 1 >= totalPages}
        >
          Next
        </button>
        {onSize ? (
          <label className="pagination__size">
            Rows per page
            <select value={size} onChange={(event) => onSize(Number(event.target.value))}>
              {sizes.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </label>
        ) : null}
      </div>
    </nav>
  );
}
