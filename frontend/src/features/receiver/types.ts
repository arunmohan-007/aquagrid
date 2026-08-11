/**
 * Types mirroring the Module 18 (Receiver) API contract.
 */
import type { PageResponse } from '@/features/users/types';

/**
 * What became of a packet.
 *
 * Three values, not one per failure mode — *why* it failed is the error code. `DUPLICATE` is
 * deliberately not a failure: a retransmission means the network never got our acknowledgement,
 * which is a link-quality signal, but the reading itself was already safely ingested.
 */
export type ReceptionStatus = 'ACCEPTED' | 'DUPLICATE' | 'REJECTED';

/**
 * One packet, as the receiver recorded it.
 *
 * Both timestamps are always present in the model even though `observedAt` is often null, and they
 * are never collapsed into one. `receivedAt` is our clock; `observedAt` is the device's; the gap
 * between them (`latencySeconds`) is the most diagnostic number on the row.
 */
export interface ReceiverPacket {
  packetId: string;
  deviceId?: string;
  deviceCode?: string;
  transport: string;
  communicationProfile?: string;
  /** When the platform took delivery. Always present. */
  receivedAt: string;
  /** The device's own clock. Absent when the payload carried no timestamp, or was never parsed. */
  observedAt?: string;
  /** Device clock minus ours, in seconds. Negative means the device is running ahead. */
  latencySeconds?: number;
  status: ReceptionStatus;
  /** Stable platform error code, e.g. RECEIVER_UNKNOWN_DEVICE. Absent when accepted. */
  errorCode?: string;
  errorDetail?: string;
  payloadSize: number;
  processingTimeMs: number;
  authenticationScheme?: string;
  /** Which strategy identified the device — how, not just whether. */
  resolutionStrategy?: string;
  parser?: string;
  sourceIp?: string;
  correlationId?: string;
  /** Metric values recorded from this packet. Empty for keep-alives and rejected packets. */
  readings: Record<string, number>;
}

/** A device's recent reporting behaviour, from the hourly rollups. */
export interface ReportingDevice {
  deviceId: string;
  deviceCode?: string;
  name?: string;
  transport?: string;
  deviceSource?: 'LIVE' | 'SIMULATOR';
  status?: string;
  lastPacketAt?: string;
  /**
   * Seconds since the last packet. The field this view exists for — a device with a healthy
   * acceptance rate that last spoke nine hours ago is the fault worth finding, and it is invisible
   * in any view that only shows what did arrive.
   */
  silentForSeconds?: number;
  accepted: number;
  duplicates: number;
  rejected: number;
  /** Accepted / (accepted + rejected), 0–1. Duplicates are excluded from the denominator. */
  successRate?: number;
  bytesReceived: number;
}

/** A transport listener's self-report. */
export interface TransportStatus {
  transport: string;
  displayName: string;
  running: boolean;
  endpoint: string;
  stateful: boolean;
  activeConnections: number;
  packetsReceived: number;
  errors: number;
  lastPacketAt?: string;
  startedAt?: string;
}

export interface PacketSearchQuery {
  deviceId?: string | undefined;
  transport?: string | undefined;
  status?: ReceptionStatus | undefined;
  errorCode?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export type { PageResponse };
