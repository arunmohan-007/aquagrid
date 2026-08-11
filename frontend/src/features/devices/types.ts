/**
 * Types mirroring the Module 6 (Device Registration) API contract.
 */
import type { PageResponse } from '@/features/users/types';

/**
 * The communication technologies a device can use — every one of them a *network*.
 *
 * "Simulator" is deliberately not here — see {@link DeviceSource}. HTTP is deliberately not here
 * either: it answers "how does the packet arrive?", which is {@link DeviceProtocol}.
 */
export type CommunicationType =
  | 'LORAWAN' | 'NB_IOT' | 'CELLULAR' | 'ETHERNET'
  | 'MQTT' | 'TCP' | 'UDP' | 'WEBSOCKET';

/**
 * How telemetry reaches the platform — the ingress protocol, independent of the network.
 *
 * A LoRaWAN meter whose network server posts to our webhook has protocol HTTP; a device that
 * publishes onto a broker has protocol MQTT whichever radio it sits behind.
 */
export type DeviceProtocol = 'HTTP' | 'MQTT';

/**
 * Where a device's telemetry comes from, independent of the network it arrives on.
 *
 * A simulated meter still emulates NB-IoT or LoRaWAN, IMEI and all; what makes it simulated is that
 * the platform generates its readings rather than a device in the ground. Keeping this on its own
 * axis is what lets an operator exclude synthetic traffic from a water balance without excluding a
 * whole transport. API_TEST covers hand-injected Postman / client traffic the same way.
 */
export type DeviceSource = 'LIVE' | 'SIMULATOR' | 'API_TEST';

export type DeviceType =
  | 'WATER_METER' | 'BULK_FLOW_METER' | 'PRESSURE_SENSOR' | 'LEVEL_SENSOR'
  | 'QUALITY_SENSOR' | 'VALVE_CONTROLLER' | 'PUMP_CONTROLLER' | 'ENERGY_METER'
  | 'GATEWAY' | 'OTHER';

export type DeviceStatus =
  | 'PROVISIONED' | 'ACTIVE' | 'INACTIVE' | 'DECOMMISSIONED' | 'FAULTY';

export interface Device {
  id: string;
  /** Operator-facing identity, unique per tenant. Immutable after registration. */
  deviceCode: string;
  name: string;
  deviceType: DeviceType;
  assetNumber?: string;
  assetId?: string;
  deviceSource: DeviceSource;
  /** HTTP or MQTT — how packets arrive. Orthogonal to {@link communicationType}. */
  protocol: DeviceProtocol;
  communicationType: CommunicationType;
  manufacturer?: string;
  model?: string;
  serialNumber?: string;
  installationDate?: string;
  status: DeviceStatus;
  /** [lon, lat] in EPSG:4326; absent when the device has no recorded position. */
  coordinates?: [number, number];
  /** Derived server-side from the communication block — never sent on a request. */
  networkAddress?: string;
  /** Non-secret communication fields, keyed by the field definition's `key`. */
  communication: Record<string, string>;
  /** Keys of the secret fields (AppKey) that currently hold a value. Never the values. */
  communicationSecretsSet: string[];
  firmwareVersion?: string;
  batteryV?: number;
  rssi?: number;
  snr?: number;
  lastSeenAt?: string;
  attributes: Record<string, unknown>;
}

/**
 * Every field is optional and explicitly admits `undefined`: the project builds with
 * `exactOptionalPropertyTypes`, and the form assembles this payload by dropping blank inputs, so
 * "absent" has to be expressible as a value. On update, absent means "leave alone".
 */
export interface DeviceRequest {
  deviceCode?: string | undefined;
  name?: string | undefined;
  deviceType?: DeviceType | undefined;
  assetNumber?: string | undefined;
  assetId?: string | undefined;
  deviceSource?: DeviceSource | undefined;
  protocol?: DeviceProtocol | undefined;
  communicationType?: CommunicationType | undefined;
  manufacturer?: string | undefined;
  model?: string | undefined;
  serialNumber?: string | undefined;
  installationDate?: string | undefined;
  status?: DeviceStatus | undefined;
  coordinates?: [number, number] | undefined;
  communication?: Record<string, string> | undefined;
  firmwareVersion?: string | undefined;
}

/**
 * One communication-specific input, as declared by the server.
 *
 * The form renders from this rather than from a hard-coded table, so a field the server requires
 * can never be missing from the form, and a field the form invents can never be silently dropped.
 */
export interface CommunicationFieldDefinition {
  key: string;
  label: string;
  required: boolean;
  /** Human-readable rule, e.g. "16 hexadecimal characters". Shown as helper text. */
  expectation: string;
  /** Write-only: sent on save, never returned. Rendered as a password field. */
  secret: boolean;
}

export interface CommunicationTypeDefinition {
  id: CommunicationType;
  /** Which field becomes the device's network address; null when the type has none. */
  identityField: string | null;
  fields: CommunicationFieldDefinition[];
}

export interface DeviceListQuery {
  status?: DeviceStatus | undefined;
  transport?: CommunicationType | undefined;
  deviceType?: DeviceType | undefined;
  source?: DeviceSource | undefined;
  protocol?: DeviceProtocol | undefined;
  search?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export type { PageResponse };
