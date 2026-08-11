import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deviceDataConfigApi } from '../api/deviceDataConfigApi';
import type {
  CreateParameterRequest,
  DiscoveryQuery,
  ParameterQuery,
  RawTelemetryQuery,
  UpdateParameterRequest,
} from '../types';

export const queryKeys = {
  dataConfig: {
    all: ['device-data-config'] as const,
    parameters: () => [...queryKeys.dataConfig.all, 'parameters'] as const,
    parameterList: (query: ParameterQuery) =>
      [...queryKeys.dataConfig.parameters(), query] as const,
    history: (id: string) => [...queryKeys.dataConfig.all, 'history', id] as const,
    effective: (deviceId: string) => [...queryKeys.dataConfig.all, 'effective', deviceId] as const,
    deviceTypes: () => [...queryKeys.dataConfig.all, 'device-types'] as const,
    dataTypes: () => [...queryKeys.dataConfig.all, 'data-types'] as const,
    categories: () => [...queryKeys.dataConfig.all, 'categories'] as const,
    units: () => [...queryKeys.dataConfig.all, 'units'] as const,
    discovered: () => [...queryKeys.dataConfig.all, 'discovered'] as const,
    discoveredList: (query: DiscoveryQuery) =>
      [...queryKeys.dataConfig.discovered(), query] as const,
    discoverySamples: (id: string) =>
      [...queryKeys.dataConfig.all, 'discovery-samples', id] as const,
    pendingCount: () => [...queryKeys.dataConfig.all, 'pending-count'] as const,
    rawTelemetry: (query: RawTelemetryQuery) =>
      [...queryKeys.dataConfig.all, 'raw-telemetry', query] as const,
  },
} as const;

export function useParameters(query: ParameterQuery) {
  return useQuery({
    queryKey: queryKeys.dataConfig.parameterList(query),
    queryFn: () => deviceDataConfigApi.parameters(query),
    // Keeps the previous page on screen while the next loads, so paging a wide grid does not blank
    // it — the empty flash is what makes a table feel like it reloaded rather than paged.
    placeholderData: keepPreviousData,
  });
}

export function useParameterHistory(id: string | undefined) {
  return useQuery({
    queryKey: queryKeys.dataConfig.history(id ?? ''),
    queryFn: () => deviceDataConfigApi.history(id!),
    enabled: Boolean(id),
  });
}

export function useEffectiveConfig(deviceId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.dataConfig.effective(deviceId ?? ''),
    queryFn: () => deviceDataConfigApi.effective(deviceId!),
    enabled: Boolean(deviceId),
  });
}

/**
 * The four catalogues the form is built from.
 *
 * Effectively static — they change only when the platform does, or when a tenant adds a unit — so
 * they are fetched once per session rather than every time a dialog opens. `deviceTypes` is the
 * exception: its parameter counts move whenever the catalogue does, so it is invalidated on every
 * write rather than held forever.
 */
export function useDataTypes() {
  return useQuery({
    queryKey: queryKeys.dataConfig.dataTypes(),
    queryFn: () => deviceDataConfigApi.dataTypes(),
    staleTime: Infinity,
  });
}

export function useCategories() {
  return useQuery({
    queryKey: queryKeys.dataConfig.categories(),
    queryFn: () => deviceDataConfigApi.categories(),
    staleTime: Infinity,
  });
}

export function useUnits() {
  return useQuery({
    queryKey: queryKeys.dataConfig.units(),
    queryFn: () => deviceDataConfigApi.units(),
    staleTime: 10 * 60_000,
  });
}

export function useDeviceTypeSummaries() {
  return useQuery({
    queryKey: queryKeys.dataConfig.deviceTypes(),
    queryFn: () => deviceDataConfigApi.deviceTypes(),
    staleTime: 60_000,
  });
}

// ---- Discovery ---------------------------------------------------------------------------------

export function useDiscoveredParameters(query: DiscoveryQuery) {
  return useQuery({
    queryKey: queryKeys.dataConfig.discoveredList(query),
    queryFn: () => deviceDataConfigApi.discovered(query),
    placeholderData: keepPreviousData,
  });
}

/**
 * How many parameters are waiting for a decision.
 *
 * Polled rather than invalidated, because the number changes without anyone in this browser doing
 * anything — a device sends a new field and the queue grows. A minute is fast enough to notice
 * within a reporting interval and slow enough not to matter.
 */
export function usePendingDiscoveryCount(enabled = true) {
  return useQuery({
    queryKey: queryKeys.dataConfig.pendingCount(),
    queryFn: () => deviceDataConfigApi.pendingCount(),
    refetchInterval: 60_000,
    enabled,
  });
}

export function useDiscoverySamples(id: string | undefined) {
  return useQuery({
    queryKey: queryKeys.dataConfig.discoverySamples(id ?? ''),
    queryFn: () => deviceDataConfigApi.discoverySamples(id!),
    enabled: Boolean(id),
  });
}

export function useRawTelemetry(query: RawTelemetryQuery, enabled = true) {
  return useQuery({
    queryKey: queryKeys.dataConfig.rawTelemetry(query),
    queryFn: () => deviceDataConfigApi.rawTelemetry(query),
    placeholderData: keepPreviousData,
    enabled,
  });
}

// ---- Mutations ---------------------------------------------------------------------------------

/**
 * Invalidates everything a configuration change can affect.
 *
 * Deliberately broad. Creating a parameter changes the grid, the device type's parameter count, the
 * effective configuration of every device of that type, and — because configuring a parameter closes
 * the discovery rows it answers — the discovered queue and its badge. Being precise here would leave
 * a discovery list still asking about a parameter that was defined a moment ago, which is the
 * fastest way to make a queue nobody reads.
 */
function useConfigInvalidation() {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: queryKeys.dataConfig.parameters() });
    qc.invalidateQueries({ queryKey: queryKeys.dataConfig.deviceTypes() });
    qc.invalidateQueries({ queryKey: queryKeys.dataConfig.discovered() });
    qc.invalidateQueries({ queryKey: queryKeys.dataConfig.pendingCount() });
    qc.invalidateQueries({ queryKey: [...queryKeys.dataConfig.all, 'effective'] });
  };
}

export function useCreateParameter() {
  const invalidate = useConfigInvalidation();
  return useMutation({
    mutationFn: (payload: CreateParameterRequest) => deviceDataConfigApi.create(payload),
    onSuccess: invalidate,
  });
}

export function useUpdateParameter() {
  const invalidate = useConfigInvalidation();
  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: UpdateParameterRequest }) =>
      deviceDataConfigApi.update(id, payload),
    onSuccess: invalidate,
  });
}

export function useSetParameterActive() {
  const invalidate = useConfigInvalidation();
  return useMutation({
    mutationFn: ({
      id,
      active,
      reason,
    }: {
      id: string;
      active: boolean;
      reason?: string | undefined;
    }) =>
      active
        ? deviceDataConfigApi.reactivate(id, reason)
        : deviceDataConfigApi.deactivate(id, reason),
    onSuccess: invalidate,
  });
}

export function useIgnoreDiscovery() {
  const invalidate = useConfigInvalidation();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string | undefined }) =>
      deviceDataConfigApi.ignoreDiscovery(id, reason),
    onSuccess: invalidate,
  });
}

export function useRestoreDiscovery() {
  const invalidate = useConfigInvalidation();
  return useMutation({
    mutationFn: (id: string) => deviceDataConfigApi.restoreDiscovery(id),
    onSuccess: invalidate,
  });
}

export function useCreateUnit() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: { code: string; label: string; quantity: string; description?: string }) =>
      deviceDataConfigApi.createUnit(payload),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.dataConfig.units() }),
  });
}
