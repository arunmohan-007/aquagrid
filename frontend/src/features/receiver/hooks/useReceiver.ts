import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { receiverApi } from '../api/receiverApi';
import type { PacketSearchQuery } from '../types';

export const queryKeys = {
  receiver: {
    all: ['receiver'] as const,
    devices: (hours: number) => [...queryKeys.receiver.all, 'devices', hours] as const,
    devicePackets: (deviceId: string, query: PacketSearchQuery) =>
      [...queryKeys.receiver.all, 'device-packets', deviceId, query] as const,
    packets: (query: PacketSearchQuery) => [...queryKeys.receiver.all, 'packets', query] as const,
    transports: () => [...queryKeys.receiver.all, 'transports'] as const,
  },
} as const;

export const PAGE_SIZE = 25;

/**
 * Live telemetry ages by the second, so these poll.
 *
 * Thirty seconds rather than a websocket: this is an operator watching a fleet, not a control loop,
 * and a poll costs one indexed read against a pre-aggregated table. A push channel would be a
 * second connection to keep alive and reconnect for a screen that is usually not open.
 */
const REFETCH_MS = 30_000;

export function useReportingDevices(hours: number) {
  return useQuery({
    queryKey: queryKeys.receiver.devices(hours),
    queryFn: () => receiverApi.reportingDevices(hours),
    refetchInterval: REFETCH_MS,
    placeholderData: keepPreviousData,
  });
}

export function useDevicePackets(deviceId: string | undefined, page: number) {
  const query: PacketSearchQuery = { page, size: PAGE_SIZE };
  return useQuery({
    queryKey: queryKeys.receiver.devicePackets(deviceId ?? '', query),
    queryFn: () => receiverApi.devicePackets(deviceId!, query),
    enabled: Boolean(deviceId),
    refetchInterval: REFETCH_MS,
    placeholderData: keepPreviousData,
  });
}

export function usePacketSearch(
  transport: string | undefined,
  status: PacketSearchQuery['status'],
  page: number,
  enabled: boolean,
) {
  const query: PacketSearchQuery = {
    transport: transport || undefined,
    status: status || undefined,
    page,
    size: PAGE_SIZE,
  };
  return useQuery({
    queryKey: queryKeys.receiver.packets(query),
    queryFn: () => receiverApi.searchPackets(query),
    enabled,
    refetchInterval: REFETCH_MS,
    placeholderData: keepPreviousData,
  });
}

export function useTransportStatus() {
  return useQuery({
    queryKey: queryKeys.receiver.transports(),
    queryFn: () => receiverApi.transports(),
    refetchInterval: REFETCH_MS,
  });
}
