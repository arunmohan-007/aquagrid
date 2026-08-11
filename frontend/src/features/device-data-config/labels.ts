import type { ParameterChangeType, QualityStatus, DiscoveryStatus } from './types';

/**
 * Wording for the Device Data Configuration screens.
 *
 * Only the things the server does not already name are here. Data-type labels, category labels and
 * unit labels are not: they come from `/device-data-config/{data-types,categories,units}` along with
 * the configuration each one accepts, so the client cannot fall behind the server's list.
 */

export const CHANGE_TYPE_LABELS: Record<ParameterChangeType, string> = {
  CREATED: 'Created',
  UPDATED: 'Updated',
  DEACTIVATED: 'Retired',
  REACTIVATED: 'Returned to service',
};

export const DISCOVERY_STATUS_LABELS: Record<DiscoveryStatus, string> = {
  PENDING: 'Awaiting a decision',
  CONFIGURED: 'Configured',
  IGNORED: 'Ignored',
};

/**
 * What each quality verdict means, in the words an operator needs rather than the enum's.
 *
 * `UNKNOWN` is phrased carefully. It is not a failure — it is the honest state before anyone has
 * described the parameter — and wording it as an error would push people to configure parameters
 * they have no use for just to clear a warning.
 */
export const QUALITY_LABELS: Record<QualityStatus, { label: string; hint: string }> = {
  VALID: { label: 'Valid', hint: 'Inside every rule configured for it' },
  INVALID: {
    label: 'Invalid',
    hint: 'Stored, but could not be read as its configured type — usually the configuration, not the device',
  },
  OUT_OF_RANGE: {
    label: 'Out of range',
    hint: 'Stored, and outside the configured minimum or maximum',
  },
  MISSING: {
    label: 'Missing',
    hint: 'Configured as mandatory and absent from the packet. The packet was still accepted',
  },
  UNKNOWN: {
    label: 'Not configured',
    hint: 'Received and stored. Nothing has been said about what it means yet',
  },
};

/** The configuration grid's columns, in order. */
export interface ColumnDef {
  id: string;
  label: string;
  width: number;
  align?: 'left' | 'right' | 'center';
}

export const PARAMETER_COLUMNS: ColumnDef[] = [
  { id: 'parameterName', label: 'Parameter', width: 190 },
  { id: 'displayName', label: 'Display Name', width: 180 },
  { id: 'dataType', label: 'Data Type', width: 120 },
  { id: 'unit', label: 'Unit', width: 80 },
  { id: 'category', label: 'Category', width: 140 },
  { id: 'range', label: 'Range', width: 130, align: 'right' },
  { id: 'mandatory', label: 'Mandatory', width: 100, align: 'center' },
  { id: 'dashboardVisible', label: 'Dashboard', width: 100, align: 'center' },
  { id: 'useForAlarm', label: 'Alarm', width: 80, align: 'center' },
  { id: 'useForReports', label: 'Reports', width: 88, align: 'center' },
  { id: 'active', label: 'Status', width: 110 },
  { id: 'actions', label: '', width: 130, align: 'right' },
];

export const DISCOVERED_COLUMNS: ColumnDef[] = [
  { id: 'parameterName', label: 'Parameter', width: 200 },
  { id: 'deviceCode', label: 'Device', width: 150 },
  { id: 'deviceType', label: 'Device Type', width: 150 },
  { id: 'sampleValue', label: 'Sample Value', width: 150 },
  { id: 'detectedDataType', label: 'Detected Type', width: 130 },
  { id: 'firstSeenAt', label: 'First Seen', width: 160 },
  { id: 'lastSeenAt', label: 'Last Seen', width: 160 },
  { id: 'occurrences', label: 'Count', width: 90, align: 'right' },
  { id: 'status', label: 'Status', width: 120 },
  { id: 'actions', label: '', width: 190, align: 'right' },
];

/** Human device-type labels, as a fallback while the server list loads. */
export function humanise(constant: string | null | undefined): string {
  if (!constant) return '—';
  return constant
    .split('_')
    .map((part) => part.charAt(0) + part.slice(1).toLowerCase())
    .join(' ');
}

/** A compact absolute timestamp. Relative times hide whether a device has been quiet for a week. */
export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}
