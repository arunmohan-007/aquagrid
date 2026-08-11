import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api/dashboardApi';

export const queryKeys = {
  dashboard: {
    network: ['dashboard', 'network'] as const,
  },
} as const;

/**
 * Network and facility totals.
 *
 * Kept fresh for a minute: an import can change every number on this page, and an operator who
 * runs one and switches to the dashboard should not be reading yesterday's totals — but a
 * per-render refetch would hammer an aggregate query for a page nobody is editing.
 */
export function useNetworkSummary() {
  return useQuery({
    queryKey: queryKeys.dashboard.network,
    queryFn: () => dashboardApi.networkSummary(),
    staleTime: 60_000,
  });
}
