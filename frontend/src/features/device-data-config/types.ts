/**
 * Device Data Configuration — what each device is expected to send, and what to do with it.
 *
 * These mirror `DataConfigDtos` on the server. Three lists are deliberately NOT enumerated here as
 * string-literal unions with their own labels — data types, categories and units all come from
 * `GET /device-data-config/{data-types,categories,units}`. The server decides which types exist,
 * which of them take a range or a precision, and which units a tenant may choose; a second copy in
 * the client is a copy that will one day offer something the server rejects, which is a validation
 * error the operator cannot act on because the form offered the value.
 *
 * The platform has removed two such copies already: the metric label map in the browser (now
 * `MetricCatalog`, served) and the importer's `TARGET_FIELDS` array (now the attribute catalogue).
 */

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

/**
 * Who a parameter applies to.
 *
 * `DEVICE_TYPE` is the template every device of that type inherits; `DEVICE` is one device's own
 * override, which replaces the template entry of the same name entire.
 */
export type ParameterScope = 'DEVICE_TYPE' | 'DEVICE';

/**
 * What validation made of a received value.
 *
 * Every one of these is stored. There is no status meaning "discarded", and that is the module's
 * central rule rather than an omission: configuration decides how data is used, never whether it is
 * allowed in.
 */
export type QualityStatus = 'VALID' | 'INVALID' | 'OUT_OF_RANGE' | 'MISSING' | 'UNKNOWN';

export type DiscoveryStatus = 'PENDING' | 'CONFIGURED' | 'IGNORED';

export type ParameterChangeType = 'CREATED' | 'UPDATED' | 'DEACTIVATED' | 'REACTIVATED';

export interface DeviceParameter {
  id: string;
  scope: ParameterScope;
  deviceType: string | null;
  deviceId: string | null;
  parameterName: string;
  displayName: string;
  description: string | null;
  dataType: string;
  unit: string | null;
  category: string;
  /** The key matched in an incoming payload — already resolved, so null never means "the name". */
  payloadKey: string;
  mandatory: boolean;
  dashboardVisible: boolean;
  useForAlarm: boolean;
  useForReports: boolean;
  minValue: number | null;
  maxValue: number | null;
  decimalPrecision: number | null;
  sampleValue: string | null;
  defaultValue: string | null;
  active: boolean;
  sortOrder: number;
  /**
   * True when this row came from the device type's template rather than from the device.
   *
   * Only meaningful when the grid is filtered to one device. It is what stops an operator editing
   * an inherited row expecting to change one meter and changing every device of that type.
   */
  inherited: boolean;
  createdAt: string | null;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface DataTypeInfo {
  value: string;
  label: string;
  numeric: boolean;
  usesPrecision: boolean;
  /** False for JSON and ARRAY: they are catalogued and stored, but produce no reading row. */
  storedAsReading: boolean;
}

export interface CategoryInfo {
  value: string;
  label: string;
}

export interface UnitInfo {
  id: string;
  code: string;
  label: string;
  quantity: string;
  description: string | null;
  /** Platform-supplied. A tenant may add units; it may not edit or shadow these. */
  standard: boolean;
  active: boolean;
  sortOrder: number;
}

export interface DeviceTypeSummary {
  value: string;
  label: string;
  activeParameters: number;
  deviceCount: number;
}

export interface ParameterHistoryEntry {
  id: number;
  parameterId: string;
  parameterName: string;
  changeType: ParameterChangeType;
  previousState: Record<string, unknown> | null;
  newState: Record<string, unknown> | null;
  changeReason: string | null;
  changedBy: string | null;
  changedAt: string;
}

export interface DiscoveredParameter {
  id: string;
  deviceId: string;
  deviceCode: string | null;
  deviceType: string | null;
  /** The payload key verbatim — not canonicalised, so it matches the vendor's documentation. */
  parameterName: string;
  sampleValue: string | null;
  detectedDataType: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  occurrences: number;
  status: DiscoveryStatus;
  parameterId: string | null;
  resolvedAt: string | null;
}

export interface RawTelemetry {
  id: string;
  deviceId: string | null;
  deviceCode: string | null;
  assetId: string | null;
  assetNumber: string | null;
  deviceTimestamp: string | null;
  receivedAt: string;
  communicationType: string | null;
  connectionMode: string;
  messageId: string;
  correlationId: string | null;
  sourceIp: string | null;
  /** The payload as stored: a JSON object, never a string needing a second parse. */
  payload: Record<string, unknown>;
  payloadEncoding: 'JSON' | 'BASE64' | 'TEXT';
  payloadSize: number;
  processingStatus: 'ACCEPTED' | 'DUPLICATE' | 'REJECTED';
  processingError: string | null;
}

export interface EffectiveConfig {
  deviceId: string;
  deviceCode: string;
  deviceType: string;
  parameters: DeviceParameter[];
  pendingDiscoveries: number;
}

/*
 * The query and request shapes below spell their optional properties `?: T | undefined`.
 *
 * Not redundant: the project compiles with `exactOptionalPropertyTypes`, under which `foo?: string`
 * accepts an absent key but rejects `{ foo: undefined }` — and `{ foo: undefined }` is exactly what
 * a form builds when a field is left blank. Widening the type is the honest fix; the alternative is
 * stripping undefined keys at every call site, which is the same behaviour written out four times.
 */

export interface ParameterQuery {
  scope?: ParameterScope | undefined;
  deviceType?: string | undefined;
  deviceId?: string | undefined;
  search?: string | undefined;
  dataType?: string | undefined;
  category?: string | undefined;
  mandatory?: boolean | undefined;
  dashboardVisible?: boolean | undefined;
  useForAlarm?: boolean | undefined;
  useForReports?: boolean | undefined;
  active?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
  sort?: string | undefined;
}

export interface CreateParameterRequest {
  scope: ParameterScope;
  deviceType?: string | undefined;
  deviceId?: string | undefined;
  parameterName: string;
  displayName?: string | undefined;
  description?: string | undefined;
  dataType: string;
  unit?: string | undefined;
  category?: string | undefined;
  payloadKey?: string | undefined;
  mandatory?: boolean | undefined;
  dashboardVisible?: boolean | undefined;
  useForAlarm?: boolean | undefined;
  useForReports?: boolean | undefined;
  minValue?: number | null | undefined;
  maxValue?: number | null | undefined;
  decimalPrecision?: number | null | undefined;
  sampleValue?: string | undefined;
  defaultValue?: string | undefined;
  active?: boolean | undefined;
  sortOrder?: number | undefined;
  changeReason?: string | undefined;
  /** Set when raised from the Discovered list, so that queue closes the rows this answers. */
  discoveredParameterId?: string | undefined;
}

/**
 * Omitted or null means "leave alone", never "clear".
 *
 * To remove a range bound, send `NaN` — an explicit signal, because "I did not send a maximum" and
 * "this parameter has no maximum" are different statements and a nullable field cannot carry both.
 */
export interface UpdateParameterRequest {
  displayName?: string | undefined;
  description?: string | undefined;
  dataType?: string | undefined;
  unit?: string | undefined;
  category?: string | undefined;
  payloadKey?: string | undefined;
  mandatory?: boolean | undefined;
  dashboardVisible?: boolean | undefined;
  useForAlarm?: boolean | undefined;
  useForReports?: boolean | undefined;
  minValue?: number | undefined;
  maxValue?: number | undefined;
  decimalPrecision?: number | null | undefined;
  sampleValue?: string | undefined;
  defaultValue?: string | undefined;
  sortOrder?: number | undefined;
  changeReason?: string | undefined;
  /**
   * Required when the data type or the source key differs from what is stored. The first attempt is
   * answered with a description of exactly what will happen, which is what the dialog shows.
   */
  confirmBreakingChange?: boolean | undefined;
}

export interface DiscoveryQuery {
  deviceId?: string | undefined;
  deviceType?: string | undefined;
  status?: DiscoveryStatus | undefined;
  search?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface RawTelemetryQuery {
  deviceId?: string | undefined;
  status?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}
