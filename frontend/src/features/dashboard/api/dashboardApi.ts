import { apiGet } from '@/lib/api/httpClient';

/** One panchayat's share of the network and its mapped facilities. */
export interface PanchayatSummary {
  panchayat: string;
  pipelineCount: number;
  /** Ellipsoidal length in metres — the UI converts to km for display. */
  pipelineLengthM: number;
  tanks: number;
  openWells: number;
  boreWells: number;
}

/**
 * Dashboard totals plus the rows they were summed from.
 *
 * The totals are computed server-side from these exact rows, so the headline figure and the chart
 * below it are arithmetically guaranteed to agree.
 */
export interface NetworkSummary {
  totalPipelineLengthM: number;
  pipelineCount: number;
  tanks: number;
  openWells: number;
  boreWells: number;
  panchayats: PanchayatSummary[];
}

export const dashboardApi = {
  networkSummary: () => apiGet<NetworkSummary>('/gis/dashboard/network'),
};
