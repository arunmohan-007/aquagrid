import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AxiosError } from 'axios';
import { simulatorApi } from '../api/simulatorApi';
import type { SimulatedFault } from '../types';

export const queryKeys = {
  simulator: {
    all: ['simulator'] as const,
    status: () => [...queryKeys.simulator.all, 'status'] as const,
    devices: () => [...queryKeys.simulator.all, 'devices'] as const,
  },
} as const;

/**
 * The fleet moves on the simulator's interval, not the operator's attention span, so these poll.
 *
 * Ten seconds rather than the receiver console's thirty: this screen is used while deliberately
 * changing something — injecting a fault, stepping the clock, cutting a meter over — and the delay
 * between acting and seeing the result is the whole experience. The queries are in-memory reads of
 * fleet state, not database scans.
 */
const REFETCH_MS = 10_000;

/** A 404 here means the simulator is not enabled server-side, which is a state, not an error. */
export function isDisabled(error: unknown): boolean {
  return error instanceof AxiosError && error.response?.status === 404;
}

export function useSimulatorStatus() {
  return useQuery({
    queryKey: queryKeys.simulator.status(),
    queryFn: () => simulatorApi.status(),
    refetchInterval: REFETCH_MS,
    placeholderData: keepPreviousData,
    // A deployment without the simulator answers 404 on every call. Retrying it three times per
    // poll would turn an expected absence into a stream of failed requests.
    retry: (failureCount, error) => !isDisabled(error) && failureCount < 2,
  });
}

export function useSimulatedDevices(enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.simulator.devices(),
    queryFn: () => simulatorApi.devices(),
    enabled,
    refetchInterval: REFETCH_MS,
    placeholderData: keepPreviousData,
  });
}

/**
 * Every control refreshes both queries.
 *
 * Deliberately not optimistic. These mutations are the point of the screen — the operator is
 * verifying that the platform reacted — so showing a predicted result would be showing them the
 * one thing they came here not to trust.
 */
function useSimulatorMutation<TArgs>(fn: (args: TArgs) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.simulator.all });
    },
  });
}

export function useStartSimulator() {
  return useSimulatorMutation(() => simulatorApi.start());
}

export function usePauseSimulator() {
  return useSimulatorMutation(() => simulatorApi.pause());
}

export function useReloadFleet() {
  return useSimulatorMutation(() => simulatorApi.reload());
}

export function useStepSimulator() {
  return useSimulatorMutation((ticks: number) => simulatorApi.step(ticks));
}

export function useInjectFault() {
  return useSimulatorMutation(({ deviceId, fault }: { deviceId: string; fault: SimulatedFault }) =>
    simulatorApi.injectFault(deviceId, fault),
  );
}

export function useSuspendDevice() {
  return useSimulatorMutation(({ deviceId, suspended }: { deviceId: string; suspended: boolean }) =>
    simulatorApi.suspend(deviceId, suspended),
  );
}
