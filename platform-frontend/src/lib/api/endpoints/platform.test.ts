import {
  afterAll,
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { transferableAbortController } from "node:util";
import { goto } from "$app/navigation";
import * as fixture from "$lib/../test/fixtures/platform";
import type { PlatformConfigurationKey } from "$api/types";

const base = "*/record-platform/api/v1/platform/*";
const key = fixture.PLATFORM_TEST_KEY;
const tenant = fixture.PLATFORM_TEST_IDS.tenant;
const user = fixture.PLATFORM_TEST_IDS.user;
const invitation = fixture.PLATFORM_TEST_IDS.invitation;
const seen: Array<{
  method: string;
  path: string;
  tenant: string | null;
  authorization: string | null;
  key: string | null;
  query: Record<string, string>;
  body: unknown;
}> = [];
let data: unknown;
let responseCode = 200;
const server = setupServer(
  http.all(base, async ({ request }) => {
    const url = new URL(request.url);
    seen.push({
      method: request.method,
      path: url.pathname.replace("/record-platform/api/v1", ""),
      tenant: request.headers.get("X-Tenant-ID"),
      authorization: request.headers.get("Authorization"),
      key: request.headers.get("Idempotency-Key"),
      query: Object.fromEntries(url.searchParams),
      body: request.method === "GET" ? undefined : await request.json(),
    });
    return HttpResponse.json(
      { code: responseCode, message: "fixture", data },
      { status: responseCode === 70002 ? 403 : 200 },
    );
  }),
);
vi.stubGlobal("AbortController", transferableAbortController().constructor);
server.listen({ onUnhandledRequest: "error" });
const [api, client] = await Promise.all([
  import("./platform"),
  import("$api/client"),
]);

beforeEach(() => {
  seen.length = 0;
  responseCode = 200;
  client.setToken("platform-fixture-token", "2099-01-01", false, "platform");
});
afterEach(() => server.resetHandlers());
afterAll(() => {
  server.close();
  vi.unstubAllGlobals();
});

/** Assert response rejection at the actual HTTP endpoint boundary. */
async function rejectsResponse(
  call: () => Promise<unknown>,
  payload: unknown,
): Promise<void> {
  data = payload;
  await expect(call()).rejects.toMatchObject({ code: 90003 });
}

const reads: Array<[string, () => Promise<unknown>, () => unknown]> = [
  [
    "/platform/session",
    () => api.getPlatformSession(),
    fixture.platformSessionFixture,
  ],
  [
    "/platform/overview",
    () => api.getPlatformOverview(),
    fixture.platformOverviewFixture,
  ],
  [
    "/platform/resources/health",
    () => api.getPlatformResourceHealth(),
    fixture.platformHealthFixture,
  ],
  [
    "/platform/tenants",
    () => api.listPlatformTenants(),
    () => fixture.platformPageFixture([fixture.platformTenantFixture()]),
  ],
  [
    `/platform/tenants/${tenant}`,
    () => api.getPlatformTenant(tenant),
    fixture.platformTenantFixture,
  ],
  [
    "/platform/users",
    () => api.listPlatformUsers(),
    () => fixture.platformPageFixture([fixture.platformUserFixture()]),
  ],
  [
    `/platform/tenants/${tenant}/users`,
    () => api.listPlatformTenantMembers(tenant),
    () => fixture.platformPageFixture([fixture.platformMemberFixture()]),
  ],
  [
    `/platform/tenants/${tenant}/invitations`,
    () => api.listPlatformTenantInvitations(tenant),
    () => [fixture.platformInvitationFixture()],
  ],
  [
    `/platform/tenants/${tenant}/usage`,
    () => api.getPlatformTenantUsage(tenant),
    fixture.platformUsageFixture,
  ],
  [
    `/platform/tenants/${tenant}/quota`,
    () => api.getPlatformTenantQuota(tenant),
    fixture.platformQuotaFixture,
  ],
  [
    "/platform/configuration",
    () => api.listPlatformConfiguration(),
    fixture.platformConfigurationsFixture,
  ],
  [
    "/platform/configuration/HIGH_FREQ_THRESHOLD",
    () => api.getPlatformConfiguration("HIGH_FREQ_THRESHOLD"),
    fixture.platformConfigurationFixture,
  ],
  [
    "/platform/audit",
    () => api.listPlatformAudit(),
    () => fixture.platformPageFixture([fixture.platformAuditFixture()]),
  ],
  [
    `/platform/audit/${fixture.PLATFORM_TEST_IDS.operation}`,
    () => api.getPlatformAudit(fixture.PLATFORM_TEST_IDS.operation),
    fixture.platformAuditFixture,
  ],
];
const reason = "Approved fixture change";
const writes: Array<[string, string, () => Promise<unknown>, unknown]> = [
  [
    "POST",
    "/platform/tenants",
    () =>
      api.createPlatformTenant(
        { code: "new-tenant", name: "New tenant", reason },
        key,
      ),
    { code: "new-tenant", name: "New tenant", reason },
  ],
  [
    "PUT",
    `/platform/tenants/${tenant}`,
    () =>
      api.updatePlatformTenant(
        tenant,
        { name: "New name", expectedVersion: 0, reason },
        key,
      ),
    { name: "New name", expectedVersion: 0, reason },
  ],
  [
    "PUT",
    `/platform/tenants/${tenant}/status`,
    () =>
      api.changePlatformTenantStatus(
        tenant,
        { status: 0, expectedVersion: 0, reason },
        key,
      ),
    { status: 0, expectedVersion: 0, reason },
  ],
  [
    "PUT",
    `/platform/tenants/${tenant}/users/${user}/role`,
    () =>
      api.changePlatformMemberRole(
        tenant,
        user,
        { role: "monitor", reason },
        key,
      ),
    { role: "monitor", reason },
  ],
  [
    "PUT",
    `/platform/tenants/${tenant}/users/${user}/status`,
    () =>
      api.changePlatformMemberStatus(tenant, user, { status: 0, reason }, key),
    { status: 0, reason },
  ],
  [
    "POST",
    `/platform/tenants/${tenant}/users/${user}/sessions/revoke`,
    () => api.revokePlatformMemberSessions(tenant, user, { reason }, key),
    { reason },
  ],
  [
    "POST",
    `/platform/tenants/${tenant}/invitations`,
    () =>
      api.invitePlatformTenantMember(
        tenant,
        {
          email: "member@example.invalid",
          role: "admin",
          expiresInHours: 72,
          reason,
        },
        key,
      ),
    {
      email: "member@example.invalid",
      role: "admin",
      expiresInHours: 72,
      reason,
    },
  ],
  [
    "DELETE",
    `/platform/tenants/${tenant}/invitations/${invitation}`,
    () =>
      api.revokePlatformTenantInvitation(tenant, invitation, { reason }, key),
    { reason },
  ],
  [
    "PUT",
    `/platform/tenants/${tenant}/quota`,
    () =>
      api.updatePlatformTenantQuota(
        tenant,
        { maxStorageBytes: 1024, maxFileCount: 10, expectedVersion: 0, reason },
        key,
      ),
    { maxStorageBytes: 1024, maxFileCount: 10, expectedVersion: 0, reason },
  ],
  [
    "PUT",
    "/platform/configuration/HIGH_FREQ_THRESHOLD",
    () =>
      api.updatePlatformConfiguration(
        "HIGH_FREQ_THRESHOLD",
        { value: 50, expectedVersion: 0, reason },
        key,
      ),
    { value: 50, expectedVersion: 0, reason },
  ],
];

describe("all 24 platform HTTP operations", () => {
  it.each(reads)(
    "GET %s uses system identity and decoded wire data",
    async (path, call, response) => {
      data = response();
      await expect(call()).resolves.toBeDefined();
      expect(seen).toEqual([
        {
          method: "GET",
          path,
          tenant: "0",
          authorization: "Bearer platform-fixture-token",
          key: null,
          query: {},
          body: undefined,
        },
      ]);
    },
  );
  it.each(writes)(
    "%s %s retains its UUID and exact reason body",
    async (method, path, call, body) => {
      data = fixture.platformMutationFixture({ version: null });
      await expect(call()).resolves.toMatchObject({ version: null });
      expect(seen).toEqual([
        {
          method,
          path,
          tenant: "0",
          authorization: "Bearer platform-fixture-token",
          key,
          query: {},
          body,
        },
      ]);
    },
  );
  it("keeps target filtering in query parameters instead of identity headers", async () => {
    data = fixture.platformPageFixture([]);
    await api.listPlatformUsers({
      tenantId: tenant,
      keyword: "Alice",
      role: "admin",
      status: 0,
      pageNum: 2,
      pageSize: 10,
    });
    expect(seen[0]).toMatchObject({
      tenant: "0",
      query: {
        tenantId: tenant,
        keyword: "Alice",
        role: "admin",
        status: "0",
        pageNum: "2",
        pageSize: "10",
      },
    });
  });
  it("encodes an opaque target resource exactly once", async () => {
    data = fixture.platformTenantFixture({ id: "tenant:opaque" });
    await api.getPlatformTenant("tenant:opaque");
    expect(seen[0]?.path).toBe("/platform/tenants/tenant%3Aopaque");
  });
  it("keeps permission denial separate from session invalidation", async () => {
    responseCode = 70002;
    data = null;
    await expect(api.getPlatformOverview()).rejects.toMatchObject({
      code: 70002,
    });
    expect(client.getToken()).toBe("platform-fixture-token");
    expect(goto).not.toHaveBeenCalled();
  });
  it("does not retry an uncertain write automatically", async () => {
    const requests = vi.fn();
    server.use(
      http.post("*/record-platform/api/v1/platform/tenants", () => {
        requests();
        return HttpResponse.error();
      }),
    );
    await expect(
      api.createPlatformTenant(
        { code: "fixture", name: "Fixture", reason },
        key,
      ),
    ).rejects.toMatchObject({ code: 90001 });
    expect(requests).toHaveBeenCalledOnce();
  });
  it.each(["", "not-uuid", key.toUpperCase()])(
    "rejects invalid operation key %s before transport",
    async (invalid) => {
      await expect(
        api.createPlatformTenant(
          { code: "fixture", name: "Fixture", reason },
          invalid,
        ),
      ).rejects.toMatchObject({ code: 10001 });
      expect(seen).toHaveLength(0);
    },
  );
  it.each(["", ".", "..", "contains space", "x\u0001"])(
    "rejects invalid resource %s before transport",
    async (id) => {
      await expect(api.getPlatformTenant(id)).rejects.toMatchObject({
        code: 10001,
      });
      expect(seen).toHaveLength(0);
    },
  );
  it.each([
    { pageNum: 0 },
    { pageNum: 1.5 },
    { pageSize: 101 },
    { keyword: "x".repeat(101) },
    { role: "platform_admin" },
    { status: "DELETED" },
    { tenantId: 1 },
    { skipTenant: true },
  ])("rejects invalid query %j", async (params) => {
    // Invalid input deliberately crosses the erased TypeScript boundary for runtime validation.
    await expect(api.listPlatformUsers(params as never)).rejects.toMatchObject({
      code: 10001,
    });
    expect(seen).toHaveLength(0);
  });
  it("rejects an unregistered global configuration key", async () => {
    await expect(
      api.getPlatformConfiguration(
        "DATABASE_PASSWORD" as PlatformConfigurationKey,
      ),
    ).rejects.toMatchObject({ code: 50026 });
    expect(seen).toHaveLength(0);
  });
});

describe("platform response contracts", () => {
  it.each(["0", null, -1, 0.5, Number.MAX_SAFE_INTEGER + 1, undefined])(
    "rejects invalid business number %s",
    async (tenants) => {
      await rejectsResponse(api.getPlatformOverview, {
        ...fixture.platformOverviewFixture(),
        tenants,
      });
    },
  );
  it("retains measured zero and explicitly nullable resource versions", async () => {
    data = fixture.platformQuotaFixture({
      version: 0,
      usedStorageBytes: 0,
      usedFileCount: 0,
    });
    await expect(api.getPlatformTenantQuota(tenant)).resolves.toMatchObject({
      version: 0,
      usedStorageBytes: 0,
    });
    data = fixture.platformConfigurationFixture({
      state: "UNAVAILABLE",
      value: null,
      version: null,
    });
    await expect(
      api.getPlatformConfiguration("HIGH_FREQ_THRESHOLD"),
    ).resolves.toMatchObject({ value: null, version: null });
  });
  it("derives page count from real metadata when the server omits pages", async () => {
    data = {
      records: [fixture.platformTenantFixture()],
      current: 1,
      size: 20,
      total: 21,
    };
    await expect(api.listPlatformTenants()).resolves.toMatchObject({
      pages: 2,
    });
  });
  it.each([
    { records: null },
    { current: 0 },
    { size: 0 },
    { size: 101 },
    { total: -1 },
    { total: "1" },
    { pages: 9 },
    { records: [fixture.platformTenantFixture()], total: 0 },
  ])("rejects malformed pages %j", async (changes) => {
    await rejectsResponse(api.listPlatformTenants, {
      ...fixture.platformPageFixture([fixture.platformTenantFixture()]),
      ...changes,
    });
  });
  it.each([
    "disabledReason",
    "disabledAt",
    "disabledBy",
    "createTime",
    "updateTime",
  ])("requires explicit nullable tenant field %s", async (field) => {
    const payload: Record<string, unknown> = {
      ...fixture.platformTenantFixture(),
    };
    delete payload[field];
    await rejectsResponse(() => api.getPlatformTenant(tenant), payload);
  });
  it.each(["nickname", "lastLoginTime", "registerTime"])(
    "requires explicit nullable global user field %s",
    async (field) => {
      const payload: Record<string, unknown> = {
        ...fixture.platformUserFixture(),
      };
      delete payload[field];
      await rejectsResponse(
        api.listPlatformUsers,
        fixture.platformPageFixture([payload]),
      );
    },
  );
  it("accepts legacy target-member profile nulls and omitted optional fields without losing email", async () => {
    data = fixture.platformPageFixture([fixture.platformMemberFixture()]);
    await expect(api.listPlatformTenantMembers(tenant)).resolves.toMatchObject({
      records: [
        { email: "member@example.test", nickname: null, lastLoginTime: null },
      ],
    });
    const member: Record<string, unknown> = {
      ...fixture.platformMemberFixture(),
    };
    delete member.nickname;
    delete member.lastLoginTime;
    data = fixture.platformPageFixture([member]);
    await expect(api.listPlatformTenantMembers(tenant)).resolves.toMatchObject({
      records: [{ username: "member" }],
    });
  });
  it("projects out secrets and refuses platform roles in ordinary member metadata", async () => {
    data = fixture.platformPageFixture([
      {
        ...fixture.platformUserFixture(),
        email: "private@example.invalid",
        password: "secret",
      },
    ]);
    const response = await api.listPlatformUsers();
    expect(response.records[0]).not.toHaveProperty("email");
    expect(response.records[0]).not.toHaveProperty("password");
    await rejectsResponse(
      api.listPlatformUsers,
      fixture.platformPageFixture([
        { ...fixture.platformUserFixture(), role: "platform_admin" },
      ]),
    );
  });
  it.each([
    { systemTenantId: "0" },
    { scope: "tenant" },
    { capabilities: ["platform:unknown:read"] },
    { capabilities: ["platform:tenant:read", "platform:tenant:read"] },
    { actorId: "" },
  ])("rejects malformed platform sessions %j", async (changes) => {
    await rejectsResponse(api.getPlatformSession, {
      ...fixture.platformSessionFixture(),
      ...changes,
    });
  });
  it("keeps unknown and degraded health distinct and drops provider internals", async () => {
    data = {
      ...fixture.platformHealthFixture({
        status: "DEGRADED",
        components: {
          database: "UP",
          redis: "UNKNOWN",
          storage: "DEGRADED",
          blockchain: "DOWN",
        },
      }),
      password: "secret",
    };
    const health = await api.getPlatformResourceHealth();
    expect(health.components.redis).toBe("UNKNOWN");
    expect(health).not.toHaveProperty("password");
    await rejectsResponse(api.getPlatformResourceHealth, {
      status: "UP",
      components: {},
    });
  });
  it.each([
    { value: undefined },
    { version: undefined },
    { value: "100" },
    { minimum: -1 },
    { maximum: 0 },
    { mutable: "true" },
    { state: "AVAILABLE", value: null },
    { state: "UNAVAILABLE", value: 100 },
  ])("rejects malformed configuration %j", async (changes) => {
    await rejectsResponse(
      () => api.getPlatformConfiguration("HIGH_FREQ_THRESHOLD"),
      { ...fixture.platformConfigurationFixture(), ...changes },
    );
  });
  it("rejects wrong measurement scopes and invalid invitation timestamps", async () => {
    await rejectsResponse(() => api.getPlatformTenantUsage(tenant), {
      ...fixture.platformUsageFixture(),
      attestationScope: "GLOBAL_CHAIN",
    });
    await rejectsResponse(
      () => api.listPlatformTenantInvitations(tenant),
      [
        {
          ...fixture.platformInvitationFixture(),
          expiresAt: "2026-02-30T09:00:00",
        },
      ],
    );
    await rejectsResponse(
      () => api.listPlatformTenantInvitations(tenant),
      [
        {
          ...fixture.platformInvitationFixture(),
          expiresAt: "2026-09-07T25:00:00",
        },
      ],
    );
  });
  it("preserves all nullable processing evidence and rejects invented terminal results", async () => {
    data = fixture.platformProcessingAuditFixture();
    await expect(
      api.getPlatformAudit(fixture.PLATFORM_TEST_IDS.operation),
    ).resolves.toMatchObject({
      status: "PROCESSING",
      result: null,
      durationMs: null,
    });
    await rejectsResponse(
      () => api.getPlatformAudit(fixture.PLATFORM_TEST_IDS.operation),
      {
        ...fixture.platformProcessingAuditFixture(),
        result: fixture.platformMutationFixture(),
      },
    );
  });
  it.each([
    { result: null },
    { completedAt: null },
    { durationMs: -1 },
    { traceId: "raw-provider-secret" },
    { resourceType: "FILE" },
    { operation: "IMPERSONATE" },
  ])("rejects invalid terminal audit %j", async (changes) => {
    await rejectsResponse(
      () => api.getPlatformAudit(fixture.PLATFORM_TEST_IDS.operation),
      { ...fixture.platformAuditFixture(), ...changes },
    );
  });
  it("accepts a terminal failure with bounded error evidence and no result", async () => {
    data = fixture.platformAuditFixture({
      status: "FAILURE",
      result: null,
      errorCode: 50022,
    });
    await expect(
      api.getPlatformAudit(fixture.PLATFORM_TEST_IDS.operation),
    ).resolves.toMatchObject({
      status: "FAILURE",
      result: null,
      errorCode: 50022,
    });
  });
});
