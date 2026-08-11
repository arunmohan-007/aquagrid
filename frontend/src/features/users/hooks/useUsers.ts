import {
  useMutation,
  useQuery,
  useQueryClient,
  keepPreviousData,
} from '@tanstack/react-query';
import { rolesApi, usersApi } from '../api/usersApi';
import type {
  CreateUserPayload,
  InviteUserPayload,
  RolePayload,
  UpdateUserPayload,
  UserListQuery,
  UserStatus,
} from '../types';

/**
 * React Query cache keys for Module 2.
 *
 * Collected in one place so an invalidation after a mutation is unambiguous: `queryKeys.users.all`
 * clears every user list and detail; `queryKeys.users.detail(id)` clears one.
 */
export const queryKeys = {
  users: {
    all: ['users'] as const,
    lists: () => [...queryKeys.users.all, 'list'] as const,
    list: (query: UserListQuery) => [...queryKeys.users.lists(), query] as const,
    detail: (id: string) => [...queryKeys.users.all, 'detail', id] as const,
    invitations: () => [...queryKeys.users.all, 'invitations'] as const,
  },
  roles: {
    all: ['roles'] as const,
    detail: (id: string) => [...queryKeys.roles.all, 'detail', id] as const,
  },
} as const;

// --- User queries ---------------------------------------------------------------------------

export function useUserList(query: UserListQuery) {
  return useQuery({
    queryKey: queryKeys.users.list(query),
    queryFn: () => usersApi.list(query),
    // `keepPreviousData` keeps the table populated while the next page/filtered page loads, so the
    // UI does not flash empty between keystrokes in the search box or page changes.
    placeholderData: keepPreviousData,
  });
}

export function useUser(userId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.users.detail(userId ?? ''),
    queryFn: () => usersApi.get(userId!),
    enabled: Boolean(userId),
  });
}

// --- User mutations -------------------------------------------------------------------------

export function useCreateUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: CreateUserPayload) => usersApi.create(payload),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.users.lists() }),
  });
}

export function useUpdateUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, payload }: { userId: string; payload: UpdateUserPayload }) =>
      usersApi.update(userId, payload),
    onSuccess: (data, { userId }) => {
      qc.setQueryData(queryKeys.users.detail(userId), data);
      qc.invalidateQueries({ queryKey: queryKeys.users.lists() });
    },
  });
}

export function useChangeUserStatus() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, status }: { userId: string; status: UserStatus }) =>
      usersApi.changeStatus(userId, status),
    onSuccess: (data, { userId }) => {
      qc.setQueryData(queryKeys.users.detail(userId), data);
      qc.invalidateQueries({ queryKey: queryKeys.users.lists() });
    },
  });
}

export function useAssignRoles() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, roleCodes }: { userId: string; roleCodes: string[] }) =>
      usersApi.assignRoles(userId, roleCodes),
    onSuccess: (data, { userId }) => {
      qc.setQueryData(queryKeys.users.detail(userId), data);
      qc.invalidateQueries({ queryKey: queryKeys.users.lists() });
    },
  });
}

export function useAdminResetPassword() {
  return useMutation({
    mutationFn: ({
      userId,
      newPassword,
      mustChangePassword,
    }: {
      userId: string;
      newPassword: string;
      mustChangePassword: boolean;
    }) => usersApi.adminResetPassword(userId, newPassword, mustChangePassword),
  });
}

export function useDeleteUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) => usersApi.delete(userId),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.users.all }),
  });
}

// --- Invitations -----------------------------------------------------------------------------

export function useInvitations() {
  return useQuery({
    queryKey: queryKeys.users.invitations(),
    queryFn: () => usersApi.listInvitations(),
  });
}

export function useInviteUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: InviteUserPayload) => usersApi.invite(payload),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.users.invitations() }),
  });
}

export function useRevokeInvitation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (invitationId: string) => usersApi.revokeInvitation(invitationId),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.users.invitations() }),
  });
}

export function useAcceptInvitation() {
  return useMutation({
    mutationFn: ({ token, password }: { token: string; password: string }) =>
      usersApi.acceptInvitation(token, password),
  });
}

// --- Roles -----------------------------------------------------------------------------------

export function useRoles() {
  return useQuery({ queryKey: queryKeys.roles.all, queryFn: () => rolesApi.list() });
}

export function useRole(roleId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.roles.detail(roleId ?? ''),
    queryFn: () => rolesApi.get(roleId!),
    enabled: Boolean(roleId),
  });
}

export function useCreateRole() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: RolePayload) => rolesApi.create(payload),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.roles.all }),
  });
}

export function useUpdateRole() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ roleId, payload }: { roleId: string; payload: RolePayload }) =>
      rolesApi.update(roleId, payload),
    onSuccess: (data, { roleId }) => {
      qc.setQueryData(queryKeys.roles.detail(roleId), data);
      qc.invalidateQueries({ queryKey: queryKeys.roles.all });
    },
  });
}

export function useDeleteRole() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (roleId: string) => rolesApi.delete(roleId),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.roles.all }),
  });
}
