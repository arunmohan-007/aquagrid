import { QueryClient } from '@tanstack/react-query';
import { AxiosError } from 'axios';

/**
 * Server-state policy.
 *
 * The defaults are conservative because this is an operations product: a stale tank level
 * or a stale alarm list is worse than a slightly chattier client. Individual queries
 * override `staleTime` and `refetchInterval` per widget — live telemetry polls, an asset
 * register does not.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: true,
      refetchOnReconnect: true,
      retry: (failureCount, error) => {
        /*
         * Never retry a request the server has already answered definitively. Retrying a
         * 401 races the refresh interceptor; retrying a 403 or a 422 just repeats a
         * decision; retrying a 429 makes the rate limit worse.
         */
        if (error instanceof AxiosError) {
          const status = error.response?.status ?? 0;
          if (status === 401 || status === 403 || status === 404 || status === 422 || status === 429) {
            return false;
          }
        }
        return failureCount < 2;
      },
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8_000),
    },
    mutations: {
      // A mutation is a side effect. Replaying it automatically is how duplicate work
      // orders and double-issued commands happen.
      retry: false,
    },
  },
});

export const queryKeys = {
  auth: {
    me: ['auth', 'me'] as const,
    sessions: ['auth', 'sessions'] as const,
    passwordPolicy: ['auth', 'password-policy'] as const,
  },
} as const;
