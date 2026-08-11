import { apiGet, apiPost, apiPut, http } from '@/lib/api/httpClient';
import type {
  AttributeDataTypeInfo,
  AttributeHistoryEntry,
  AttributeQuery,
  AttributeValidationRule,
  CreateAttributeRequest,
  LayerAttribute,
  MetadataLayer,
  PageResponse,
  UpdateAttributeRequest,
} from '../types';

/**
 * Data Management endpoints.
 *
 * `dataTypes` and `reservedWords` are what keep the create form honest: the selectable types, the
 * configuration each one needs, and the names that cannot be used all come from the server that
 * enforces them. A client-side copy of any of the three would eventually offer something the
 * server refuses, which is a validation error the operator cannot act on.
 */
export const dataManagementApi = {
  layers: () => apiGet<MetadataLayer[]>('/data-management/layers'),

  attributes: (query: AttributeQuery = {}) =>
    apiGet<PageResponse<LayerAttribute>>('/data-management/attributes', { params: query }),

  attribute: (id: string) => apiGet<LayerAttribute>(`/data-management/attributes/${id}`),

  create: (payload: CreateAttributeRequest) =>
    apiPost<LayerAttribute>('/data-management/attributes', payload),

  update: (id: string, payload: UpdateAttributeRequest) =>
    apiPut<LayerAttribute>(`/data-management/attributes/${id}`, payload),

  deactivate: (id: string, reason?: string) =>
    apiPost<LayerAttribute>(`/data-management/attributes/${id}/deactivate`, { reason }),

  reactivate: (id: string, reason?: string) =>
    apiPost<LayerAttribute>(`/data-management/attributes/${id}/reactivate`, { reason }),

  history: (id: string, page = 0, size = 25) =>
    apiGet<PageResponse<AttributeHistoryEntry>>(
      `/data-management/attributes/${id}/history`,
      { params: { page, size } },
    ),

  rules: (id: string) =>
    apiGet<AttributeValidationRule[]>(`/data-management/attributes/${id}/rules`),

  dataTypes: () => apiGet<AttributeDataTypeInfo[]>('/data-management/data-types'),

  reservedWords: () => apiGet<string[]>('/data-management/reserved-words'),

  /**
   * Downloads the data dictionary for whatever the grid is currently showing.
   *
   * Goes through `http` rather than `apiGet` because the response is a file, not JSON — and
   * through the same axios instance so the download carries the access token and the silent
   * refresh applies. Opening a bare URL in a new tab would send no Authorization header and would
   * 401 for a user who is signed in, which is a confusing way to fail.
   */
  exportCatalogue: async (query: AttributeQuery = {}): Promise<Blob> => {
    const response = await http.get('/data-management/attributes/export', {
      params: query,
      responseType: 'blob',
    });
    return response.data as Blob;
  },
};

/** Hands a downloaded blob to the browser as a file, then releases the object URL. */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  // Revoking immediately can cancel the download in Safari, which reads the URL asynchronously.
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
