/**
 * Types for the reading-export endpoint (Module 25).
 */

export type ExportFormat = 'XLSX' | 'PDF';

/**
 * What to export.
 *
 * `deviceType` and `transport` are independent axes, not alternatives: "every pressure sensor" and
 * "everything on LoRaWAN" are different questions, and a report may ask both at once — the same
 * device-registry distinction the platform already draws between what an instrument is and which
 * network it speaks on.
 */
// Every optional field spelled `| undefined` rather than `?:` — the project runs
// exactOptionalPropertyTypes, and the page always passes these keys, just sometimes empty.
export interface ReportRequest {
  format: ExportFormat;
  deviceId: string | undefined;
  deviceType: string | undefined;
  transport: string | undefined;
  metric?: string | undefined;
  from: string;
  to: string;
}
