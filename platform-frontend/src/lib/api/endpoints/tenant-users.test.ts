import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    deleteWithBody: vi.fn(),
  },
}));

vi.mock("../client", () => ({ api: mocks.api }));

import * as tenantUsers from "./tenant-users";

describe("tenant user endpoints", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    for (const method of Object.values(mocks.api)) method.mockResolvedValue({});
  });

  it("uses tenant-derived routes and never sends a tenant selector", async () => {
    await tenantUsers.listTenantMembers({ pageNum: 1, role: "admin" });
    await tenantUsers.listTenantInvitations();
    await tenantUsers.createTenantInvitation({
      email: "user@example.com",
      role: "user",
      expiresInHours: 24,
      reason: "approved",
    });
    await tenantUsers.changeTenantMemberRole("U1", "monitor", "approved");
    await tenantUsers.changeTenantMemberStatus("U1", 0, "approved");
    await tenantUsers.revokeTenantMemberSessions("U1", "approved");
    await tenantUsers.revokeTenantInvitation("E1", { reason: "approved" });

    expect(mocks.api.get).toHaveBeenNthCalledWith(1, "/admin/users", {
      params: { pageNum: 1, role: "admin" },
    });
    expect(mocks.api.get).toHaveBeenNthCalledWith(
      2,
      "/admin/users/invitations",
    );
    expect(mocks.api.put).toHaveBeenCalledWith("/admin/users/U1/role", {
      role: "monitor",
      reason: "approved",
    });
    expect(mocks.api.deleteWithBody).toHaveBeenCalledWith(
      "/admin/users/invitations/E1",
      { reason: "approved" },
    );
    const allCalls = Object.values(mocks.api).flatMap(
      (method) => method.mock.calls,
    );
    expect(JSON.stringify(allCalls)).not.toContain("tenantId");
  });

  it("accepts invitations without auth or caller tenant headers", async () => {
    const request = {
      token: "opaque",
      username: "new-user",
      password: "password123",
    };
    await tenantUsers.acceptTenantInvitation(request);
    expect(mocks.api.post).toHaveBeenCalledWith(
      "/public/invitations/accept",
      request,
      { skipAuth: true, skipTenant: true, retries: 0 },
    );
  });
});
