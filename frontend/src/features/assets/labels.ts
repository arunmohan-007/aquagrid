import type { AssetType } from './types';

/**
 * Display names for asset types.
 *
 * Three screens used to render the raw enum through `replaceAll('_', ' ')`, which was fine while
 * every name happened to read well in English. It stopped being fine when TANK had to display as
 * "Over Head Tank": the enum value cannot change without rewriting every stored row, so the label
 * has to live apart from it. One map, so the register, the detail page and the create dialog can
 * never disagree about what a thing is called.
 */
export const ASSET_TYPE_LABELS: Record<AssetType, string> = {
  METER: 'Meter',
  VALVE: 'Valve',
  PIPELINE: 'Pipeline',
  HYDRANT: 'Hydrant',
  TANK: 'Over Head Tank',
  RESERVOIR: 'Reservoir',
  PUMP_STATION: 'Pump Station',
  OPEN_WELL: 'Open Well',
  BORE_WELL: 'Bore Well',
  DMA: 'DMA',
  PANCHAYAT: 'Panchayat Boundary',
  SERVICE_CONNECTION: 'Service Connection',
  SENSOR: 'Sensor',
};

/** Plural form for filter dropdowns and layer headings. */
export const ASSET_TYPE_LABELS_PLURAL: Record<AssetType, string> = {
  METER: 'Meters',
  VALVE: 'Valves',
  PIPELINE: 'Pipelines',
  HYDRANT: 'Hydrants',
  TANK: 'Over Head Tanks',
  RESERVOIR: 'Reservoirs',
  PUMP_STATION: 'Pump Stations',
  OPEN_WELL: 'Open Wells',
  BORE_WELL: 'Bore Wells',
  DMA: 'DMAs',
  PANCHAYAT: 'Panchayat Boundaries',
  SERVICE_CONNECTION: 'Service Connections',
  SENSOR: 'Sensors',
};

/**
 * Label for an asset type, tolerating a value the frontend does not yet know about.
 *
 * The backend can ship a new AssetType before this bundle is redeployed; falling back to the
 * de-underscored enum keeps that a cosmetic difference rather than a blank cell.
 */
export function assetTypeLabel(type: string): string {
  return ASSET_TYPE_LABELS[type as AssetType] ?? type.replaceAll('_', ' ');
}
