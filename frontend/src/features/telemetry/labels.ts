import type { MetricKind, MetricReading } from './types';

/**
 * Formatting only. There is deliberately no metric table here — labels, units, kinds and categories
 * all come from the server's catalogue, so this file cannot drift out of step with it.
 */

/**
 * Renders a value the way its kind should be read.
 *
 * A flag is a state, not a number: showing "1" where an operator expects "Tamper" makes them
 * translate it, and 0 renders as "Clear" rather than blank because "no tamper reported" and "the
 * device did not report" are different facts.
 *
 * A cumulative register is shown whole and unrounded — it is a meter reading, and rounding the
 * number a bill is computed from is not a display choice to make lightly.
 */
export function formatValue(reading: MetricReading): string {
  if (reading.value === undefined || reading.value === null) return '—';
  if (reading.kind === 'FLAG') return reading.value === 0 ? 'Clear' : 'Set';
  if (reading.kind === 'COUNTER') {
    return reading.value.toLocaleString(undefined, { maximumFractionDigits: 1 });
  }
  return reading.value.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

/** The unit, suppressed for flags — "Set flag" reads as nonsense. */
export function formatUnit(reading: MetricReading): string {
  if (reading.kind === 'FLAG' || !reading.unit) return '';
  return reading.unit;
}

/** A raised flag is the one thing on this screen that should catch the eye. */
export function isRaised(reading: MetricReading): boolean {
  return reading.kind === 'FLAG' && Boolean(reading.value);
}

/** Whether plotting this metric tells the reader anything. */
export function isPlottable(kind: MetricKind): boolean {
  return kind !== 'FLAG';
}

/**
 * A reading's age, in the shortest honest form.
 *
 * Not rounded to "just now": on a six-hour duty cycle the difference between 20 minutes and 5 hours
 * is the difference between a healthy meter and one worth investigating.
 */
export function formatAge(seconds: number | undefined): string {
  if (seconds === undefined || seconds === null) return '—';
  if (seconds < 0) return 'in the future';
  if (seconds < 60) return `${Math.floor(seconds)}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return minutes > 0 ? `${hours}h ${minutes}m ago` : `${hours}h ago`;
  }
  const days = Math.floor(seconds / 86400);
  return `${days}d ago`;
}

/** Windows offered for a series. */
export const SERIES_WINDOWS = [
  { hours: 6, label: 'Last 6 hours' },
  { hours: 24, label: 'Last 24 hours' },
  { hours: 24 * 7, label: 'Last 7 days' },
  { hours: 24 * 30, label: 'Last 30 days' },
];
