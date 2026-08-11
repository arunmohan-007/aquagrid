import { useMutation } from '@tanstack/react-query';
import { reportsApi } from '../api/reportsApi';
import type { ReportRequest } from '../types';

/**
 * Downloads a report and saves it, in one action.
 *
 * A mutation rather than a query: this has a side effect (the browser save dialog) and no cached
 * value worth keeping — re-running the same filters should generate a fresh file, not replay one
 * from the query cache, because the underlying readings have very likely moved on.
 */
export function useDownloadReadings() {
  return useMutation({
    mutationFn: async (request: ReportRequest) => {
      const { blob, filename } = await reportsApi.downloadReadings(request);
      saveBlob(blob, filename ?? defaultFilename(request));
    },
  });
}

/**
 * Triggers a browser download without navigating away from the page.
 *
 * The object URL is revoked on the next tick rather than immediately: Firefox and Safari both start
 * the download asynchronously off the anchor's `href`, and revoking before that read happens hands
 * back an empty file. A `setTimeout(0)` is ugly but it is the fix every browser vendor's own bug
 * tracker converges on for this exact race.
 */
function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

function defaultFilename(request: ReportRequest): string {
  const extension = request.format === 'PDF' ? 'pdf' : 'xlsx';
  return `aquagrid-readings.${extension}`;
}
