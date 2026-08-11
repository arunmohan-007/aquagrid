import type { ReceptionStatus } from './types';

/**
 * Display names, kept apart from the wire values.
 *
 * The error codes are the interesting half. They arrive as stable platform constants
 * (`RECEIVER_UNKNOWN_DEVICE`) which are exactly right for a client to branch on and exactly wrong
 * to show an operator — so each one is translated into what it means for the fleet, not what it
 * means to the receiver. "The device is not registered" is a fact; "no registered device matches
 * this packet — commission it, or check the identifier" is an instruction.
 */
export const RECEPTION_STATUS_LABELS: Record<ReceptionStatus, string> = {
  ACCEPTED: 'Accepted',
  DUPLICATE: 'Duplicate',
  REJECTED: 'Rejected',
};

/**
 * What each rejection means to the person reading it.
 *
 * Unlisted codes fall back to the server's own detail string, so a code added server-side is never
 * rendered as a blank — it just reads more technically until it is added here.
 */
export const ERROR_CODE_LABELS: Record<string, string> = {
  RECEIVER_UNKNOWN_DEVICE: 'Device not registered',
  RECEIVER_UNKNOWN_TENANT: 'Could not attribute to an organisation',
  RECEIVER_AUTHENTICATION_FAILED: 'Authentication failed',
  RECEIVER_UNSUPPORTED_TRANSPORT: 'Transport not enabled',
  RECEIVER_MALFORMED_PAYLOAD: 'Payload could not be decoded',
  RECEIVER_DUPLICATE_PACKET: 'Already ingested',
  RECEIVER_REPLAY_DETECTED: 'Replayed packet',
  RECEIVER_CHECKSUM_FAILED: 'Checksum mismatch',
  RECEIVER_VALIDATION_FAILED: 'Failed validation',
  RECEIVER_PAYLOAD_TOO_LARGE: 'Payload too large',
  RECEIVER_IP_NOT_ALLOWED: 'Source address not permitted',
  RECEIVER_DEVICE_NOT_OPERATIONAL: 'Device not accepting telemetry',
  RECEIVER_TIMEOUT: 'Receiver timed out',
  RECEIVER_CONNECTION_FAILED: 'Transport connection failed',
  RATE_LIMIT_EXCEEDED: 'Rate limit exceeded',
  INTERNAL_ERROR: 'Internal error — packet queued for replay',
};

/** Canonical metric names, as an operator reads them, with their units. */
export const METRIC_LABELS: Record<string, { label: string; unit: string }> = {
  flow_rate: { label: 'Flow', unit: 'L/min' },
  volume: { label: 'Volume', unit: 'L' },
  pressure: { label: 'Pressure', unit: 'bar' },
  battery_voltage: { label: 'Battery', unit: 'V' },
  temperature: { label: 'Temperature', unit: '°C' },
  tamper: { label: 'Tamper', unit: '' },
  reverse_flow: { label: 'Reverse flow', unit: '' },
  leak: { label: 'Leak', unit: '' },
};

/** Metrics that are 0/1 conditions rather than measurements — rendered as a state, not a number. */
export const FLAG_METRICS = new Set(['tamper', 'reverse_flow', 'leak']);

export function metricLabel(metric: string): string {
  return METRIC_LABELS[metric]?.label ?? metric;
}

export function metricUnit(metric: string): string {
  return METRIC_LABELS[metric]?.unit ?? '';
}

export function errorLabel(code: string | undefined, fallback: string | undefined): string {
  if (!code) return fallback ?? '';
  return ERROR_CODE_LABELS[code] ?? fallback ?? code;
}

/**
 * A duration in the shortest form that is still honest.
 *
 * Deliberately not "a few seconds ago" style rounding: this number is read to decide whether a
 * meter has stopped reporting, and on a 15-minute schedule the difference between 14 minutes and
 * 40 minutes is the whole question.
 */
export function formatDuration(seconds: number | undefined): string {
  if (seconds === undefined || seconds === null) return '—';
  if (seconds < 0) return 'in the future';
  if (seconds < 60) return `${Math.floor(seconds)}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  if (seconds < 86400) {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  }
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  return hours > 0 ? `${days}d ${hours}h` : `${days}d`;
}

/** Absolute timestamp, to the second. Telemetry questions are answered in seconds, not minutes. */
export function formatTimestamp(iso: string | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}
