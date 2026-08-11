import type { AttributeChangeType, LayerAttribute } from './types';

/**
 * Wording for the Data Management screen.
 *
 * Only the things the server does not already name are here. Data-type labels are not: they come
 * from `GET /data-management/data-types` along with which configuration fields each one needs, so
 * the client cannot fall behind the server's list.
 */

export const CHANGE_TYPE_LABELS: Record<AttributeChangeType, string> = {
  CREATED: 'Created',
  UPDATED: 'Updated',
  DEACTIVATED: 'Retired',
  REACTIVATED: 'Returned to service',
};

/** The grid's columns, in order, with the widths they open at. */
export interface ColumnDef {
  id: string;
  label: string;
  width: number;
  /** The server-side sort key, when the column is sortable. */
  sort?: string;
  align?: 'left' | 'right' | 'center';
}

/**
 * Column widths are the starting point, not the rule — every column is resizable by dragging its
 * right edge, and the widths persist for the session. They are sized to the content each column
 * actually holds: field names are short identifiers, descriptions are sentences.
 */
export const COLUMNS: ColumnDef[] = [
  { id: 'fieldName', label: 'Field Name', width: 180, sort: 'fieldName' },
  { id: 'displayName', label: 'Display Name', width: 190, sort: 'displayName' },
  { id: 'description', label: 'Description', width: 320 },
  { id: 'dataType', label: 'Data Type', width: 130, sort: 'dataType' },
  { id: 'maxLength', label: 'Length', width: 90, sort: 'maxLength', align: 'right' },
  { id: 'numericPrecision', label: 'Precision', width: 96, align: 'right' },
  { id: 'numericScale', label: 'Scale', width: 80, align: 'right' },
  { id: 'defaultValue', label: 'Default Value', width: 150 },
  { id: 'sampleValue', label: 'Sample Value', width: 170 },
  { id: 'mandatory', label: 'Mandatory', width: 110, sort: 'mandatory', align: 'center' },
  { id: 'unique', label: 'Unique', width: 96, sort: 'uniqueValue', align: 'center' },
  { id: 'editable', label: 'Editable', width: 100, sort: 'editable', align: 'center' },
  { id: 'visible', label: 'Visible', width: 96, sort: 'visible', align: 'center' },
  { id: 'active', label: 'Active', width: 96, sort: 'active', align: 'center' },
  { id: 'createdBy', label: 'Created By', width: 150 },
  { id: 'createdAt', label: 'Created Date', width: 170, sort: 'createdAt' },
  { id: 'modifiedBy', label: 'Modified By', width: 150 },
  { id: 'modifiedAt', label: 'Modified Date', width: 170, sort: 'updatedAt' },
];

/**
 * Why an attribute cannot be edited or retired, in the words the operator needs.
 *
 * Returning the reason rather than a boolean is what lets a disabled button explain itself. A
 * greyed-out "Retire" with no tooltip is the control that generates the support ticket.
 */
export function lockReason(attribute: LayerAttribute | null): string | null {
  if (!attribute) return null;
  if (attribute.storage === 'TYPE_TABLE') {
    return `${attribute.displayName} belongs to a typed detail table. It is catalogued and exported here, but its value is set through the asset's own editor.`;
  }
  if (attribute.system) {
    return `${attribute.displayName} is a system field: the platform reads it by name and it is backed by a database column. Its label, description, default, sample and visibility can be changed — its name, type, mandatory flag and active state cannot.`;
  }
  return null;
}

/** A UUID as something readable in a grid cell, until a user directory is wired in. */
export function shortActor(id: string | null): string {
  if (!id) return 'System';
  return id.slice(0, 8);
}

export function formatTimestamp(iso: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}
