import { useQuery } from '@tanstack/react-query';
import { gisApi } from '../api/gisApi';

export const queryKeys = {
  gis: {
    layers: ['gis', 'layers'] as const,
    extent: (code: string) => ['gis', 'layers', code, 'extent'] as const,
    pipeline: (assetId: string) => ['gis', 'pipeline', assetId] as const,
  },
} as const;

export function useLayers() {
  return useQuery({ queryKey: queryKeys.gis.layers, queryFn: () => gisApi.layers() });
}

/**
 * A layer's bounding box, used to frame the map's opening camera.
 *
 * Cached for the session: an extent moves only when assets are added or edited, and re-fetching it
 * on every remount would re-run the opening zoom in the middle of an operator's panning.
 */
/**
 * The pipeline engineering record for an asset, when one exists.
 *
 * Not retried: the common answer on an imported network is a 404 (no `gis.pipelines` row), and
 * retrying that three times per click just delays the card's "not recorded" state.
 */
export function usePipelineDetail(assetId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.gis.pipeline(assetId ?? ''),
    queryFn: () => gisApi.pipeline(assetId!),
    enabled: Boolean(assetId),
    retry: false,
    staleTime: 60_000,
  });
}

export function useLayerExtent(code: string | undefined) {
  return useQuery({
    queryKey: queryKeys.gis.extent(code ?? ''),
    queryFn: () => gisApi.layerExtent(code!),
    enabled: Boolean(code),
    staleTime: Infinity,
  });
}
