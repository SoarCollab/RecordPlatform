import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, setToken } from "$api/client";
import type { PlatformCommand } from "./platformMutation";
const mocks = vi.hoisted(() => ({
  require: vi.fn(),
  createPlatformTenant: vi.fn(),
  updatePlatformTenant: vi.fn(),
  changePlatformTenantStatus: vi.fn(),
  changePlatformMemberRole: vi.fn(),
  changePlatformMemberStatus: vi.fn(),
  revokePlatformMemberSessions: vi.fn(),
  invitePlatformTenantMember: vi.fn(),
  revokePlatformTenantInvitation: vi.fn(),
  updatePlatformTenantQuota: vi.fn(),
  updatePlatformConfiguration: vi.fn(),
}));
vi.mock("$api/endpoints/platform", () => mocks);
vi.mock("$utils/platformAccess", () => ({
  requirePlatformCapability: mocks.require,
}));
import {
  createPlatformMutationAttempt,
  executePlatformMutation,
  classifyPlatformMutationFailure,
  platformErrorMessage,
} from "./platformMutation";

const key = "8914a4ab-c9c2-4e9f-8e48-8cffac10b17c";
const commands: Array<
  [PlatformCommand, keyof typeof mocks, string, unknown[]]
> = [
  [
    {
      operation: "TENANT_CREATE",
      payload: {
        code: "new-tenant",
        name: "New",
        maxStorageBytes: null,
        maxFileCount: 10,
      },
    },
    "createPlatformTenant",
    "platform:tenant:write",
    [
      {
        code: "new-tenant",
        name: "New",
        maxStorageBytes: null,
        maxFileCount: 10,
        reason: "Approved",
      },
      key,
    ],
  ],
  [
    {
      operation: "TENANT_UPDATE",
      tenantId: "tenant-a",
      payload: { name: "New", expectedVersion: 0 },
    },
    "updatePlatformTenant",
    "platform:tenant:write",
    ["tenant-a", { name: "New", expectedVersion: 0, reason: "Approved" }, key],
  ],
  [
    {
      operation: "TENANT_STATUS_CHANGE",
      tenantId: "tenant-a",
      payload: { status: 0, expectedVersion: 0 },
    },
    "changePlatformTenantStatus",
    "platform:tenant:write",
    ["tenant-a", { status: 0, expectedVersion: 0, reason: "Approved" }, key],
  ],
  [
    {
      operation: "USER_ROLE_CHANGE",
      tenantId: "tenant-a",
      userId: "user-a",
      payload: { role: "monitor" },
    },
    "changePlatformMemberRole",
    "platform:user:write",
    ["tenant-a", "user-a", { role: "monitor", reason: "Approved" }, key],
  ],
  [
    {
      operation: "USER_STATUS_CHANGE",
      tenantId: "tenant-a",
      userId: "user-a",
      payload: { status: 1 },
    },
    "changePlatformMemberStatus",
    "platform:user:write",
    ["tenant-a", "user-a", { status: 1, reason: "Approved" }, key],
  ],
  [
    {
      operation: "USER_SESSIONS_REVOKE",
      tenantId: "tenant-a",
      userId: "user-a",
      payload: {},
    },
    "revokePlatformMemberSessions",
    "platform:user:write",
    ["tenant-a", "user-a", { reason: "Approved" }, key],
  ],
  [
    {
      operation: "INVITATION_CREATE",
      tenantId: "tenant-a",
      payload: {
        email: "member@example.invalid",
        role: "admin",
        expiresInHours: 72,
      },
    },
    "invitePlatformTenantMember",
    "platform:user:write",
    [
      "tenant-a",
      {
        email: "member@example.invalid",
        role: "admin",
        expiresInHours: 72,
        reason: "Approved",
      },
      key,
    ],
  ],
  [
    {
      operation: "INVITATION_REVOKE",
      tenantId: "tenant-a",
      invitationId: "invite-a",
      payload: {},
    },
    "revokePlatformTenantInvitation",
    "platform:user:write",
    ["tenant-a", "invite-a", { reason: "Approved" }, key],
  ],
  [
    {
      operation: "QUOTA_UPDATE",
      tenantId: "tenant-a",
      payload: { maxStorageBytes: 1024, maxFileCount: 0, expectedVersion: 0 },
    },
    "updatePlatformTenantQuota",
    "platform:quota:write",
    [
      "tenant-a",
      {
        maxStorageBytes: 1024,
        maxFileCount: 0,
        expectedVersion: 0,
        reason: "Approved",
      },
      key,
    ],
  ],
  [
    {
      operation: "CONFIGURATION_UPDATE",
      key: "HIGH_FREQ_THRESHOLD",
      payload: { value: 100, expectedVersion: 0 },
    },
    "updatePlatformConfiguration",
    "platform:configuration:write",
    [
      "HIGH_FREQ_THRESHOLD",
      { value: 100, expectedVersion: 0, reason: "Approved" },
      key,
    ],
  ],
];

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(crypto, "randomUUID").mockReturnValue(key);
  for (const mock of Object.values(mocks))
    mock.mockResolvedValue({
      operationId: "op-a",
      resourceId: "target",
      version: null,
    });
});

describe("typed immutable platform commands", () => {
  it("does not dispatch under a credential that changed while capability validation was awaiting", async () => {
    setToken("reviewed-operator", "2099-01-01", true, "platform");
    const attempt = createPlatformMutationAttempt(
      {
        operation: "TENANT_UPDATE",
        tenantId: "tenant-a",
        payload: { name: "Reviewed", expectedVersion: 0 },
      },
      "Approved",
    );
    mocks.require.mockImplementationOnce(async () => {
      setToken("different-operator", "2099-01-01", true, "platform");
    });
    await expect(executePlatformMutation(attempt)).rejects.toMatchObject({
      code: 70001,
    });
    expect(mocks.updatePlatformTenant).not.toHaveBeenCalled();
  });
  it.each(commands)(
    "dispatches %j with its exact reason, UUID and capability",
    async (command, endpoint, permission, expected) => {
      const attempt = createPlatformMutationAttempt(command, "  Approved  ");
      await expect(executePlatformMutation(attempt)).resolves.toMatchObject({
        operationId: "op-a",
      });
      expect(mocks.require).toHaveBeenCalledExactlyOnceWith(permission);
      expect(mocks[endpoint]).toHaveBeenCalledExactlyOnceWith(...expected);
    },
  );
  it("freezes a copy of the reviewed payload and reuses one UUID on manual retry", async () => {
    const draft = {
      operation: "TENANT_UPDATE" as const,
      tenantId: "tenant-a",
      payload: { name: "Reviewed", expectedVersion: 0 },
    };
    const attempt = createPlatformMutationAttempt(draft, "Approved");
    draft.payload.name = "Changed later";
    draft.tenantId = "tenant-b";
    await executePlatformMutation(attempt);
    await executePlatformMutation(attempt);
    expect(mocks.updatePlatformTenant).toHaveBeenNthCalledWith(
      1,
      "tenant-a",
      { name: "Reviewed", expectedVersion: 0, reason: "Approved" },
      key,
    );
    expect(mocks.updatePlatformTenant.mock.calls[1]).toEqual(
      mocks.updatePlatformTenant.mock.calls[0],
    );
    expect(crypto.randomUUID).toHaveBeenCalledOnce();
    expect(Object.isFrozen(attempt.command.payload)).toBe(true);
  });
  it.each(["", "   ", "x".repeat(256)])(
    "rejects an absent or oversized reason",
    (reason) => {
      expect(() =>
        createPlatformMutationAttempt(commands[1]![0], reason),
      ).toThrow();
      expect(crypto.randomUUID).not.toHaveBeenCalled();
    },
  );
  it.each([
    {
      operation: "TENANT_CREATE",
      payload: { code: "Uppercase", name: "Name" },
    },
    { operation: "TENANT_CREATE", payload: { code: "valid", name: "" } },
    {
      operation: "QUOTA_UPDATE",
      tenantId: "tenant-a",
      payload: { maxStorageBytes: -1, maxFileCount: 1, expectedVersion: 0 },
    },
    {
      operation: "QUOTA_UPDATE",
      tenantId: "tenant-a",
      payload: {
        maxStorageBytes: Number.MAX_SAFE_INTEGER + 1,
        maxFileCount: 1,
        expectedVersion: 0,
      },
    },
    {
      operation: "CONFIGURATION_UPDATE",
      key: "HIGH_FREQ_THRESHOLD",
      payload: { value: 1, expectedVersion: null },
    },
    {
      operation: "CONFIGURATION_UPDATE",
      key: "ERROR_RATE_THRESHOLD",
      payload: { value: 101, expectedVersion: 0 },
    },
    {
      operation: "USER_ROLE_CHANGE",
      tenantId: "tenant-a",
      userId: "user-a",
      payload: { role: "platform_admin" },
    },
    {
      operation: "USER_STATUS_CHANGE",
      tenantId: "tenant-a",
      userId: "user-a",
      payload: { status: 2 },
    },
    {
      operation: "INVITATION_CREATE",
      tenantId: "tenant-a",
      payload: { email: "bad", role: "admin", expiresInHours: 1 },
    },
    {
      operation: "INVITATION_CREATE",
      tenantId: "tenant-a",
      payload: {
        email: "member@example.invalid",
        role: "admin",
        expiresInHours: 169,
      },
    },
  ])("rejects invalid command fields before review %j", (command) => {
    // Malformed wire-like fixtures deliberately bypass compile-time command narrowing.
    expect(() =>
      createPlatformMutationAttempt(command as PlatformCommand, "Approved"),
    ).toThrow();
    expect(crypto.randomUUID).not.toHaveBeenCalled();
  });
  it("stops a command when current capability validation fails", async () => {
    mocks.require.mockRejectedValue(new ApiError(70002, "denied"));
    await expect(
      executePlatformMutation(
        createPlatformMutationAttempt(commands[0]![0], "Approved"),
      ),
    ).rejects.toMatchObject({ code: 70002 });
    expect(mocks.createPlatformTenant).not.toHaveBeenCalled();
  });
  it.each([
    [50022, "version-conflict"],
    [50023, "idempotency-conflict"],
    [50024, "processing"],
    [70001, "unauthorized"],
    [70002, "denied"],
    [50021, "rejected"],
    [90001, "uncertain"],
    [90002, "uncertain"],
    [90003, "uncertain"],
  ])("classifies code %s without automatic retries", (code, expected) => {
    expect(
      classifyPlatformMutationFailure(new ApiError(Number(code), "untrusted")),
    ).toBe(expected);
  });
  it("never renders arbitrary provider messages or invalid trace identifiers", () => {
    expect(
      platformErrorMessage(
        new ApiError(90001, "DATABASE_PASSWORD=secret", {
          detail: "secret",
          traceId: "secret",
        }),
      ),
    ).not.toContain("secret");
    const trace = "0123456789abcdef";
    expect(
      platformErrorMessage(
        new ApiError(50021, "untrusted", { traceId: trace }),
      ),
    ).toContain(trace);
    expect(
      platformErrorMessage(new Error("private-provider-error")),
    ).not.toContain("private-provider-error");
  });
});
