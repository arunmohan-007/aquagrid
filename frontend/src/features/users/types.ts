/**
 * Types mirroring the Module 2 (user & role management) API contract.
 *
 * Hand-written until the OpenAPI-driven client generator (Module 35) lands; kept structurally
 * identical to {@link AuthResponses}/{@link UserManagementResponses} on the backend so the swap is
 * a one-file rename.
 */

export type UserStatus = 'PENDING' | 'ACTIVE' | 'DISABLED' | 'LOCKED';

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface UserSummary {
  id: string;
  username: string;
  email: string;
  fullName: string;
  jobTitle?: string;
  status: UserStatus;
  mfaEnabled: boolean;
  lastLoginAt?: string;
  roles: string[];
}

export interface UserDetail {
  id: string;
  username: string;
  email: string;
  fullName: string;
  jobTitle?: string;
  phone?: string;
  avatarUrl?: string;
  status: UserStatus;
  mfaEnabled: boolean;
  mustChangePassword: boolean;
  lastLoginAt?: string;
  lastLoginIp?: string;
  timezone?: string;
  locale?: string;
  createdAt: string;
  roles: string[];
  permissions: string[];
}

export interface RoleSummary {
  id: string;
  code: string;
  name: string;
  description?: string;
  system: boolean;
  permissionCount: number;
}

export interface RoleDetail {
  id: string;
  code: string;
  name: string;
  description?: string;
  system: boolean;
  permissions: string[];
}

export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED';

export interface InvitationSummary {
  id: string;
  email: string;
  fullName: string;
  username: string;
  roleCodes: string[];
  invitedAt: string;
  expiresAt: string;
  status: InvitationStatus;
}

export interface InvitationCreated {
  token: string;
  expiresAt: string;
}

export interface MessageResponse {
  message: string;
}

export interface UserListQuery {
  status?: UserStatus | undefined;
  search?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface CreateUserPayload {
  email: string;
  fullName: string;
  username: string;
  jobTitle?: string | undefined;
  phone?: string | undefined;
  roleCodes?: string[] | undefined;
  mustChangePassword?: boolean | undefined;
}

export interface UpdateUserPayload {
  fullName?: string | undefined;
  jobTitle?: string | undefined;
  phone?: string | undefined;
  avatarUrl?: string | undefined;
  timezone?: string | undefined;
  locale?: string | undefined;
}

export interface InviteUserPayload {
  email: string;
  fullName: string;
  username: string;
  jobTitle?: string | undefined;
  phone?: string | undefined;
  roleCodes?: string[] | undefined;
}

export interface RolePayload {
  code?: string | undefined;
  name: string;
  description?: string | undefined;
  permissions?: string[] | undefined;
}
