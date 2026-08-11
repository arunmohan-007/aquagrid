import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { layersApi } from '../api/layersApi';
import type {
  CreateLayerRequest,
  LayerStatus,
  SaveStyleRequest,
  UpdateLayerRequest,
} from '../types';

export const layerKeys = {
  all: ['gis-layers'] as const,
  list: (query: Record<string, unknown>) => [...layerKeys.all, 'list', query] as const,
  detail: (id: string) => [...layerKeys.all, 'detail', id] as const,
  statistics: (id: string) => [...layerKeys.all, 'statistics', id] as const,
  fieldValues: (id: string, field: string) => [...layerKeys.all, 'field-values', id, field] as const,
  geometryTypes: () => [...layerKeys.all, 'geometry-types'] as const,
  assetTypes: () => [...layerKeys.all, 'asset-types'] as const,
  categories: () => [...layerKeys.all, 'categories'] as const,
  crs: (search: string) => [...layerKeys.all, 'crs', search] as const,
  styles: (layerId: string) => [...layerKeys.all, 'styles', layerId] as const,
  styleFields: (layerId: string) => [...layerKeys.all, 'style-fields', layerId] as const,
  vocabulary: () => [...layerKeys.all, 'vocabulary'] as const,
  templates: (layerId: string) => [...layerKeys.all, 'templates', layerId] as const,
  symbols: () => [...layerKeys.all, 'symbols'] as const,
  mapStyle: () => [...layerKeys.all, 'map-style'] as const,
} as const;

export interface LayerListQuery {
  status?: LayerStatus | undefined;
  category?: string | undefined;
  geometryType?: string | undefined;
  search?: string | undefined;
  withCounts?: boolean | undefined;
}

export function useGisLayers(query: LayerListQuery) {
  return useQuery({
    queryKey: layerKeys.list(query as Record<string, unknown>),
    queryFn: () => layersApi.list(query),
  });
}

export function useGisLayer(layerId: string | undefined) {
  return useQuery({
    queryKey: layerKeys.detail(layerId ?? ''),
    queryFn: () => layersApi.get(layerId!),
    enabled: Boolean(layerId),
  });
}

export function useLayerStatistics(layerId: string | undefined) {
  return useQuery({
    queryKey: layerKeys.statistics(layerId ?? ''),
    queryFn: () => layersApi.statistics(layerId!),
    enabled: Boolean(layerId),
  });
}

/**
 * The values a field holds, for the categorical and graduated editors.
 *
 * Not cached for long: this is a scan of live data, and an administrator who has just imported a
 * file expects the new categories to appear. Thirty seconds is enough to stop the query re-running
 * on every keystroke in the rule builder without pretending the answer is stable.
 */
export function useFieldValues(layerId: string | undefined, fieldName: string | undefined) {
  return useQuery({
    queryKey: layerKeys.fieldValues(layerId ?? '', fieldName ?? ''),
    queryFn: () => layersApi.fieldValues(layerId!, fieldName!),
    enabled: Boolean(layerId && fieldName),
    staleTime: 30_000,
  });
}

/*
 * Reference data. Geometry types and the style vocabulary change only when the platform does, so
 * they are fetched once per session; categories and the CRS list are per tenant and per search but
 * still nearly static.
 */
export function useGeometryTypes() {
  return useQuery({
    queryKey: layerKeys.geometryTypes(),
    queryFn: () => layersApi.geometryTypes(),
    staleTime: Infinity,
  });
}

export function useAssetTypeOptions() {
  return useQuery({
    queryKey: layerKeys.assetTypes(),
    queryFn: () => layersApi.assetTypes(),
    staleTime: Infinity,
  });
}

export function useLayerCategories() {
  return useQuery({
    queryKey: layerKeys.categories(),
    queryFn: () => layersApi.categories(),
    staleTime: 5 * 60_000,
  });
}

export function useCrsOptions(search: string) {
  return useQuery({
    queryKey: layerKeys.crs(search),
    queryFn: () => layersApi.crs(search || undefined),
    staleTime: 10 * 60_000,
  });
}

export function useLayerStyles(layerId: string | undefined) {
  return useQuery({
    queryKey: layerKeys.styles(layerId ?? ''),
    queryFn: () => layersApi.styles(layerId!),
    enabled: Boolean(layerId),
  });
}

/**
 * The fields a style may reference — Data Management's catalogue for the layer.
 *
 * Fetched from the style API rather than from the Data Management API only to save the caller a
 * second permission; it is the same catalogue, served by the same service. It is invalidated
 * alongside Data Management's own queries so a field retired there disappears from the picker
 * without a reload.
 */
export function useStyleFields(layerId: string | undefined) {
  return useQuery({
    queryKey: layerKeys.styleFields(layerId ?? ''),
    queryFn: () => layersApi.styleFields(layerId!),
    enabled: Boolean(layerId),
  });
}

/**
 * Style templates for a layer.
 *
 * Keyed by layer because the server filters them to the layer's geometry. Long-lived: the set changes
 * only when the platform does.
 */
export function useStyleTemplates(layerId: string | undefined) {
  return useQuery({
    queryKey: layerKeys.templates(layerId ?? ''),
    queryFn: () => layersApi.templates(layerId),
    enabled: Boolean(layerId),
    staleTime: Infinity,
  });
}

export function useStyleVocabulary() {
  return useQuery({
    queryKey: layerKeys.vocabulary(),
    queryFn: () => layersApi.vocabulary(),
    staleTime: Infinity,
  });
}

/**
 * Invalidates everything a registry or style change can affect.
 *
 * Deliberately broad, for the reason the Data Management module gives about its own catalogue.
 * Creating a layer changes the registry, the map's layer list, the map's composed style and the
 * import hub's target list; recolouring one changes the map and the legend. Being precise here would
 * mean a map still drawing last week's colours because the invalidation missed a key — the exact
 * "my change isn't showing" failure this architecture is meant to remove.
 */
function useLayerInvalidation() {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: layerKeys.all });
    // The map's own layer catalogue and the Data Management layer list read the same rows.
    qc.invalidateQueries({ queryKey: ['gis', 'layers'] });
    qc.invalidateQueries({ queryKey: ['data-management', 'layers'] });
  };
}

export function useCreateLayer() {
  const invalidate = useLayerInvalidation();
  return useMutation({
    mutationFn: (payload: CreateLayerRequest) => layersApi.create(payload),
    onSuccess: invalidate,
  });
}

export function useUpdateLayer() {
  const invalidate = useLayerInvalidation();
  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: UpdateLayerRequest }) =>
      layersApi.update(id, payload),
    onSuccess: invalidate,
  });
}

export function useChangeLayerStatus() {
  const invalidate = useLayerInvalidation();
  return useMutation({
    mutationFn: ({ id, status, reason }: { id: string; status: LayerStatus; reason?: string | undefined }) => {
      if (status === 'ACTIVE') return layersApi.enable(id, reason);
      if (status === 'INACTIVE') return layersApi.disable(id, reason);
      return layersApi.archive(id, reason);
    },
    onSuccess: invalidate,
  });
}

export function useSaveStyle() {
  const invalidate = useLayerInvalidation();
  return useMutation({
    mutationFn: ({ id, payload }: { id?: string | undefined; payload: SaveStyleRequest }) =>
      id ? layersApi.updateStyle(id, payload) : layersApi.createStyle(payload),
    onSuccess: invalidate,
  });
}

export function useStyleLifecycle() {
  const invalidate = useLayerInvalidation();
  return useMutation({
    mutationFn: ({
      id,
      action,
      reason,
    }: {
      id: string;
      action: 'activate' | 'deactivate' | 'make-default';
      reason?: string | undefined;
    }) => {
      if (action === 'activate') return layersApi.activateStyle(id);
      if (action === 'deactivate') return layersApi.deactivateStyle(id, reason);
      return layersApi.makeDefaultStyle(id);
    },
    onSuccess: invalidate,
  });
}

/** The tenant's uploaded symbol library. */
export function useMapSymbols() {
  return useQuery({
    queryKey: layerKeys.symbols(),
    queryFn: () => layersApi.symbols(),
    staleTime: 60_000,
  });
}

export function useUploadSymbol() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      file,
      name,
      description,
      tintable,
    }: {
      file: File;
      name: string;
      description: string;
      tintable: boolean;
    }) => layersApi.uploadSymbol(file, name, description, tintable),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: layerKeys.symbols() });
      // The map registers icons from the composed style, so a new symbol has to reach it too.
      qc.invalidateQueries({ queryKey: layerKeys.mapStyle() });
    },
  });
}

export function useDeleteSymbol() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (symbolId: string) => layersApi.deleteSymbol(symbolId),
    onSuccess: () => qc.invalidateQueries({ queryKey: layerKeys.symbols() }),
  });
}

/**
 * The map's composed rendering instructions.
 *
 * One request for every layer, because the alternative is one per layer on every page load and a map
 * that paints its layers in whatever order the responses happened to arrive — which puts a service
 * connection above the DMA it sits in about half the time.
 */
export function useMapStyle() {
  return useQuery({
    queryKey: layerKeys.mapStyle(),
    queryFn: () => layersApi.mapStyle(),
    staleTime: 60_000,
  });
}
