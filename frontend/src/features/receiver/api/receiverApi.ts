import { apiGet } from '@/lib/api/httpClient';
import type {
  PacketSearchQuery,
  PageResponse,
  ReceiverPacket,
  ReportingDevice,
  TransportStatus,
} from '../types';

/**
 * Module 18 endpoints.
 *
 * Read-only. The receiver's write side is the ingress endpoint, and that is spoken by gateways and
 * meters — never by this client, which is why there is no `send` here.
 */
export const receiverApi = {
  /** Every device that reported in the window, quietest first. */
  reportingDevices: (hours: number) =>
    apiGet<ReportingDevice[]>('/receiver/devices', { params: { hours } }),

  /**
   * What one device sent, newest first, with the values each packet carried.
   *
   * The only view that returns readings — the cross-device search deliberately omits them, because
   * attaching values there would cost a query per device on the page.
   */
  devicePackets: (deviceId: string, query: PacketSearchQuery = {}) =>
    apiGet<PageResponse<ReceiverPacket>>(`/receiver/devices/${deviceId}/packets`, {
      params: query,
    }),

  searchPackets: (query: PacketSearchQuery = {}) =>
    apiGet<PageResponse<ReceiverPacket>>('/receiver/packets', { params: query }),

  transports: () => apiGet<TransportStatus[]>('/receiver/transports'),
};
