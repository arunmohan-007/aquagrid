import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { dataManagementApi } from '../api/dataManagementApi';
import type {
  AttributeQuery,
  CreateAttributeRequest,
  UpdateAttributeRequest,
} from '../types';

export const queryKeys = {
  metadata: {
    all: ['data-management'] as const,
    layers: () => [...queryKeys.metadata.all, 'layers'] as const,
    attributes: () => [...queryKeys.metadata.all, 'attributes'] as const,
    attributeList: (query: AttributeQuery) =>
      [...queryKeys.metadata.attributes(), query] as const,
    history: (id: string) => [...queryKeys.metadata.all, 'history', id] as const,
    rules: (id: string) => [...queryKeys.metadata.all, 'rules', id] as const,
    dataTypes: () => [...queryKeys.metadata.all, 'data-types'] as const,
    reservedWords: () => [...queryKeys.metadata.all, 'reserved-words'] as const,
  },
} as const;

/**
 * The layer list.
 *
 * Long-lived: layers change when the platform gains an asset type, which is a release. The
 * attribute counts on each move more often, so this is invalidated whenever an attribute is
 * created or its active state changes rather than being polled.
 */
export function useLayers() {
  return useQuery({
    queryKey: queryKeys.metadata.layers(),
    queryFn: () => dataManagementApi.layers(),
    staleTime: 5 * 60_000,
  });
}

export function useAttributes(query: AttributeQuery) {
  return useQuery({
    queryKey: queryKeys.metadata.attributeList(query),
    queryFn: () => dataManagementApi.attributes(query),
    // Keeps the previous page on screen while the next one loads, so sorting a wide grid does not
    // blank it — the empty flash is what makes a table feel like it reloaded rather than paged.
    placeholderData: keepPreviousData,
  });
}

export function useAttributeHistory(id: string | undefined) {
  return useQuery({
    queryKey: queryKeys.metadata.history(id ?? ''),
    queryFn: () => dataManagementApi.history(id!),
    enabled: Boolean(id),
  });
}

export function useAttributeRules(id: string | undefined) {
  return useQuery({
    queryKey: queryKeys.metadata.rules(id ?? ''),
    queryFn: () => dataManagementApi.rules(id!),
    enabled: Boolean(id),
  });
}

/**
 * The data-type catalogue and the reserved-word list.
 *
 * Both are effectively static — they change only when the platform does — so they are fetched once
 * per session rather than every time the create dialog opens.
 */
export function useDataTypes() {
  return useQuery({
    queryKey: queryKeys.metadata.dataTypes(),
    queryFn: () => dataManagementApi.dataTypes(),
    staleTime: Infinity,
  });
}

export function useReservedWords() {
  return useQuery({
    queryKey: queryKeys.metadata.reservedWords(),
    queryFn: () => dataManagementApi.reservedWords(),
    staleTime: Infinity,
  });
}

/**
 * Invalidates everything a catalogue change can affect.
 *
 * Deliberately broad. Creating or retiring an attribute changes the grid, the layer's field count,
 * and — because the import wizard reads the same catalogue — the target fields it offers. Being
 * precise here would mean a stale import mapper showing a field that was retired a minute ago,
 * which is exactly the drift this module exists to remove.
 */
function useCatalogueInvalidation() {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: queryKeys.metadata.attributes() });
    qc.invalidateQueries({ queryKey: queryKeys.metadata.layers() });
    qc.invalidateQueries({ queryKey: ['import', 'target-fields'] });
  };
}

export function useCreateAttribute() {
  const invalidate = useCatalogueInvalidation();
  return useMutation({
    mutationFn: (payload: CreateAttributeRequest) => dataManagementApi.create(payload),
    onSuccess: invalidate,
  });
}

export function useUpdateAttribute() {
  const invalidate = useCatalogueInvalidation();
  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: UpdateAttributeRequest }) =>
      dataManagementApi.update(id, payload),
    onSuccess: invalidate,
  });
}

export function useSetAttributeActive() {
  const invalidate = useCatalogueInvalidation();
  return useMutation({
    mutationFn: ({ id, active, reason }: { id: string; active: boolean; reason?: string | undefined }) =>
      active
        ? dataManagementApi.reactivate(id, reason)
        : dataManagementApi.deactivate(id, reason),
    onSuccess: invalidate,
  });
}
