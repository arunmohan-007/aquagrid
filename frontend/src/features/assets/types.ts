/**
 * Types mirroring the Module 23 (Asset Management) API contract.
 */
import type { PageResponse } from '@/features/users/types';

/** Mirrors the backend AssetType enum. TANK is labelled "Over Head Tank" in the UI. */
export type AssetType =
  | 'METER' | 'VALVE' | 'PIPELINE' | 'HYDRANT' | 'TANK' | 'RESERVOIR'
  | 'PUMP_STATION' | 'OPEN_WELL' | 'BORE_WELL' | 'DMA' | 'PANCHAYAT'
  | 'SERVICE_CONNECTION' | 'SENSOR';

export type AssetStatus =
  | 'PLANNED' | 'IN_SERVICE' | 'OUT_OF_SERVICE' | 'DECOMMISSIONED' | 'DAMAGED';

export interface Asset {
  id: string;
  assetCode: string;
  assetType: AssetType;
  name: string;
  status: AssetStatus;
  installDate?: string;
  decommissionDate?: string;
  /** [lon, lat] for point geometry; absent for lines/polygons */
  coordinates?: [number, number];
  geometryType?: string;
  /** Geodesic length in metres, computed from the geometry; present for line assets only. */
  lengthM?: number;
  attributes: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface AssetRequest {
  assetCode?: string;
  assetType?: AssetType;
  name?: string;
  status?: AssetStatus;
  installDate?: string;
  decommissionDate?: string;
  geometry?: { type: string; coordinates: number[] | number[][] | number[][][] };
  attributes?: Record<string, unknown>;
}

export interface AttachmentSummary {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  uploadedAt: string;
}

export interface AssetListQuery {
  assetType?: AssetType | undefined;
  search?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface BulkImportStatus {
  state: string;
  total: number;
  imported: number;
  failed: number;
  errors: string[];
}

export type { PageResponse };
