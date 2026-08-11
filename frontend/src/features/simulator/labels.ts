import type { SimulatedFault } from './types';

/**
 * Fault names as an operator reads them, with what each one is for.
 *
 * The description is not decoration. This screen is used to validate a rule someone else wrote, so
 * the useful label is not "leak" but which analysis is supposed to notice a leak — otherwise an
 * engineer injects a fault, sees nothing happen, and has no way to tell whether the platform failed
 * or they picked the wrong condition.
 */
export const FAULT_LABELS: Record<SimulatedFault, { label: string; description: string }> = {
  LEAK: {
    label: 'Leak',
    description:
      'A slow persistent addition to baseline flow. The signature minimum-night-flow anomaly, and invisible in any single reading.',
  },
  BURST: {
    label: 'Burst',
    description: 'A large flow for a bounded window — a main failure, loud and short.',
  },
  COMMS_LOSS: {
    label: 'Comms loss',
    description:
      'The meter stops transmitting. Not the same as reporting zero: silence and no flow are different faults.',
  },
  TAMPER: {
    label: 'Tamper',
    description: 'Magnet or enclosure removal. Stays set until the meter is cleared.',
  },
  REVERSE_FLOW: {
    label: 'Reverse flow',
    description:
      'Flow measured in the wrong direction — a contamination risk, and a common sign of a meter fitted backwards.',
  },
  BATTERY_CRITICAL: {
    label: 'Battery critical',
    description:
      'Drops the cell to its replacement threshold at once, rather than over the two simulated years it would otherwise take.',
  },
  HEALTHY: {
    label: 'Clear all faults',
    description: 'Returns the meter to its demand curve. What makes a scenario repeatable.',
  },
};

/** The faults an operator can inject, in the order the list offers them. `HEALTHY` is separate. */
export const INJECTABLE_FAULTS: SimulatedFault[] = [
  'LEAK',
  'BURST',
  'COMMS_LOSS',
  'TAMPER',
  'REVERSE_FLOW',
  'BATTERY_CRITICAL',
];

export function faultLabel(fault: SimulatedFault): string {
  return FAULT_LABELS[fault]?.label ?? fault;
}

/**
 * A reporting interval in the shortest honest form.
 *
 * Mirrors the receiver console's duration formatting rather than importing it: this is a configured
 * schedule, not an elapsed time, and the two would drift apart the moment one of them needed
 * "never" or "overdue".
 */
export function formatInterval(seconds: number): string {
  if (!seconds) return '—';
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.round(seconds / 60)} min`;
  if (seconds < 86400) {
    const hours = seconds / 3600;
    return `${Number.isInteger(hours) ? hours : hours.toFixed(1)} h`;
  }
  return `${(seconds / 86400).toFixed(1)} d`;
}

export function formatLitres(litres: number): string {
  return `${Math.round(litres).toLocaleString()} L/day`;
}
