import { apiGet } from '@/lib/api/httpClient';
import type { DeviceTelemetry, MetricSeries } from '../types';

/**
 * Module 7 endpoints.
 *
 * Read-only, and gated on `iot:device:read` rather than `iot:receiver:read`: this is what a meter
 * reads, not how the packet carrying it was authenticated.
 */
export const telemetryApi = {
  /** A device's identity, state and latest value of every metric, grouped by what it describes. */
  device: (deviceId: string) => apiGet<DeviceTelemetry>(`/devices/${deviceId}/telemetry`),

  /** One metric's history, oldest first. */
  series: (deviceId: string, metric: string, hours: number) =>
    apiGet<MetricSeries>(`/devices/${deviceId}/telemetry/series`, {
      params: { metric, hours },
    }),
};
