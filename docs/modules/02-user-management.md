# Module 2 — User & Role Management

User CRUD, role/permission administration and the invitation flow. Builds on Module 1's identity
schema and `IdentityApi` published contract; extends them without redefining them.

---

## 1. Scope

| Capability | Endpoint | Permission |
|---|---|---|
| List users (paginated, filtered, searched) | `GET /api/v1/users` | `identity:user:read` |
| Get user detail | `GET /api/v1/users/{id}` | `identity:user:read` |
| Create user | `POST /api/v1/users` | `identity:user:create` |
| Update profile | `PATCH /api/v1/users/{id}` | `identity:user:update` |
| Change status (ACTIVE/DISABLED/LOCKED/PENDING) | `PUT /api/v1/users/{id}/status` | `identity:user:update` |
| Assign roles (full-replace) | `PUT /api/v1/users/{id}/roles` | `identity:user:update` |
| Admin password reset | `PUT /api/v1/users/{id}/password` | `identity:user:update` |
| Delete user | `DELETE /api/v1/users/{id}` | `identity:user:delete` |
| List invitations | `GET /api/v1/users/invitations` | `identity:user:read` |
| Invite user | `POST /api/v1/users/invitations` | `identity:user:create` |
| Revoke invitation | `DELETE /api/v1/users/invitations/{id}` | `identity:user:update` |
| **Accept invitation** (public) | `POST /api/v1/users/invitations/accept` | — |
| List roles | `GET /api/v1/roles` | `identity:role:read` |
| Get role | `GET /api/v1/roles/{id}` | `identity:role:read` |
| Create custom role | `POST /api/v1/roles` | `identity:role:manage` |
| Update custom role | `PUT /api/v1/roles/{id}` | `identity:role:manage` |
| Delete custom role | `DELETE /api/v1/roles/{id}` | `identity:role:manage` |

---

## 2. Database

Migration `V1104__user_invitations.sql` adds a single table, `identity.user_invitations`. The
users/roles/permissions tables from `V1100`/`V1103` are reused unchanged.

Key decisions:
- **Token hash, not token.** Only the SHA-256 of the invitation token is stored, mirroring the
  refresh-token scheme — a database dump yields no working invitations.
- **At most one outstanding invitation per `(organization_id, email)`** via a partial unique index.
- **Role codes denormalised as JSONB** on the invitation row, because an invitation is a transient
  document consumed once; the grants materialise on `user_roles` at activation.
- **Lifecycle CHECK** — a row cannot be both accepted and revoked — enforced at the DB and
  re-asserted by the entity's `accept`/`revoke` methods.

## 3. Security invariants

1. **Tenant scoping.** Every endpoint derives `organizationId` from the JWT, never from the request
   body. A tenant cannot address another tenant's users; a cross-tenant lookup returns 404 (never
   403, to avoid a user-existence oracle).
2. **No self-modification of privilege.** An admin cannot change their own status, roles, or delete
   their own account (`CANNOT_MODIFY_SELF_ROLE`, `CANNOT_DELETE_SELF`). This is the classic
   lockout/escalation hole.
3. **Status change evicts sessions.** Disabling or locking a user revokes every refresh token
   immediately; a suspended user cannot keep working on a token minted before suspension.
4. **System roles are immutable.** `RolePolicy.isModifiable` returns false for `is_system` roles;
   the service throws `ROLE_IS_SYSTEM` before any write. Customers author *custom* roles.
5. **Role deletion guarded.** A role assigned to any user cannot be deleted (`RESOURCE_CONFLICT`),
   so authorisation never silently changes.
6. **Permission cache eviction.** Role/status changes clear the `userPermissions` L1 cache so the
   change is effective immediately rather than after the 5-minute TTL.
7. **Password policy enforced** on admin reset and invitation activation, identical to self-change.
8. **Every mutation audited** under `AuditCategory.DATA` (or `AUTHORIZATION` for roles), with the
   resource id, actor and client IP.

## 4. Invitation lifecycle

```
invite  ──► PENDING ──accept──► ACCEPTED (user materialised, can sign in)
              │
              ├──revoke──► REVOKED
              └──expire──► (reaper marks REVOKED) ──► never reactivatable
```

- Issued with a 7-day TTL (`aquagrid.security.jwt.invitation-ttl`, default 7d).
- A scheduled reaper (`@Scheduled`, default 04:00 daily) marks expired outstanding invitations
  revoked so they can no longer be presented.
- Activation re-checks username/email uniqueness at materialisation time — the issue-time check is
  a fast path, not a lock, because another admin may have created a colliding user meanwhile.

## 5. Files added

| Path | Role |
|---|---|
| `db/migration/identity/V1104__user_invitations.sql` | Schema |
| `domain/model/UserInvitation.java` | Entity + lifecycle invariants |
| `domain/policy/RolePolicy.java` | Pure role rules (testable without Spring) |
| `infrastructure/persistence/PermissionRepository.java` | Permission lookup by code |
| `infrastructure/persistence/UserInvitationRepository.java` | Invitation queries + reaper |
| `application/command/UserManagementCommands.java` | Validated input models |
| `application/service/UserManagementService.java` | User CRUD, invitations, activation |
| `application/service/RoleManagementService.java` | Role catalogue admin |
| `web/dto/UserManagementResponses.java` | Outbound DTOs |
| `web/controller/UserController.java` | Users + invitations REST |
| `web/controller/RoleController.java` | Roles REST |
| `test/.../RolePolicyTest.java` | Pure domain unit tests |

## 6. Extended (not rewritten)

- `UserRepository`, `RoleRepository` — added Module 2 query methods
- `AuditEventTypes` — added user/role admin event constants
- `ErrorCode` — added Module 2 error codes
- `JwtProperties` — added `invitationTtl` (default 7d)
- `application.yml` — added `invitation-ttl`

## 7. Out of scope (deferred)

OIDC/SAML federation, bulk import, user impersonation UI, and the email delivery of invitation
tokens (the notification centre, Module 20, delivers them — until then the token is returned to the
inviter, who conveys it securely).
