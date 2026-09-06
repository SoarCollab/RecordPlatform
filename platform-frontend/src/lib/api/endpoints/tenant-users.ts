import { api } from "../client";
import type {
  AcceptTenantInvitationRequest,
  CreateTenantInvitationRequest,
  Page,
  TenantInvitation,
  TenantMember,
  TenantMemberQuery,
  TenantMemberReasonRequest,
  TenantRole,
} from "../types";

const BASE = "/admin/users";

/** Lists members in the authenticated tenant. */
export function listTenantMembers(
  params: TenantMemberQuery,
): Promise<Page<TenantMember>> {
  return api.get<Page<TenantMember>>(BASE, { params });
}

/** Lists recent invitation metadata without token material. */
export function listTenantInvitations(): Promise<TenantInvitation[]> {
  return api.get<TenantInvitation[]>(`${BASE}/invitations`);
}

/** Sends a one-time tenant invitation. */
export function createTenantInvitation(
  request: CreateTenantInvitationRequest,
): Promise<TenantInvitation> {
  return api.post<TenantInvitation>(`${BASE}/invitations`, request);
}

/** Revokes a pending invitation with an audited reason. */
export function revokeTenantInvitation(
  id: string,
  request: TenantMemberReasonRequest,
): Promise<void> {
  return api.deleteWithBody<void>(`${BASE}/invitations/${id}`, request);
}

/** Changes a member role and invalidates existing sessions. */
export function changeTenantMemberRole(
  id: string,
  role: TenantRole,
  reason: string,
): Promise<void> {
  return api.put<void>(`${BASE}/${id}/role`, { role, reason });
}

/** Enables or disables a member and invalidates existing sessions. */
export function changeTenantMemberStatus(
  id: string,
  status: 0 | 1,
  reason: string,
): Promise<void> {
  return api.put<void>(`${BASE}/${id}/status`, { status, reason });
}

/** Revokes every current session for a member. */
export function revokeTenantMemberSessions(
  id: string,
  reason: string,
): Promise<void> {
  return api.post<void>(`${BASE}/${id}/sessions/revoke`, { reason });
}

/** Accepts an invitation without auth or caller-controlled tenant context. */
export function acceptTenantInvitation(
  request: AcceptTenantInvitationRequest,
): Promise<TenantMember> {
  return api.post<TenantMember>("/public/invitations/accept", request, {
    skipAuth: true,
    skipTenant: true,
    retries: 0,
  });
}
