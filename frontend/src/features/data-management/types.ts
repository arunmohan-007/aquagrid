/**
 * Data Management — the layer and attribute catalogue.
 *
 * These mirror `MetadataDtos` on the server. The data-type list is deliberately NOT enumerated
 * here as a union of string literals with its own labels: it is fetched from
 * `GET /data-management/data-types`, because the server decides which types exist, which of them
 * take a length, which take precision and scale, and which an administrator may select. A second
 * copy in the client is a copy that will one day offer a type the server rejects — the same
 * mistake the device registration form avoids by fetching its transport catalogue.
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

export interface MetadataLayer {
  id: string;
  code: string;
  title: string;
  description: string | null;
  assetType: string;
  visible: boolean;
  sortOrder: number;
  /** Active attributes on this layer. */
  attributeCount: number;
}

/** Where an attribute's value physically lives. Drives what the UI lets you do with it. */
export type AttributeStorage = 'JSONB' | 'COLUMN' | 'GEOMETRY' | 'TYPE_TABLE';

export interface LayerAttribute {
  id: string;
  layerId: string;
  fieldName: string;
  displayName: string;
  description: string | null;
  dataType: string;
  maxLength: number | null;
  numericPrecision: number | null;
  numericScale: number | null;
  defaultValue: string | null;
  sampleValue: string | null;
  mandatory: boolean;
  unique: boolean;
  editable: boolean;
  visible: boolean;
  active: boolean;
  /**
   * True for fields the platform's own code reads by name. Their name, type and mandatory flag
   * are locked and they cannot be retired; labels, descriptions, samples and visibility are the
   * tenant's.
   */
  system: boolean;
  storage: AttributeStorage;
  /** False when the field cannot be filled by an import — a typed detail-table column. */
  importable: boolean;
  sortOrder: number;
  createdBy: string | null;
  createdAt: string;
  modifiedBy: string | null;
  modifiedAt: string;
}

export interface AttributeDataTypeInfo {
  code: string;
  label: string;
  description: string;
  usesLength: boolean;
  usesPrecisionScale: boolean;
  numeric: boolean;
  /** False for types only the platform may declare — geometry. Shown, but not offerable. */
  selectable: boolean;
  sampleValue: string;
}

export type AttributeChangeType = 'CREATED' | 'UPDATED' | 'DEACTIVATED' | 'REACTIVATED';

export interface AttributeHistoryEntry {
  id: number;
  attributeId: string;
  fieldName: string;
  changeType: AttributeChangeType;
  previousState: Record<string, unknown> | null;
  newState: Record<string, unknown> | null;
  changeReason: string | null;
  changedBy: string | null;
  changedAt: string;
}

export interface AttributeValidationRule {
  id: string;
  attributeId: string;
  ruleType: string;
  ruleValue: string;
  errorMessage: string | null;
  active: boolean;
  sortOrder: number;
}

/*
 * The request and query shapes below spell their optional properties `?: T | undefined`.
 *
 * That is not redundant here: the project compiles with `exactOptionalPropertyTypes`, under which
 * `foo?: string` accepts an absent key but rejects `{ foo: undefined }` — and `{ foo: undefined }`
 * is exactly what a form builds when a field is left blank. Widening the type is the honest fix;
 * the alternative is stripping undefined keys at every call site, which is the same behaviour
 * written out four times.
 */

/** Every filter is optional; omitting one means "do not filter on it". */
export interface AttributeQuery {
  layerId?: string | undefined;
  search?: string | undefined;
  dataType?: string | undefined;
  mandatory?: boolean | undefined;
  editable?: boolean | undefined;
  visible?: boolean | undefined;
  active?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
  sort?: string | undefined;
  direction?: 'ASC' | 'DESC' | undefined;
}

export interface CreateAttributeRequest {
  layerId: string;
  fieldName: string;
  displayName?: string | undefined;
  description?: string | undefined;
  dataType: string;
  maxLength?: number | null | undefined;
  numericPrecision?: number | null | undefined;
  numericScale?: number | null | undefined;
  defaultValue?: string | undefined;
  sampleValue?: string | undefined;
  mandatory: boolean;
  unique: boolean;
  editable: boolean;
  visible: boolean;
  active: boolean;
  changeReason?: string | undefined;
}

/** Null or omitted means "leave it alone", never "clear it". */
export interface UpdateAttributeRequest {
  fieldName?: string | undefined;
  displayName?: string | undefined;
  description?: string | undefined;
  dataType?: string | undefined;
  maxLength?: number | null | undefined;
  numericPrecision?: number | null | undefined;
  numericScale?: number | null | undefined;
  defaultValue?: string | undefined;
  sampleValue?: string | undefined;
  mandatory?: boolean | undefined;
  unique?: boolean | undefined;
  editable?: boolean | undefined;
  visible?: boolean | undefined;
  /**
   * Required when `fieldName` or `dataType` differs from what is stored. The first attempt is
   * answered with `ATTRIBUTE_CHANGE_REQUIRES_CONFIRMATION` and a description of the effect, which
   * is what the confirmation dialog shows.
   */
  confirmBreakingChange?: boolean | undefined;
  changeReason?: string | undefined;
}

/** Sortable grid columns, matching the server's allow-list. */
export type SortableColumn =
  | 'fieldName'
  | 'displayName'
  | 'dataType'
  | 'maxLength'
  | 'mandatory'
  | 'uniqueValue'
  | 'editable'
  | 'visible'
  | 'active'
  | 'sortOrder'
  | 'createdAt'
  | 'updatedAt';
