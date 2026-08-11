import axios, {
  AxiosError,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios';
import { tokenStore } from '@/lib/auth/tokenStore';
import { toProblem } from './problem';

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

/** Endpoints that must never trigger a refresh attempt — they *are* the refresh path. */
const AUTH_BYPASS = ['/auth/login', '/auth/refresh', '/auth/mfa/challenge', '/auth/password/forgot',
  '/auth/password/reset', '/auth/password/policy'];

interface RetriableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean;
}

export const http = axios.create({
  baseURL: API_BASE,
  timeout: 30_000,
  /*
   * Required for the refresh-token cookie to be sent and stored. Without it the browser
   * silently drops the cookie and every session ends after 15 minutes with no clue why.
   */
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
});

/** A bare client for the refresh call, so a 401 there cannot recurse into the interceptor. */
const refreshClient = axios.create({
  baseURL: API_BASE,
  timeout: 15_000,
  withCredentials: true,
});

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = tokenStore.get();
  if (token && !AUTH_BYPASS.some((path) => config.url?.startsWith(path))) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  // Lets a browser trace be correlated with a server log line.
  config.headers.set('X-Request-Id', crypto.randomUUID().replace(/-/g, ''));
  return config;
});

/*
 * Single-flight refresh.
 *
 * A dashboard fires a dozen requests at once. When the access token expires they all
 * receive 401 simultaneously, and a naive per-request refresh would fire a dozen refresh
 * calls. Because refresh tokens *rotate*, eleven of those would present an
 * already-rotated token — which the server correctly treats as theft, revoking the whole
 * family and signing the user out.
 *
 * So: the first 401 starts one refresh, every other request waits on that same promise,
 * and all of them retry once it resolves.
 */
let refreshInFlight: Promise<string> | null = null;
let onSessionExpired: (() => void) | null = null;

export function setSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler;
}

async function refreshAccessToken(): Promise<string> {
  refreshInFlight ??= refreshClient
    .post<{ accessToken: string; expiresAt?: string; expiresIn?: number }>('/auth/refresh')
    .then((response) => {
      const { accessToken, expiresAt, expiresIn } = response.data;
      tokenStore.set(accessToken, expiresAt, expiresIn);
      return accessToken;
    })
    .finally(() => {
      refreshInFlight = null;
    });
  return refreshInFlight;
}

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const config = error.config as RetriableConfig | undefined;
    const problem = toProblem(error);

    const isAuthFailure = error.response?.status === 401;
    const isRefreshable =
      isAuthFailure &&
      config &&
      !config._retried &&
      !AUTH_BYPASS.some((path) => config.url?.startsWith(path)) &&
      // A reused refresh token is a security event, not a transient expiry: retrying
      // would be pointless and would hide the incident from the user.
      problem.code !== 'AUTH_REFRESH_TOKEN_REUSED';

    if (isRefreshable) {
      try {
        const token = await refreshAccessToken();
        config._retried = true;
        config.headers.set('Authorization', `Bearer ${token}`);
        return await http.request(config);
      } catch {
        tokenStore.clear();
        onSessionExpired?.();
        return Promise.reject(error);
      }
    }

    if (isAuthFailure && !AUTH_BYPASS.some((path) => config?.url?.startsWith(path))) {
      tokenStore.clear();
      onSessionExpired?.();
    }
    return Promise.reject(error);
  },
);

/** Restores the session on page load using the HttpOnly cookie. */
export async function bootstrapSession(): Promise<string | null> {
  try {
    return await refreshAccessToken();
  } catch {
    return null;
  }
}

export async function apiGet<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return (await http.get<T>(url, config)).data;
}

export async function apiPost<T>(
  url: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  return (await http.post<T>(url, body, config)).data;
}

export async function apiPut<T>(
  url: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  return (await http.put<T>(url, body, config)).data;
}

export async function apiPatch<T>(
  url: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  return (await http.patch<T>(url, body, config)).data;
}

export async function apiDelete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return (await http.delete<T>(url, config)).data;
}
