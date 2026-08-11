import { apiGet, apiPost, apiPut } from '@/lib/api/httpClient';
import type {
  CommunicationTypeDefinition,
  Device,
  DeviceListQuery,
  DeviceRequest,
  PageResponse,
} from '../types';

/**
 * Module 6 endpoints.
 *
 * `communicationTypes` is what makes the registration form communication-independent: the fields
 * for NB-IoT, 4G and LoRaWAN are fetched from the server rather than duplicated here, so the two
 * cannot drift.
 */
export const devicesApi = {
  list: (query: DeviceListQuery = {}) =>
    apiGet<PageResponse<Device>>('/devices', { params: query }),

  get: (id: string) => apiGet<Device>(`/devices/${id}`),

  register: (payload: DeviceRequest) => apiPost<Device>('/devices', payload),

  update: (id: string, payload: DeviceRequest) => apiPut<Device>(`/devices/${id}`, payload),

  communicationTypes: () =>
    apiGet<CommunicationTypeDefinition[]>('/devices/communication-types'),
};
