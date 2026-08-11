import type {
  CommunicationType,
  DeviceProtocol,
  DeviceSource,
  DeviceStatus,
  DeviceType,
} from './types';

/**
 * Display names, kept apart from the enum values.
 *
 * The stored values cannot change without rewriting every row, and several of them do not read
 * well when spelled out: NB_IOT is written "NB-IoT" everywhere in the industry, and CELLULAR means
 * 4G to the people registering the device. One map, so the list, the filter and the form can never
 * disagree about what a thing is called.
 */
export const COMMUNICATION_TYPE_LABELS: Record<CommunicationType, string> = {
  LORAWAN: 'LoRaWAN',
  NB_IOT: 'NB-IoT',
  CELLULAR: '4G Cellular',
  ETHERNET: 'Ethernet',
  MQTT: 'MQTT broker',
  TCP: 'TCP',
  UDP: 'UDP',
  WEBSOCKET: 'WebSocket',
};

export const DEVICE_PROTOCOL_LABELS: Record<DeviceProtocol, string> = {
  HTTP: 'HTTP',
  MQTT: 'MQTT',
};

/** HTTP first: it is the default and the bearer Postman / webhooks already use. */
export const DEVICE_PROTOCOLS: DeviceProtocol[] = ['HTTP', 'MQTT'];

/**
 * Device Source, spelled the way an operator would say it.
 *
 * "Live Device" rather than "Live": on its own the word reads as a status, and this is not one — a
 * decommissioned meter in the ground is still a live device in this sense. The pairing with
 * "Simulator" is what makes the axis legible. "Postman/API test" names the third origin — hand
 * injection during integration testing — without looking like a network.
 */
export const DEVICE_SOURCE_LABELS: Record<DeviceSource, string> = {
  SIMULATOR: 'Simulator',
  LIVE: 'Live Device',
  API_TEST: 'Postman/API test',
};

/** Simulator first: it is the choice being made deliberately. LIVE is the default already set. */
export const DEVICE_SOURCES: DeviceSource[] = ['SIMULATOR', 'LIVE', 'API_TEST'];

export const DEVICE_TYPE_LABELS: Record<DeviceType, string> = {
  WATER_METER: 'Water Meter',
  BULK_FLOW_METER: 'Bulk Flow Meter',
  PRESSURE_SENSOR: 'Pressure Sensor',
  LEVEL_SENSOR: 'Level Sensor',
  QUALITY_SENSOR: 'Quality Sensor',
  VALVE_CONTROLLER: 'Valve Controller',
  PUMP_CONTROLLER: 'Pump Controller',
  ENERGY_METER: 'Energy Meter',
  GATEWAY: 'Gateway',
  OTHER: 'Other',
};

export const DEVICE_STATUS_LABELS: Record<DeviceStatus, string> = {
  PROVISIONED: 'Provisioned',
  ACTIVE: 'Active',
  INACTIVE: 'Inactive',
  DECOMMISSIONED: 'Decommissioned',
  FAULTY: 'Faulty',
};

/** Every type the form offers, in the order operators most often register them. */
export const DEVICE_TYPES: DeviceType[] = [
  'WATER_METER', 'BULK_FLOW_METER', 'PRESSURE_SENSOR', 'LEVEL_SENSOR',
  'QUALITY_SENSOR', 'VALVE_CONTROLLER', 'PUMP_CONTROLLER', 'ENERGY_METER',
  'GATEWAY', 'OTHER',
];

export const DEVICE_STATUSES: DeviceStatus[] = [
  'PROVISIONED', 'ACTIVE', 'INACTIVE', 'FAULTY', 'DECOMMISSIONED',
];
