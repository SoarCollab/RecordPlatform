import type { PageParams } from "./common";
import type { components } from "./generated";

type OpenApiSchema<Name extends keyof components["schemas"]> =
  components["schemas"][Name];

export type TenantRole = "user" | "admin" | "monitor";

type GeneratedTenantMember = OpenApiSchema<"TenantMemberVO">;
type GeneratedTenantInvitation = OpenApiSchema<"TenantInvitationVO">;

export interface TenantMember extends GeneratedTenantMember {
  id: string;
  username: string;
  email: string;
  nickname?: string;
  role: TenantRole;
  status: 0 | 1;
  registerTime: string;
  lastLoginTime?: string;
}

export interface TenantInvitation extends GeneratedTenantInvitation {
  id: string;
  email: string;
  role: TenantRole;
  status: "PENDING" | "ACCEPTED" | "REVOKED" | "EXPIRED";
  expiresAt: string;
  createTime: string;
}

export interface TenantMemberQuery extends PageParams {
  keyword?: string;
  role?: TenantRole;
  status?: 0 | 1;
}

export interface CreateTenantInvitationRequest extends Omit<
  OpenApiSchema<"CreateTenantInvitationRequest">,
  "role"
> {
  email: string;
  role: TenantRole;
  expiresInHours: number;
  reason: string;
}

export type AcceptTenantInvitationRequest =
  OpenApiSchema<"AcceptTenantInvitationRequest">;

export type TenantMemberReasonRequest =
  OpenApiSchema<"TenantMemberReasonRequest">;
