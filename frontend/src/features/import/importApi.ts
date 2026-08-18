import { apiGet, http } from '@/lib/api/httpClient';
import type { PageResponse } from '@/features/users/types';
import type { GeometryKind } from './catalogue';

/**
 * A field a source column can be mapped onto, as the server's attribute catalogue describes it.
 *
 * `aliases` are the column headings the auto-mapper should treat as this field. They come from the
 * server so that a newly created attribute is auto-matched by its own name and label without the
 * client being taught about it.
 */
export interface TargetField {
  fieldName: string;
  displayName: string;
  description: string | null;
  dataType: string;
  mandatory: boolean;
  sampleValue: string | null;
  aliases: string[];
}

/** Phase-1 result: the columns the file carries, plus a few sample rows to map against. */
export interface ColumnAnalysis {
  columns: string[];
  sampleRows: Record<string, string>[];
  format: string;
}

/** One row's outcome, present for every row that was not a plain new-row import. */
export interface ImportRowDetail {
  row: number;
  outcome: 'REPLACED' | 'SKIPPED' | 'FAILED';
  message: string;
}

export interface ImportJobStatus {
  state: string;
  total: number;
  imported: number;
  replaced: number;
  skipped: number;
  failed: number;
  rows: ImportRowDetail[];
}

/** One row in the import history list — a past run's counts, without its per-row detail. */
export interface ImportRunSummary {
  id: string;
  jobId: string | null;
  fileName: string | null;
  format: string;
  assetType: string;
  layerId: string | null;
  state: string;
  total: number;
  imported: number;
  replaced: number;
  skipped: number;
  failed: number;
  actorUsername: string | null;
  startedAt: string;
  finishedAt: string | null;
  errorMessage: string | null;
}

export interface ImportRunDetail {
  summary: ImportRunSummary;
  rows: ImportRowDetail[];
}

/**
 * What committing a file with a given mapping would do, before anything is written.
 *
 * `duplicatesSkipped` covers rows that repeat an asset code or a duplicate-check field combination
 * already claimed earlier in the same file — the real import drops them the same way. Field-level
 * validation is not previewed; a row that would fail mandatory or type checks still counts toward
 * `toCreate` or `toReplace` here and only shows up as a failure once the import actually runs.
 */
export interface ImportPreview {
  totalRows: number;
  toCreate: number;
  toReplace: number;
  duplicatesSkipped: number;
}

/**
 * Bulk import, two phase.
 *
 * The backend deliberately refuses to guess column names: a utility's file has `Meter_No` and
 * `Easting`, not `asset_code` and `lon`, and a silent mis-map is worse than a failure. So the
 * file is analysed first, the operator maps the columns, and the mapping travels with the import.
 */
export const importApi = {
  analyze: (file: File | Blob, filename: string) => {
    const form = new FormData();
    form.append('file', file, filename);
    return http
      .post<ColumnAnalysis>('/assets/bulk-import/analyze', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data);
  },

  /**
   * The fields this layer's columns can be mapped onto, from the Data Management catalogue.
   *
   * Not a constant in this client. The catalogue is the platform's field list, so an attribute an
   * administrator created this morning appears in this dropdown this afternoon — with its label,
   * description, sample value and the alternative column headings the auto-mapper should match it
   * against — without a release in either tier. `geometry` is passed because the server, not the
   * client, decides that a polygon layer has no longitude and latitude to map.
   */
  targetFields: (assetType: string, geometry: GeometryKind) =>
    apiGet<TargetField[]>('/assets/bulk-import/target-fields', {
      params: { assetType, geometry },
    }),

  /** Resolves every row against existing assets without writing anything — for the pre-import confirm step. */
  preview: (file: File | Blob, filename: string, mapping: Record<string, string>, defaultType: string) => {
    const form = new FormData();
    form.append('file', file, filename);
    form.append('mapping', JSON.stringify(mapping));
    form.append('defaultType', defaultType);
    return http
      .post<ImportPreview>('/assets/bulk-import/preview', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data);
  },

  start: (file: File | Blob, filename: string, mapping: Record<string, string>, defaultType: string) => {
    const form = new FormData();
    form.append('file', file, filename);
    // Multipart: a nested JSON part would need a per-part content type, which browsers do not
    // set for FormData. A JSON string in a text field is what the endpoint expects.
    form.append('mapping', JSON.stringify(mapping));
    form.append('defaultType', defaultType);
    return http
      .post<{ jobId: string; status: string }>('/assets/bulk-import', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data);
  },

  status: (jobId: string) => apiGet<ImportJobStatus>(`/assets/bulk-import/${jobId}`),

  /** Persisted run history, newest first — survives closing the tab that started the import. */
  history: (page = 0, size = 20) =>
    apiGet<PageResponse<ImportRunSummary>>('/assets/bulk-import/history', { params: { page, size } }),

  historyDetail: (id: string) =>
    apiGet<ImportRunDetail>(`/assets/bulk-import/history/${id}`),
};
