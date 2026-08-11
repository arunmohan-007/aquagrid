import { apiGet, apiPost } from '@/lib/api/httpClient';
import type { SimulatedDevice, SimulatedFault, SimulatorStatus } from '../types';

/**
 * Module 17 endpoints.
 *
 * Every route is gated on `iot:simulator:run`, including the reads: the fleet view lists device
 * codes, addresses and fault state for the whole estate, which is operational detail rather than a
 * dashboard.
 *
 * These routes only exist when the simulator is enabled server-side. A deployment without it
 * answers 404, which is what `useSimulatorStatus` reads to decide the module is unavailable rather
 * than broken.
 */
export const simulatorApi = {
  status: () => apiGet<SimulatorStatus>('/simulator/status'),

  devices: () => apiGet<SimulatedDevice[]>('/simulator/devices'),

  start: () => apiPost<SimulatorStatus>('/simulator/start'),

  pause: () => apiPost<SimulatorStatus>('/simulator/pause'),

  /** Picks up devices registered, retired or switched between SIMULATOR and LIVE. */
  reload: () => apiPost<SimulatorStatus>('/simulator/reload'),

  /**
   * Runs N reporting intervals at once, compressing the simulated clock while every packet still
   * travels the full production path.
   */
  step: (ticks: number) =>
    apiPost<SimulatorStatus>('/simulator/step', undefined, { params: { ticks } }),

  injectFault: (deviceId: string, fault: SimulatedFault) =>
    apiPost<SimulatedDevice>(`/simulator/devices/${deviceId}/faults`, { fault }),

  /** Silences or resumes one meter, for cutover to a physical device at the same address. */
  suspend: (deviceId: string, suspended: boolean) =>
    apiPost<SimulatedDevice>(`/simulator/devices/${deviceId}/suspend`, undefined, {
      params: { suspended },
    }),
};
