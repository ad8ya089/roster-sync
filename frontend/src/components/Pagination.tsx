export function Pagination({
  page,
  size,
  total,
  onPage,
}: {
  page: number;
  size: number;
  total: number;
  onPage: (page: number) => void;
}) {
  return (
    <nav className="pagination" aria-label="Pagination">
      <button
        className="button button-secondary"
        disabled={page === 0}
        onClick={() => onPage(page - 1)}
      >
        Previous
      </button>
      <span>
        Page {(page + 1).toLocaleString("en-US")} of{" "}
        {Math.max(1, Math.ceil(total / size)).toLocaleString("en-US")} (
        {total.toLocaleString("en-US")} records)
      </span>
      <button
        className="button button-secondary"
        disabled={(page + 1) * size >= total}
        onClick={() => onPage(page + 1)}
      >
        Next
      </button>
    </nav>
  );
}
