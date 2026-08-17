/**
 * Types mirroring the authentication API contract.
 *
 * These are hand-written for Module 1 and will be replaced by types generated from the
 * published OpenAPI document in Module 35 — at which point a backend field rename becomes
 * a frontend build failure rather than a runtime `undefined`.
 */

export interface OrganizationSummary {
  id: string;
  code: string;
  name: string;
  type: string;
  timezone: string;
  locale: string;
  currencyCode: string;
  unitSystem: string;
  /** [longitude, latitude] in EPSG:4326 — GeoJSON axis order. */
  defaultCenter?: [number, number];
  defaultZoom?: number;
  /** [minX, minY, maxX, maxY] of the tenant's service area. */
  boundaryBbox?: [number, number, number, number];
}

export interface CurrentUser {
  id: string;
  username: string;
  email: string;
  fullName: string;
  jobTitle?: string;
  phone?: string;
  avatarUrl?: string;
  status: 'PENDING' | 'ACTIVE' | 'DISABLED' | 'LOCKED';
  mfaEnabled: boolean;
  mustChangePassword: boolean;
  lastLoginAt?: string;
  timezone: string;
  locale: string;
  roles: string[];
  permissions: string[];
  organization: OrganizationSummary;
}

export interface AuthenticationResponse {
  accessToken?: string;
  tokenType?: string;
  expiresIn?: number;
  expiresAt?: string;
  mfaRequired: boolean;
  mfaToken?: string;
  mustChangePassword?: boolean;
  user?: CurrentUser;
}

export interface LoginPayload {
  identifier: string;
  password: string;
  deviceLabel?: string;
}

export interface MfaChallengePayload {
  mfaToken: string;
  code: string;
  deviceLabel?: string;
}

export interface SessionSummary {
  id: string;
  deviceLabel?: string;
  userAgent?: string;
  clientIp?: string;
  issuedAt: string;
  lastUsedAt?: string;
  expiresAt: string;
  current: boolean;
}

export interface PasswordPolicy {
  minLength: number;
  maxLength: number;
  requireUppercase: boolean;
  requireLowercase: boolean;
  requireDigit: boolean;
  requireSpecial: boolean;
  historyDepth: number;
  rules: string[];
}

export interface MfaSetup {
  secret: string;
  provisioningUri: string;
}

export interface MfaActivation {
  mfaEnabled: boolean;
  recoveryCodes: string[];
}

export interface MessageResponse {
  message: string;
}
