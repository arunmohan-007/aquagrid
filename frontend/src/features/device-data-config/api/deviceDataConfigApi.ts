import { apiGet, apiPost, apiPut } from '@/lib/api/httpClient';
import type {
  CategoryInfo,
  CreateParameterRequest,
  DataTypeInfo,
  DeviceParameter,
  DeviceTypeSummary,
  DiscoveredParameter,
  DiscoveryQuery,
  EffectiveConfig,
  PageResponse,
  ParameterHistoryEntry,
  ParameterQuery,
  RawTelemetry,
  RawTelemetryQuery,
  UnitInfo,
  UpdateParameterRequest,
} from '../types';

/**
 * Device Data Configuration endpoints.
 *
 * `dataTypes`, `categories`, `units` and `deviceTypes` are what keep the form honest: the selectable
 * types, the configuration each one accepts, the reading groups and the unit list all come from the
 * server that enforces them. A client-side copy of any of the four would eventually offer something
 * the server refuses.
 */
export const deviceDataConfigApi = {
  parameters: (query: ParameterQuery = {}) =>
    apiGet<PageResponse<DeviceParameter>>('/device-data-config/parameters', { params: query }),

  parameter: (id: string) =>
    apiGet<DeviceParameter>(`/device-data-config/parameters/${id}`),

  create: (payload: CreateParameterRequest) =>
    apiPost<DeviceParameter>('/device-data-config/parameters', payload),

  update: (id: string, payload: UpdateParameterRequest) =>
    apiPut<DeviceParameter>(`/device-data-config/parameters/${id}`, payload),

  deactivate: (id: string, reason?: string) =>
    apiPost<DeviceParameter>(`/device-data-config/parameters/${id}/deactivate`, { reason }),

  reactivate: (id: string, reason?: string) =>
    apiPost<DeviceParameter>(`/device-data-config/parameters/${id}/reactivate`, { reason }),

  history: (id: string, page = 0, size = 25) =>
    apiGet<PageResponse<ParameterHistoryEntry>>(
      `/device-data-config/parameters/${id}/history`,
      { params: { page, size } },
    ),

  /** Template and overrides already combined, exactly as the reception path resolves them. */
  effective: (deviceId: string) =>
    apiGet<EffectiveConfig>(`/device-data-config/devices/${deviceId}/effective`),

  deviceTypes: () => apiGet<DeviceTypeSummary[]>('/device-data-config/device-types'),

  dataTypes: () => apiGet<DataTypeInfo[]>('/device-data-config/data-types'),

  categories: () => apiGet<CategoryInfo[]>('/device-data-config/categories'),

  units: () => apiGet<UnitInfo[]>('/device-data-config/units'),

  createUnit: (payload: { code: string; label: string; quantity: string; description?: string }) =>
    apiPost<UnitInfo>('/device-data-config/units', payload),

  // ---- Discovery -----------------------------------------------------------------------------

  discovered: (query: DiscoveryQuery = {}) =>
    apiGet<PageResponse<DiscoveredParameter>>('/device-data-config/discovered', { params: query }),

  pendingCount: () =>
    apiGet<{ pending: number }>('/device-data-config/discovered/pending-count'),

  /** Recent payloads that actually carried this parameter — the View Raw Data action. */
  discoverySamples: (id: string, limit = 10) =>
    apiGet<RawTelemetry[]>(`/device-data-config/discovered/${id}/samples`, { params: { limit } }),

  ignoreDiscovery: (id: string, reason?: string) =>
    apiPost<DiscoveredParameter>(`/device-data-config/discovered/${id}/ignore`, { reason }),

  restoreDiscovery: (id: string) =>
    apiPost<DiscoveredParameter>(`/device-data-config/discovered/${id}/restore`, {}),

  // ---- Raw payloads --------------------------------------------------------------------------

  rawTelemetry: (query: RawTelemetryQuery = {}) =>
    apiGet<PageResponse<RawTelemetry>>('/device-data-config/raw-telemetry', { params: query }),

  rawPayload: (id: string) =>
    apiGet<RawTelemetry>(`/device-data-config/raw-telemetry/${id}`),
};
