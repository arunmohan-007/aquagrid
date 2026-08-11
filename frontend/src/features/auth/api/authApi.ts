import { apiDelete, apiGet, apiPost } from '@/lib/api/httpClient';
import type {
  AuthenticationResponse,
  CurrentUser,
  LoginPayload,
  MessageResponse,
  MfaActivation,
  MfaChallengePayload,
  MfaSetup,
  PasswordPolicy,
  SessionSummary,
} from '../types';

/**
 * The authentication endpoints, in one place.
 *
 * Components never call `http` directly. Routing every call through this module means the
 * base path, the request shape and the response type live together, so an API change is a
 * one-file edit with a compiler-checked blast radius.
 */
export const authApi = {
  login: (payload: LoginPayload) =>
    apiPost<AuthenticationResponse>('/auth/login', {
      ...payload,
      deviceLabel: payload.deviceLabel ?? describeDevice(),
    }),

  mfaChallenge: (payload: MfaChallengePayload) =>
    apiPost<AuthenticationResponse>('/auth/mfa/challenge', {
      ...payload,
      deviceLabel: payload.deviceLabel ?? describeDevice(),
    }),

  /* No body: the refresh token travels in the HttpOnly cookie and is unreadable here. */
  refresh: () => apiPost<AuthenticationResponse>('/auth/refresh'),

  logout: () => apiPost<MessageResponse>('/auth/logout'),

  logoutAll: () => apiPost<MessageResponse>('/auth/logout-all'),

  me: () => apiGet<CurrentUser>('/auth/me'),

  passwordPolicy: () => apiGet<PasswordPolicy>('/auth/password/policy'),

  changePassword: (currentPassword: string, newPassword: string) =>
    apiPost<MessageResponse>('/auth/password/change', { currentPassword, newPassword }),

  forgotPassword: (email: string) =>
    apiPost<MessageResponse>('/auth/password/forgot', { email }),

  resetPassword: (token: string, newPassword: string) =>
    apiPost<MessageResponse>('/auth/password/reset', { token, newPassword }),

  beginMfaEnrolment: () => apiPost<MfaSetup>('/auth/mfa/setup'),

  activateMfa: (code: string) => apiPost<MfaActivation>('/auth/mfa/activate', { code }),

  disableMfa: (password: string, code: string) =>
    apiPost<MessageResponse>('/auth/mfa/disable', { password, code }),

  sessions: () => apiGet<SessionSummary[]>('/auth/sessions'),

  revokeSession: (sessionId: string) => apiDelete<void>(`/auth/sessions/${sessionId}`),
};

/**
 * A short, human-readable device label for the sessions screen.
 *
 * Derived from the user agent rather than fingerprinting: the purpose is to help a person
 * recognise "that is my laptop" versus "I do not know that device", not to identify the
 * browser uniquely.
 */
function describeDevice(): string {
  const ua = navigator.userAgent;
  const browser =
    /Edg\//.test(ua) ? 'Edge'
    : /OPR\//.test(ua) ? 'Opera'
    : /Firefox\//.test(ua) ? 'Firefox'
    : /Chrome\//.test(ua) ? 'Chrome'
    : /Safari\//.test(ua) ? 'Safari'
    : 'Browser';
  const platform =
    /Windows/.test(ua) ? 'Windows'
    : /Android/.test(ua) ? 'Android'
    : /iPhone|iPad/.test(ua) ? 'iOS'
    : /Mac OS X/.test(ua) ? 'macOS'
    : /Linux/.test(ua) ? 'Linux'
    : 'Unknown OS';
  return `${browser} on ${platform}`;
}
