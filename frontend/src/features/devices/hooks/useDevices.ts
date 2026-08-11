import {
  useMutation,
  useQuery,
  useQueryClient,
  keepPreviousData,
} from '@tanstack/react-query';
import { devicesApi } from '../api/devicesApi';
import type {
  CommunicationType,
  DeviceListQuery,
  DeviceProtocol,
  DeviceRequest,
  DeviceSource,
  DeviceType,
} from '../types';

export const queryKeys = {
  devices: {
    all: ['devices'] as const,
    lists: () => [...queryKeys.devices.all, 'list'] as const,
    list: (query: DeviceListQuery) => [...queryKeys.devices.lists(), query] as const,
    detail: (id: string) => [...queryKeys.devices.all, 'detail', id] as const,
    communicationTypes: () => [...queryKeys.devices.all, 'communication-types'] as const,
  },
} as const;

const PAGE_SIZE = 25;

export function useDeviceList(
  search: string,
  deviceType: DeviceType | undefined,
  transport: CommunicationType | undefined,
  source: DeviceSource | undefined,
  protocol: DeviceProtocol | undefined,
  page: number,
) {
  const query: DeviceListQuery = {
    search: search.trim() || undefined,
    deviceType: deviceType || undefined,
    transport: transport || undefined,
    source: source || undefined,
    protocol: protocol || undefined,
    page,
    size: PAGE_SIZE,
  };
  return useQuery({
    queryKey: queryKeys.devices.list(query),
    queryFn: () => devicesApi.list(query),
    placeholderData: keepPreviousData,
  });
}

/**
 * The device list as a picker feeds on: one page, wide, optionally narrowed by type.
 *
 * Separate from {@link useDeviceList} because the two want opposite things. The register's grid is
 * paged at 25 with every filter the screen offers; a dropdown wants as many devices as it can
 * reasonably render and no paging control at all, since an operator scrolling a picker has no page
 * buttons to reach page two with.
 *
 * It reuses `devicesApi` and the same query keys rather than introducing a second device list —
 * a second list is exactly the drift that leaves two screens disagreeing about which devices exist.
 *
 * @param deviceType narrows the list, which is what keeps the 200 bound from biting: a picker on a
 *                   large estate is used with a type already chosen
 */
export function useDevicePicker(deviceType?: DeviceType | undefined) {
  const query: DeviceListQuery = { deviceType: deviceType || undefined, page: 0, size: 200 };
  return useQuery({
    queryKey: queryKeys.devices.list(query),
    queryFn: () => devicesApi.list(query),
    staleTime: 60_000,
  });
}

export function useDevice(id: string | undefined) {
  return useQuery({
    queryKey: queryKeys.devices.detail(id ?? ''),
    queryFn: () => devicesApi.get(id!),
    enabled: Boolean(id),
  });
}

/**
 * The communication-type field catalogue.
 *
 * Effectively static — it changes only when the platform gains a transport — so it is cached for
 * the session rather than refetched every time the registration dialog opens.
 */
export function useCommunicationTypes() {
  return useQuery({
    queryKey: queryKeys.devices.communicationTypes(),
    queryFn: () => devicesApi.communicationTypes(),
    staleTime: Infinity,
  });
}

export function useRegisterDevice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: DeviceRequest) => devicesApi.register(payload),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.devices.lists() }),
  });
}

export function useUpdateDevice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: DeviceRequest }) =>
      devicesApi.update(id, payload),
    onSuccess: (data, { id }) => {
      qc.setQueryData(queryKeys.devices.detail(id), data);
      qc.invalidateQueries({ queryKey: queryKeys.devices.lists() });
    },
  });
}
