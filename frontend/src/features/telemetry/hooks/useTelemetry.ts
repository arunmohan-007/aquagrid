import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { telemetryApi } from '../api/telemetryApi';

export const queryKeys = {
  telemetry: {
    all: ['telemetry'] as const,
    device: (deviceId: string) => [...queryKeys.telemetry.all, 'device', deviceId] as const,
    series: (deviceId: string, metric: string, hours: number) =>
      [...queryKeys.telemetry.all, 'series', deviceId, metric, hours] as const,
  },
} as const;

/**
 * Thirty seconds, matching the receiver console.
 *
 * A meter's schedule is measured in minutes at best — many report every six hours — so polling
 * faster would spend requests to redisplay the same number. The reading's own age is what tells the
 * operator whether it is current, not how recently the browser asked.
 */
const REFETCH_MS = 30_000;

export function useDeviceTelemetry(deviceId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.telemetry.device(deviceId ?? ''),
    queryFn: () => telemetryApi.device(deviceId!),
    enabled: Boolean(deviceId),
    refetchInterval: REFETCH_MS,
    placeholderData: keepPreviousData,
  });
}

export function useMetricSeries(
  deviceId: string | undefined,
  metric: string | undefined,
  hours: number,
) {
  return useQuery({
    queryKey: queryKeys.telemetry.series(deviceId ?? '', metric ?? '', hours),
    queryFn: () => telemetryApi.series(deviceId!, metric!, hours),
    enabled: Boolean(deviceId && metric),
    refetchInterval: REFETCH_MS,
    placeholderData: keepPreviousData,
  });
}
