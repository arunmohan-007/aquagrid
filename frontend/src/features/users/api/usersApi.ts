import { apiDelete, apiGet, apiPatch, apiPost, apiPut } from '@/lib/api/httpClient';
import type {
  CreateUserPayload,
  InvitationCreated,
  InvitationSummary,
  InviteUserPayload,
  MessageResponse,
  PageResponse,
  RoleDetail,
  RolePayload,
  RoleSummary,
  UpdateUserPayload,
  UserDetail,
  UserListQuery,
  UserStatus,
  UserSummary,
} from '../types';

/**
 * Module 2 endpoints. Mirrors the structure of {@link authApi}: every call goes through this
 * object so the path, request shape and response type live together, and a backend change is a
 * one-file edit with compiler-checked fallout.
 */
export const usersApi = {
  list: (query: UserListQuery = {}) =>
    apiGet<PageResponse<UserSummary>>('/users', { params: query }),

  get: (userId: string) => apiGet<UserDetail>(`/users/${userId}`),

  create: (payload: CreateUserPayload) => apiPost<UserDetail>('/users', payload),

  update: (userId: string, payload: UpdateUserPayload) =>
    apiPatch<UserDetail>(`/users/${userId}`, payload),

  changeStatus: (userId: string, status: UserStatus) =>
    apiPut<UserDetail>(`/users/${userId}/status`, { status }),

  assignRoles: (userId: string, roleCodes: string[]) =>
    apiPut<UserDetail>(`/users/${userId}/roles`, { roleCodes }),

  adminResetPassword: (userId: string, newPassword: string, mustChangePassword: boolean) =>
    apiPut<MessageResponse>(`/users/${userId}/password`, { newPassword, mustChangePassword }),

  delete: (userId: string) => apiDelete<void>(`/users/${userId}`),

  // --- Invitations ------------------------------------------------------------------------

  listInvitations: () => apiGet<InvitationSummary[]>('/users/invitations'),

  invite: (payload: InviteUserPayload) => apiPost<InvitationCreated>('/users/invitations', payload),

  revokeInvitation: (invitationId: string) =>
    apiDelete<void>(`/users/invitations/${invitationId}`),

  acceptInvitation: (token: string, password: string) =>
    apiPost<MessageResponse>('/users/invitations/accept', { token, password }),
};

export const rolesApi = {
  list: () => apiGet<RoleSummary[]>('/roles'),

  get: (roleId: string) => apiGet<RoleDetail>(`/roles/${roleId}`),

  create: (payload: RolePayload) => apiPost<RoleDetail>('/roles', payload),

  update: (roleId: string, payload: RolePayload) =>
    apiPut<RoleDetail>(`/roles/${roleId}`, payload),

  delete: (roleId: string) => apiDelete<void>(`/roles/${roleId}`),
};

/** Converts a ProblemDetail `code` into a user-facing sentence for the common Module 2 cases. */
export function userManagementError(code: string): string | null {
  switch (code) {
    case 'USERNAME_TAKEN':
      return 'That username is already in use in your organisation.';
    case 'EMAIL_TAKEN':
      return 'That email address is already registered.';
    case 'ROLE_IS_SYSTEM':
      return 'System roles cannot be changed. Create a custom role instead.';
    case 'ROLE_CODE_TAKEN':
      return 'That role code is already in use.';
    case 'ROLE_NOT_FOUND':
      return 'That role no longer exists. Refresh and try again.';
    case 'INVITATION_TOKEN_INVALID':
      return 'This invitation is invalid, expired or has been revoked.';
    case 'INVITATION_ALREADY_OUTSTANDING':
      return 'An outstanding invitation already exists for that email.';
    case 'CANNOT_MODIFY_SELF_ROLE':
      return 'You cannot change your own roles or status. Ask another administrator.';
    case 'CANNOT_DELETE_SELF':
      return 'You cannot delete your own account.';
    case 'RESOURCE_CONFLICT':
      return 'The operation conflicts with the current state of the resource.';
    default:
      return null;
  }
}
