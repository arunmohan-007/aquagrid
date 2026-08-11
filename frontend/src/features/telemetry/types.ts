/**
 * Types mirroring the Module 7 (Device telemetry) API contract.
 *
 * There is no metric table here. Labels, units, kinds and categories all arrive on the readings,
 * from the server's `MetricCatalog` — the same declaration the ingest path stamps units from. A
 * client-side copy is exactly the drift the platform has already removed twice.
 */

/**
 * How a value should be read.
 *
 * Decides whether plotting it means anything: a `COUNTER` is a cumulative register whose ramp says
 * nothing (its *difference* is the consumption), and a `FLAG` is a condition, not a quantity.
 */
export type MetricKind = 'MEASUREMENT' | 'COUNTER' | 'FLAG';

export type MetricCategory =
  | 'CONSUMPTION'
  | 'PRESSURE'
  | 'DEVICE_HEALTH'
  | 'CONDITION'
  | 'ENVIRONMENT'
  | 'OTHER';

export interface MetricReading {
  metric: string;
  label: string;
  unit?: string;
  kind: MetricKind;
  category: MetricCategory;
  value?: number;
  observedAt: string;
  /** Every value carries its age — a number without one cannot be judged as current or stale. */
  ageSeconds: number;
}

export interface MetricGroup {
  category: MetricCategory;
  label: string;
  readings: MetricReading[];
}

export interface DeviceTelemetry {
  deviceId: string;
  deviceCode?: string;
  name?: string;
  deviceType?: string;
  transport?: string;
  deviceSource?: 'LIVE' | 'SIMULATOR';
  status?: string;
  networkAddress?: string;
  serialNumber?: string;
  manufacturer?: string;
  model?: string;
  firmwareVersion?: string;
  assetNumber?: string;
  latitude?: number;
  longitude?: number;
  installationDate?: string;
  lastSeenAt?: string;
  silentForSeconds?: number;
  batteryV?: number;
  rssi?: number;
  snr?: number;
  groups: MetricGroup[];
  /** Every metric reported in the window — the axis the series selector offers. */
  reportingMetrics: string[];
}

export interface SeriesPoint {
  observedAt: string;
  value?: number;
}

export interface MetricSeries {
  metric: string;
  label: string;
  unit?: string;
  kind: MetricKind;
  from: string;
  to: string;
  /** True when the window held more points than the cap, so the chart is a tail of it. */
  truncated: boolean;
  points: SeriesPoint[];
}
