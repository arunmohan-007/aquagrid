/**
 * Types mirroring the Module 17 (Fleet Simulator) API contract.
 */

/** Whether the simulator is emitting. Two states: the fleet is never discarded, only silenced. */
export type SimulatorState = 'RUNNING' | 'PAUSED';

/**
 * A condition the simulator can put a meter into on demand.
 *
 * The set is the set the platform can *detect* — a fault nothing downstream reads would generate
 * traffic and prove nothing. `HEALTHY` clears them all, which is what makes a scenario repeatable.
 */
export type SimulatedFault =
  | 'LEAK'
  | 'BURST'
  | 'COMMS_LOSS'
  | 'TAMPER'
  | 'REVERSE_FLOW'
  | 'BATTERY_CRITICAL'
  | 'HEALTHY';

export interface SimulatorStatus {
  state: SimulatorState;
  /** The tenant whose simulator-source devices are being driven. */
  organizationCode?: string;
  fleetSize: number;
  /**
   * Devices registered as simulated that carry no network address, so nothing the simulator emits
   * for them could ever be resolved back. Listed rather than dropped: a fleet that silently skipped
   * them would look identical to one where they were working.
   */
  unaddressable: string[];
  intervalSeconds: number;
  lastTickAt?: string;
  lastTickMillis: number;
  emitted: number;
  accepted: number;
  duplicates: number;
  /**
   * Refused by the receiver. The number to read first — nothing spoofs the simulator, so a
   * rejection is never stray traffic. It is always a statement about a device row, and it is the
   * same refusal the physical device would get at that address.
   */
  rejected: number;
  /** Ticks a meter stayed silent for under a comms-loss fault. Simulated outage, not failure. */
  suppressed: number;
  metersLeaking: number;
  metersTampered: number;
  metersSilent: number;
}

export interface SimulatedDevice {
  /** The registered device driven — the same id the registry and receiver console use. */
  deviceId: string;
  deviceCode?: string;
  networkAddress?: string;
  transport?: string;
  baselineDailyLitres: number;
  /** Its own duty cycle, from the device's `reportingIntervalSeconds` attribute where it has one. */
  reportingIntervalSeconds: number;
  /**
   * Silenced for cutover: still in the fleet, deliberately not reporting, because a physical device
   * is now answering at this address. The durable control is the device's source.
   */
  suspended: boolean;
  activeFaults: SimulatedFault[];
  lastEmittedAt?: string;
  uplinksEmitted: number;
  uplinksSuppressed: number;
  /** What the receiver did with its most recent uplink. */
  lastStatus?: 'ACCEPTED' | 'DUPLICATE' | 'REJECTED';
  lastErrorCode?: string;
  lastErrorDetail?: string;
}
