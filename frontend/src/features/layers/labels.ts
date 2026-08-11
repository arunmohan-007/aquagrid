import type { GeometryFamily, LayerStatus } from './types';

/**
 * Wording for the GIS Management screens.
 *
 * Only what the server does not already name. Geometry-type labels, style-type labels, operator
 * symbols and the icon list are absent on purpose: they come from `/gis-layers/geometry-types` and
 * `/layer-styles/vocabulary`, served from the same definitions the server validates against, so the
 * client cannot offer a value the server would reject — the rule the device registration form
 * follows for transports.
 */

export const STATUS_LABELS: Record<LayerStatus, string> = {
  ACTIVE: 'Active',
  INACTIVE: 'Disabled',
  ARCHIVED: 'Archived',
};

/**
 * What each status actually does, for the tooltip on the chip.
 *
 * The distinction between disabled and archived is the one people get wrong, and getting it wrong
 * is expensive in one direction only: nobody minds re-enabling a layer, and everybody minds
 * scrolling five years of retired layers to find the one that is merely paused.
 */
export const STATUS_HINTS: Record<LayerStatus, string> = {
  ACTIVE: 'Drawn on the map, offered for import, included in exports.',
  INACTIVE: 'Withdrawn for now. Every feature is kept; nothing is deleted.',
  ARCHIVED: 'Retired. Hidden from the registry unless archived layers are shown. Every feature is kept.',
};

export const FAMILY_LABELS: Record<GeometryFamily, string> = {
  POINT: 'Point',
  LINE: 'Line',
  POLYGON: 'Polygon',
  ANY: 'Mixed',
};

/**
 * The registry grid's columns, in order.
 *
 * Feature count and extent are here because they are the two questions an administrator opens this
 * screen to answer — "did that import land" and "where is this layer" — and both are computed in
 * PostGIS rather than by loading features.
 */
export const LAYER_COLUMNS = [
  { id: 'title', label: 'Layer', width: 240 },
  { id: 'code', label: 'Layer Name', width: 150 },
  { id: 'category', label: 'Category', width: 130 },
  { id: 'geometryType', label: 'Geometry', width: 140 },
  { id: 'crs', label: 'CRS', width: 110 },
  { id: 'featureCount', label: 'Features', width: 100, align: 'right' as const },
  { id: 'flags', label: 'Capabilities', width: 210 },
  { id: 'status', label: 'Status', width: 110 },
  { id: 'actions', label: '', width: 230 },
];

/** `[minLon, minLat, maxLon, maxLat]` as an operator reads it, or a dash when the layer is empty. */
export function formatExtent(extent: [number, number, number, number] | null): string {
  if (!extent) return '—';
  const [minLon, minLat, maxLon, maxLat] = extent;
  return `${minLon.toFixed(4)}, ${minLat.toFixed(4)} → ${maxLon.toFixed(4)}, ${maxLat.toFixed(4)}`;
}

/**
 * Thousands-separated, or an em dash when counts were not requested.
 *
 * Null and zero are deliberately different: null means nobody counted, zero means the layer is
 * empty, and to someone checking whether an import worked those are opposite answers.
 */
export function formatCount(count: number | null): string {
  return count === null || count === undefined ? '—' : count.toLocaleString();
}

export function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();
}

/** Why a control is disabled, so a greyed-out button is never unexplained. */
export function systemLayerReason(system: boolean): string | null {
  return system
    ? 'This is a system layer: the platform’s own code reads it by asset type — the dashboard sums '
      + 'its length, the network trace walks it — so its layer name and asset type are fixed and it '
      + 'cannot be archived. Everything else about it is yours to change.'
    : null;
}
