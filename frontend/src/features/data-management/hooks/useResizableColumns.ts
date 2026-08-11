import { useCallback, useEffect, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import type { ColumnDef } from '../labels';

/**
 * Drag-to-resize column widths for the attribute grid.
 *
 * Eighteen columns do not fit a laptop, and which of them matters depends entirely on the job:
 * someone writing a data dictionary lives in Description, someone auditing a migration lives in
 * Created Date. Rather than choose for them, every column resizes.
 *
 * The drag listeners go on `window`, not on the handle. A pointer moving faster than React
 * re-renders leaves the handle behind, and a handle-scoped `mousemove` then stops firing mid-drag —
 * the column freezes and the user is left holding a mouse button that does nothing. Window
 * listeners follow the pointer wherever it goes, including outside the browser frame.
 *
 * Widths are session state, not persisted. A stored layout has to be migrated when the column set
 * changes, and a stale one is worse than a default — it hides a column the user has never seen.
 * Re-dragging is cheap; a layout that silently omits a new field is not.
 */
export function useResizableColumns(columns: ColumnDef[], minWidth = 64) {
  const [widths, setWidths] = useState<Record<string, number>>(() =>
    Object.fromEntries(columns.map((column) => [column.id, column.width])),
  );
  const [resizing, setResizing] = useState<string | null>(null);
  const drag = useRef<{ id: string; startX: number; startWidth: number } | null>(null);

  const beginResize = useCallback(
    (id: string, event: ReactMouseEvent) => {
      // Without this the browser starts a text selection, and the whole grid highlights blue as
      // the pointer sweeps across it.
      event.preventDefault();
      event.stopPropagation();
      drag.current = { id, startX: event.clientX, startWidth: widths[id] ?? minWidth };
      setResizing(id);
    },
    [widths, minWidth],
  );

  useEffect(() => {
    if (!resizing) return undefined;

    const onMove = (event: globalThis.MouseEvent) => {
      const current = drag.current;
      if (!current) return;
      const next = Math.max(minWidth, current.startWidth + (event.clientX - current.startX));
      setWidths((previous) => ({ ...previous, [current.id]: next }));
    };
    const onUp = () => {
      drag.current = null;
      setResizing(null);
    };

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    // The col-resize cursor is set on the body so it survives leaving the header cell mid-drag.
    const previousCursor = document.body.style.cursor;
    document.body.style.cursor = 'col-resize';

    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      document.body.style.cursor = previousCursor;
    };
  }, [resizing, minWidth]);

  return { widths, beginResize, resizing };
}
