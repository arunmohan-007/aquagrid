/**
 * Layer Management and Layer Style Management — the shapes the API speaks.
 *
 * Mirrors `LayerDtos` and `StyleDtos` on the server. Nothing here decides anything about how a
 * layer looks: the MapLibre paint and layout come from the server already composed, and this file
 * only types the envelope they arrive in.
 */

export type LayerStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';

export type GeometryTypeCode =
  | 'POINT'
  | 'MULTIPOINT'
  | 'LINESTRING'
  | 'MULTILINESTRING'
  | 'POLYGON'
  | 'MULTIPOLYGON'
  | 'GEOMETRY'
  | 'GEOMETRYCOLLECTION';

export type GeometryFamily = 'POINT' | 'LINE' | 'POLYGON' | 'ANY';

export type StyleTypeCode = 'SIMPLE' | 'CATEGORICAL' | 'GRADUATED' | 'RULE_BASED';

export type StyleOperatorCode =
  | 'EQ'
  | 'NEQ'
  | 'LT'
  | 'LTE'
  | 'GT'
  | 'GTE'
  | 'IN'
  | 'BETWEEN'
  | 'IS_NULL'
  | 'IS_NOT_NULL';

export interface GisLayer {
  id: string;
  /** The layer name — the identifier in tile URLs and MapLibre source ids. Permanent. */
  code: string;
  title: string;
  description: string | null;
  category: string | null;
  assetType: string;
  geometryType: GeometryTypeCode;
  geometryFamily: GeometryFamily;
  /** e.g. `EPSG:4326`. */
  crs: string;
  crsAuthority: string;
  srid: number;
  featureTable: string;
  geometryColumn: string;
  status: LayerStatus;
  visibleByDefault: boolean;
  editable: boolean;
  queryable: boolean;
  searchable: boolean;
  importEnabled: boolean;
  exportEnabled: boolean;
  vectorTileEnabled: boolean;
  minZoom: number;
  maxZoom: number;
  sortOrder: number;
  /** Locked code and asset type; cannot be archived. */
  system: boolean;
  /**
   * Null when counts were not requested — deliberately distinct from `0`, which means the layer is
   * genuinely empty. The two answer very different questions for someone checking an import.
   */
  featureCount: number | null;
  /** `[minLon, minLat, maxLon, maxLat]` in EPSG:4326, or null when the layer holds nothing. */
  extent: [number, number, number, number] | null;
  createdBy: string | null;
  createdDate: string | null;
  modifiedBy: string | null;
  modifiedDate: string | null;
}

export interface GeometryTypeOption {
  value: GeometryTypeCode;
  label: string;
  family: GeometryFamily;
  /** True for POINT, LINESTRING and POLYGON — the set an ordinary administrator chooses between. */
  simple: boolean;
  multi: boolean;
}

export interface AssetTypeOption {
  value: string;
  label: string;
}

export interface CrsOption {
  srid: number;
  authority: string;
  title: string;
  /** `EPSG:4326` — authority and srid joined, as an operator writes it. */
  code: string;
}

export interface LayerStatistics {
  featureCount: number;
  geometryType: GeometryTypeCode;
  crs: string;
  extent: [number, number, number, number] | null;
}

export interface FieldValues {
  fieldName: string;
  values: string[];
  minimum: number | null;
  maximum: number | null;
}

export interface CreateLayerRequest {
  code?: string | undefined;
  title: string;
  description?: string | undefined;
  category?: string | undefined;
  assetType?: string | undefined;
  geometryType?: GeometryTypeCode | undefined;
  crsAuthority?: string | undefined;
  srid?: number | undefined;
  active?: boolean | undefined;
  visibleByDefault?: boolean | undefined;
  editable?: boolean | undefined;
  queryable?: boolean | undefined;
  searchable?: boolean | undefined;
  importEnabled?: boolean | undefined;
  exportEnabled?: boolean | undefined;
  vectorTileEnabled?: boolean | undefined;
  minZoom?: number | undefined;
  maxZoom?: number | undefined;
  sortOrder?: number | undefined;
  claimExistingFeatures?: boolean | undefined;
}

/** Every field optional; omitting one leaves it unchanged. Code and asset type are permanent. */
export type UpdateLayerRequest = Omit<
  CreateLayerRequest,
  'code' | 'assetType' | 'active' | 'claimExistingFeatures'
>;

// ---- Styles ----------------------------------------------------------------------------------

/**
 * AquaGrid's symbology vocabulary — not MapLibre's.
 *
 * The server translates. Storing raw paint would weld the database to one renderer and let the
 * editor persist expressions nothing validated.
 */
export interface Symbol {
  renderMode?: 'circle' | 'icon' | undefined;
  size?: number | undefined;
  fillColor?: string | undefined;
  strokeColor?: string | undefined;
  strokeWidth?: number | undefined;
  opacity?: number | undefined;
  glowColor?: string | undefined;
  icon?: string | undefined;
  iconSize?: number | undefined;
  lineColor?: string | undefined;
  lineWidth?: number | undefined;
  lineOpacity?: number | undefined;
  dashPattern?: number[] | undefined;
  lineCap?: 'butt' | 'round' | 'square' | undefined;
  lineJoin?: 'bevel' | 'round' | 'miter' | undefined;
  fillOpacity?: number | undefined;
  outlineColor?: string | undefined;
  outlineWidth?: number | undefined;
  outlineOpacity?: number | undefined;
}

export interface LabelConfig {
  enabled?: boolean | undefined;
  /** A field name from the Data Management catalogue. Validated server-side. */
  field?: string | undefined;
  textSize?: number | undefined;
  textColor?: string | undefined;
  haloColor?: string | undefined;
  haloWidth?: number | undefined;
  minZoom?: number | undefined;
  maxZoom?: number | undefined;
}

export interface StyleRule {
  id?: string | undefined;
  fieldName: string;
  operator: StyleOperatorCode;
  value1?: string | null | undefined;
  value2?: string | null | undefined;
  valueList?: string[] | null | undefined;
  label?: string | null | undefined;
  symbol: Symbol;
  sortOrder: number;
  active?: boolean | undefined;
}

export interface LayerStyle {
  id: string;
  layerId: string;
  name: string;
  description: string | null;
  styleType: StyleTypeCode;
  classifyField: string | null;
  active: boolean;
  defaultStyle: boolean;
  minZoom: number;
  maxZoom: number;
  symbol: Symbol;
  label: LabelConfig;
  rules: StyleRule[];
  createdBy: string | null;
  createdDate: string | null;
  modifiedBy: string | null;
  modifiedDate: string | null;
}

export interface SaveStyleRequest {
  layerId: string;
  name?: string | undefined;
  description?: string | undefined;
  styleType?: StyleTypeCode | undefined;
  classifyField?: string | null | undefined;
  active?: boolean | undefined;
  defaultStyle?: boolean | undefined;
  minZoom?: number | undefined;
  maxZoom?: number | undefined;
  symbol?: Symbol | undefined;
  label?: LabelConfig | undefined;
  rules?: Omit<StyleRule, 'id' | 'active'>[] | undefined;
}

/**
 * A starting point for a new style.
 *
 * Served rather than kept here, so a template can only produce a style the server would accept — the
 * template and the validation come from the same definitions.
 */
export interface StyleTemplate {
  id: string;
  name: string;
  description: string;
  families: GeometryFamily[];
  styleType: StyleTypeCode;
  /** Field to classify on, pre-selected only when the layer's catalogue has it. Never invented. */
  suggestedField: string | null;
  /**
   * Field a labelling template wants drawn. Templates always ship with labels *off* — "labels on,
   * no field" is the one combination the server refuses — so the client switches them on only once
   * it has resolved this against the layer's catalogue.
   */
  labelField: string | null;
  symbol: Symbol;
  label: LabelConfig;
  /** Classes to create once a field has been chosen. Empty for single-symbol templates. */
  ruleSeeds: { label: string; operator: StyleOperatorCode; value: string; symbol: Symbol }[];
}

/** One field a style may reference. Data Management's catalogue, unmodified. */
export interface StyleField {
  fieldName: string;
  displayName: string;
  description: string | null;
  dataType: string;
  numeric: boolean;
}

/** One icon from the free built-in library (Mapbox Maki, CC0; Google Material, Apache-2.0). */
export interface LibrarySymbol {
  /** What a style's `icon` property stores: `lib-<id>`. */
  iconName: string;
  name: string;
  set: 'MAKI' | 'MATERIAL';
  contentUrl: string;
}

/** One symbol this tenant uploaded. */
export interface MapSymbol {
  id: string;
  name: string;
  description: string | null;
  format: 'SVG' | 'PNG';
  sizeBytes: number;
  /** True when the map paints it in the style's colour rather than its own. */
  tintable: boolean;
  widthPx: number | null;
  heightPx: number | null;
  /** What a style's `icon` property stores: `sym-<uuid>`. */
  iconName: string;
  contentUrl: string;
  createdDate: string | null;
  createdBy: string | null;
}

export interface StyleVocabulary {
  styleTypes: { value: StyleTypeCode; label: string; hint: string }[];
  operators: {
    value: StyleOperatorCode;
    symbol: string;
    arity: 'NONE' | 'ONE' | 'TWO' | 'LIST';
    ordered: boolean;
  }[];
  symbolKeys: Record<GeometryFamily, string[]>;
  labelKeys: string[];
  enumeratedKeys: Record<string, string[]>;
  /** Built-in shapes the client draws itself — no download, always available. */
  icons: string[];
  /** The free vendored library, served by the API so the picker and the server agree. */
  libraryIcons: LibrarySymbol[];
}

/**
 * A layer's complete rendering instruction, composed by the server.
 *
 * `layers` are MapLibre layer specifications passed to `map.addLayer` verbatim. The client makes no
 * decision about appearance — which is what lets a layer created at runtime draw correctly with no
 * release.
 */
export interface ComposedMapLayer {
  layerId: string;
  code: string;
  title: string;
  category: string | null;
  sourceId: string;
  source: Record<string, unknown>;
  sourceLayer: string;
  visibleByDefault: boolean;
  queryable: boolean;
  minZoom: number;
  maxZoom: number;
  styleId: string | null;
  styleName: string | null;
  layers: Record<string, unknown>[];
  legend: { label: string; colour: string; shape: string }[];
  /** Catalogue fields the expressions read, and which the tile therefore carries. */
  styledFields: string[];
  /**
   * Library and uploaded icon ids this layer needs registered before it draws. MapLibre draws
   * nothing for a missing image and reports no error, so these are fetched and registered first.
   */
  requiredIcons: string[];
}
