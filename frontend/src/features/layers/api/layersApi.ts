import { apiDelete, apiGet, apiPost, apiPut } from '@/lib/api/httpClient';
import type {
  AssetTypeOption,
  ComposedMapLayer,
  CreateLayerRequest,
  CrsOption,
  FieldValues,
  GeometryTypeOption,
  GisLayer,
  LayerStatistics,
  LayerStatus,
  LayerStyle,
  SaveStyleRequest,
  MapSymbol,
  StyleField,
  StyleTemplate,
  StyleVocabulary,
  UpdateLayerRequest,
} from '../types';

/**
 * Layer Management and Layer Style Management endpoints.
 *
 * The registry lives under `/gis-layers` rather than under `/gis`, and that separation is the
 * server's: `/gis` is the map's read API, cached hard and gated on `gis:map:view`, while this is
 * administrative configuration gated on `gis:layer:manage`.
 *
 * Note what is *not* here. There is no attribute API — fields come from `/data-management`, and the
 * style editor's field picker calls `/layer-styles/fields`, which returns that same catalogue. Two
 * lists of a layer's fields is exactly the drift this module was built to avoid.
 */
export const layersApi = {
  // ---- Registry ------------------------------------------------------------------------------

  list: (params?: {
    status?: LayerStatus | undefined;
    category?: string | undefined;
    geometryType?: string | undefined;
    search?: string | undefined;
    withCounts?: boolean | undefined;
  }) => apiGet<GisLayer[]>('/gis-layers', { params }),

  get: (layerId: string) => apiGet<GisLayer>(`/gis-layers/${layerId}`),

  create: (body: CreateLayerRequest) => apiPost<GisLayer>('/gis-layers', body),

  update: (layerId: string, body: UpdateLayerRequest) =>
    apiPut<GisLayer>(`/gis-layers/${layerId}`, body),

  /*
   * Three verbs rather than one `status` field, because they are three decisions with different
   * consequences and the server audits them separately. Archive is refused on a system layer; the
   * message explains why and points at disable.
   */
  enable: (layerId: string, reason?: string) =>
    apiPost<GisLayer>(`/gis-layers/${layerId}/enable`, { reason }),
  disable: (layerId: string, reason?: string) =>
    apiPost<GisLayer>(`/gis-layers/${layerId}/disable`, { reason }),
  archive: (layerId: string, reason?: string) =>
    apiPost<GisLayer>(`/gis-layers/${layerId}/archive`, { reason }),

  statistics: (layerId: string) => apiGet<LayerStatistics>(`/gis-layers/${layerId}/statistics`),

  /** The values a field actually holds, so the style editor offers real categories and real bounds. */
  fieldValues: (layerId: string, fieldName: string) =>
    apiGet<FieldValues>(`/gis-layers/${layerId}/field-values`, { params: { fieldName } }),

  // ---- Reference data ------------------------------------------------------------------------

  geometryTypes: () => apiGet<GeometryTypeOption[]>('/gis-layers/geometry-types'),
  assetTypes: () => apiGet<AssetTypeOption[]>('/gis-layers/asset-types'),
  categories: () => apiGet<string[]>('/gis-layers/categories'),
  /** Read from PostGIS's own `spatial_ref_sys`, so a local grid a utility added appears here too. */
  crs: (search?: string) => apiGet<CrsOption[]>('/gis-layers/crs', { params: { search } }),

  // ---- Styles --------------------------------------------------------------------------------

  styles: (layerId: string) => apiGet<LayerStyle[]>('/layer-styles', { params: { layerId } }),
  style: (styleId: string) => apiGet<LayerStyle>(`/layer-styles/${styleId}`),
  createStyle: (body: SaveStyleRequest) => apiPost<LayerStyle>('/layer-styles', body),
  updateStyle: (styleId: string, body: SaveStyleRequest) =>
    apiPut<LayerStyle>(`/layer-styles/${styleId}`, body),
  activateStyle: (styleId: string) => apiPost<LayerStyle>(`/layer-styles/${styleId}/activate`, {}),
  deactivateStyle: (styleId: string, reason?: string) =>
    apiPost<LayerStyle>(`/layer-styles/${styleId}/deactivate`, { reason }),
  makeDefaultStyle: (styleId: string) =>
    apiPost<LayerStyle>(`/layer-styles/${styleId}/make-default`, {}),

  /**
   * Compose a style without saving it.
   *
   * The same code path as the save with the write removed, so the preview cannot show something the
   * save would reject — and the same composer the map uses, so the preview and the map cannot
   * disagree about what a rule means.
   */
  previewStyle: (body: SaveStyleRequest) =>
    apiPost<ComposedMapLayer>('/layer-styles/preview', body),

  /** Data Management's catalogue for the layer. This module keeps no field list of its own. */
  styleFields: (layerId: string) =>
    apiGet<StyleField[]>('/layer-styles/fields', { params: { layerId } }),

  vocabulary: () => apiGet<StyleVocabulary>('/layer-styles/vocabulary'),

  /** Starting points, filtered to the layer's geometry. A dashed boundary is no use on a point layer. */
  templates: (layerId?: string) =>
    apiGet<StyleTemplate[]>('/layer-styles/templates', { params: { layerId } }),

  /** Every layer's composed MapLibre specification, in one request. Consumed by the map. */
  mapStyle: () => apiGet<ComposedMapLayer[]>('/gis/map-style'),

  // ---- Uploaded symbol library ----------------------------------------------------------------

  symbols: () => apiGet<MapSymbol[]>('/map-symbols'),

  /**
   * Uploads a symbol.
   *
   * Multipart, so no explicit Content-Type: the browser has to set it itself in order to add the
   * boundary parameter, and naming it here produces a request the server cannot parse.
   */
  uploadSymbol: (file: File, name: string, description: string, tintable: boolean) => {
    const body = new FormData();
    body.append('file', file);
    if (name.trim()) body.append('name', name.trim());
    if (description.trim()) body.append('description', description.trim());
    body.append('tintable', String(tintable));
    return apiPost<MapSymbol>('/map-symbols', body);
  },

  deleteSymbol: (symbolId: string) => apiDelete<void>(`/map-symbols/${symbolId}`),
};
