import { apiGet } from '@/lib/api/httpClient';

/**
 * A layer as the map's catalogue describes it.
 *
 * Extended by Layer Management: `queryable`, `searchable` and the zoom pair used to be assumptions
 * baked into the client, and are now registry settings. `visible` keeps its name — it is
 * visible-by-default, and renaming a field the client already reads to say the same thing in
 * different words is not worth the churn.
 */
export interface LayerSummary {
  id: string;
  code: string;
  title: string;
  description: string | null;
  category: string | null;
  assetType: string;
  geometryType: string;
  visible: boolean;
  /** Whether clicking a feature of this layer opens the inspection card. */
  queryable: boolean;
  /** Whether the map's search box looks in this layer. */
  searchable: boolean;
  vectorTileEnabled: boolean;
  minZoom: number;
  maxZoom: number;
  sortOrder: number;
}

/**
 * The engineering record behind a pipeline asset.
 *
 * Every field is optional in practice: an imported network has asset rows and geometry but no
 * `gis.pipelines` row at all (the pipeline editor writes those), so the endpoint 404s for most
 * pipes on a freshly imported dataset. The inspection card treats that as "not recorded yet"
 * rather than an error.
 */
export interface PipelineDetail {
  assetId: string;
  fromNodeId?: string;
  toNodeId?: string;
  diameterMm?: number;
  material?: string;
  lengthM?: number;
  flowDirection?: string;
  roughness?: number;
  pressureClass?: number;
}

/** A layer's bounding box in EPSG:4326, as returned by `/gis/layers/{code}/extent`. */
export interface LayerExtent {
  minLon: number;
  minLat: number;
  maxLon: number;
  maxLat: number;
}

/**
 * GIS endpoints.
 *
 * Only the layer catalogue and layer extents are fetched through here. Tiles are requested by
 * MapLibre itself: it owns the tile lifecycle (which z/x/y, when to evict, when to retry) and the
 * bearer token is attached per request by the map's `transformRequest` hook, so the credential
 * never appears in a URL that a proxy or access log might retain.
 */
export const gisApi = {
  layers: () => apiGet<LayerSummary[]>('/gis/layers'),

  /*
   * Null when the layer holds no assets. The endpoint answers 204 in that case, which axios
   * surfaces as an empty-string body rather than an object — hence the shape check instead of a
   * plain cast, which would hand the map an "extent" of `''` and a NaN camera.
   */
  layerExtent: async (code: string): Promise<LayerExtent | null> => {
    const data = await apiGet<LayerExtent | ''>(`/gis/layers/${encodeURIComponent(code)}/extent`);
    return data && typeof data === 'object' ? data : null;
  },

  pipeline: (assetId: string) => apiGet<PipelineDetail>(`/assets/${assetId}/pipeline`),
};
