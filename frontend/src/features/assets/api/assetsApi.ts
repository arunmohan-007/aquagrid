import { apiDelete, apiGet, apiPatch, apiPost, http } from '@/lib/api/httpClient';
import type {
  Asset,
  AssetListQuery,
  AssetRequest,
  AttachmentSummary,
  PageResponse,
} from '../types';

/**
 * Module 23 endpoints. Attachments use multipart upload; download streams bytes via a direct
 * fetch so the auth header is attached by the axios interceptor.
 */
export const assetsApi = {
  list: (query: AssetListQuery = {}) =>
    apiGet<PageResponse<Asset>>('/assets', { params: query }),

  get: (id: string) => apiGet<Asset>(`/assets/${id}`),

  create: (payload: AssetRequest) => apiPost<Asset>('/assets', payload),

  update: (id: string, payload: AssetRequest) => apiPatch<Asset>(`/assets/${id}`, payload),

  delete: (id: string) => apiDelete<void>(`/assets/${id}`),

  // --- Attachments ----------------------------------------------------------------------

  listAttachments: (assetId: string) =>
    apiGet<AttachmentSummary[]>(`/assets/${assetId}/attachments`),

  uploadAttachment: (assetId: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    // Let the browser set the multipart boundary; do not force Content-Type.
    return http.post<AttachmentSummary>(`/assets/${assetId}/attachments`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then((r) => r.data);
  },

  deleteAttachment: (attachmentId: string) =>
    apiDelete<void>(`/assets/attachments/${attachmentId}`),

  /** Returns a URL that streams the bytes, with the auth header attached via axios. */
  downloadAttachmentUrl: (attachmentId: string) =>
    `/api/v1/assets/attachments/${attachmentId}`,

};
