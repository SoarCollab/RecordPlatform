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
import type { AuthScope } from "$api/types/auth";

const base = "*/record-platform/api/v1";
const expiry = "2099-01-01T00:00:00Z";
const observed: Array<{
  path: string;
  tenant: string | null;
  authorization: string | null;
}> = [];

/** Build the real login response for one of the two identity scopes. */
function authorization(scope: AuthScope, token = `${scope}-token`) {
  return {
    token,
    expire: expiry,
    username: `${scope}-operator`,
    scope,
    role: scope === "platform" ? "platform_admin" : "admin",
  };
}

/** Control request completion explicitly without time-based race assertions. */
function deferred() {
  let resolve: () => void = () => {};
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

const server = setupServer(
  http.post(`${base}/auth/login`, ({ request }) => {
    observed.push({
      path: "/auth/login",
      tenant: request.headers.get("X-Tenant-ID"),
      authorization: request.headers.get("Authorization"),
    });
    return HttpResponse.json({
      code: 200,
      data: authorization(
        request.headers.get("X-Tenant-ID") === "0" ? "platform" : "tenant",
      ),
    });
  }),
  http.post(`${base}/auth/tokens/refresh`, ({ request }) => {
    observed.push({
      path: "/auth/tokens/refresh",
      tenant: request.headers.get("X-Tenant-ID"),
      authorization: request.headers.get("Authorization"),
    });
    return HttpResponse.json({
      code: 200,
      data: { token: "refreshed-token", expire: expiry },
    });
  }),
  http.post(`${base}/auth/logout`, ({ request }) => {
    observed.push({
      path: "/auth/logout",
      tenant: request.headers.get("X-Tenant-ID"),
      authorization: request.headers.get("Authorization"),
    });
    return HttpResponse.json({ code: 200, data: null });
  }),
);

// Interception precedes the real singleton client's import, including its fetch capture.
vi.stubGlobal("AbortController", transferableAbortController().constructor);
server.listen({ onUnhandledRequest: "error" });
const [client, auth, helpers] = await Promise.all([
  import("$api/client"),
  import("./auth"),
  import("$utils/authSession"),
]);

describe("scope-bound authentication transport", () => {
  beforeEach(() => {
    observed.length = 0;
    client.clearToken();
  });
  afterEach(() => server.resetHandlers());
  afterAll(() => {
    server.close();
    vi.unstubAllGlobals();
  });

  it.each([true, false])(
    "logs platform identities in with fixed zero and rememberMe=%s",
    async (rememberMe) => {
      await auth.login(
        { username: "platform-operator", password: "fixture" },
        rememberMe,
        "platform",
      );
      expect(observed).toEqual([
        { path: "/auth/login", tenant: "0", authorization: null },
      ]);
      expect(client.getToken()).toBe("platform-token");
      expect(client.wasRememberMeSelected()).toBe(rememberMe);
      expect(helpers.getStoredScopeHint()).toBe("platform");
      expect(helpers.landingForScope(helpers.getStoredScopeHint())).toBe(
        "/platform",
      );
      expect(
        (rememberMe ? localStorage : sessionStorage).getItem(client.SCOPE_KEY),
      ).toBe("platform");
    },
  );

  it("retains the ordinary nonzero tenant login context", async () => {
    await auth.login({ username: "tenant-operator", password: "fixture" });
    expect(observed[0]).toEqual({
      path: "/auth/login",
      tenant: "1",
      authorization: null,
    });
    expect(helpers.getStoredScopeHint()).toBe("tenant");
    expect(helpers.landingForScope("tenant")).toBe("/dashboard");
    expect(helpers.landingForScope(null)).toBe("/dashboard");
  });

  it.each(["platform", "tenant"] as const)(
    "refreshes and logs out through the %s context",
    async (scope) => {
      client.setToken("existing", expiry, false, scope);
      await auth.refreshToken();
      expect(client.getToken()).toBe("refreshed-token");
      expect(helpers.getStoredScopeHint()).toBe(scope);
      expect(client.wasRememberMeSelected()).toBe(false);
      await auth.logout();
      const tenant = scope === "platform" ? "0" : "1";
      expect(observed).toEqual([
        {
          path: "/auth/tokens/refresh",
          tenant,
          authorization: "Bearer existing",
        },
        {
          path: "/auth/logout",
          tenant,
          authorization: "Bearer refreshed-token",
        },
      ]);
      expect(client.getToken()).toBeNull();
      expect(helpers.getStoredScopeHint()).toBeNull();
    },
  );

  it("does not overwrite a later login with a delayed earlier login", async () => {
    const started = deferred();
    const release = deferred();
    server.use(
      http.post(`${base}/auth/login`, async ({ request }) => {
        const data = (await request.json()) as { username: string };
        if (data.username === "slow") {
          started.resolve();
          await release.promise;
        }
        return HttpResponse.json({
          code: 200,
          data: authorization("tenant", data.username),
        });
      }),
    );
    const first = auth
      .login({ username: "slow", password: "fixture" })
      .catch((error: unknown) => error);
    await started.promise;
    await auth.login({ username: "new", password: "fixture" }, false);
    release.resolve();
    expect(await first).toMatchObject({ name: "AbortError" });
    expect(client.getToken()).toBe("new");
    expect(client.wasRememberMeSelected()).toBe(false);
  });

  it("does not restore credentials when logout wins an in-flight login", async () => {
    const started = deferred();
    const release = deferred();
    server.use(
      http.post(`${base}/auth/login`, async () => {
        started.resolve();
        await release.promise;
        return HttpResponse.json({ code: 200, data: authorization("tenant") });
      }),
    );
    const pending = auth
      .login({ username: "slow", password: "fixture" })
      .catch((error: unknown) => error);
    await started.promise;
    await auth.logout();
    release.resolve();
    expect(await pending).toMatchObject({ name: "AbortError" });
    expect(client.getToken()).toBeNull();
  });

  it.each(["refresh", "logout"] as const)(
    "a delayed %s result cannot replace or clear a newer account",
    async (operation) => {
      const started = deferred();
      const release = deferred();
      const path =
        operation === "refresh" ? "/auth/tokens/refresh" : "/auth/logout";
      server.use(
        http.post(`${base}${path}`, async () => {
          started.resolve();
          await release.promise;
          return HttpResponse.json({
            code: 200,
            data: { token: "stale", expire: expiry },
          });
        }),
      );
      client.setToken("old", expiry, true, "platform");
      const pending = (
        operation === "refresh" ? auth.refreshToken() : auth.logout()
      ).catch((error: unknown) => error);
      await started.promise;
      client.setToken("new-account", expiry, false, "tenant");
      release.resolve();
      const outcome = await pending;
      if (operation === "refresh")
        expect(outcome).toMatchObject({ name: "AbortError" });
      expect(client.getToken()).toBe("new-account");
      expect(helpers.getStoredScopeHint()).toBe("tenant");
      expect(goto).not.toHaveBeenCalled();
    },
  );

  it("a delayed unauthorized response cannot clear the new account", async () => {
    const started = deferred();
    const release = deferred();
    server.use(
      http.get(`${base}/probe`, async () => {
        started.resolve();
        await release.promise;
        return HttpResponse.json(
          { code: 70006, message: "Invalid old session" },
          { status: 401 },
        );
      }),
    );
    client.setToken("old", expiry, true, "platform");
    const pending = client.api
      .get("/probe", { retries: 0 })
      .catch((error: unknown) => error);
    await started.promise;
    client.setToken("new", expiry, false, "tenant");
    release.resolve();
    expect(await pending).toMatchObject({ code: 70006 });
    expect(client.getToken()).toBe("new");
    expect(goto).not.toHaveBeenCalled();
  });

  it("anonymous failures do not invalidate a remembered protected session", async () => {
    let header: string | null = "unset";
    server.use(
      http.post(`${base}/public/probe`, ({ request }) => {
        header = request.headers.get("Authorization");
        return HttpResponse.json(
          { code: 70001, message: "Anonymous request failed" },
          { status: 401 },
        );
      }),
    );
    client.setToken("remembered", expiry, true, "platform");
    await expect(
      client.api.post(
        "/public/probe",
        {},
        { skipAuth: true, skipTenant: true, retries: 0 },
      ),
    ).rejects.toMatchObject({ code: 70001 });
    expect(header).toBeNull();
    expect(client.getToken()).toBe("remembered");
    expect(goto).not.toHaveBeenCalled();
  });

  it("never acquires a new identity during a transport retry", async () => {
    const headers: Array<string | null> = [];
    server.use(
      http.get(`${base}/retry-probe`, ({ request }) => {
        headers.push(request.headers.get("Authorization"));
        if (headers.length === 1) {
          client.setToken("new", expiry, false, "tenant");
          return HttpResponse.error();
        }
        return HttpResponse.json({ code: 200, data: "ok" });
      }),
    );
    client.setToken("old", expiry, false, "tenant");
    const transport = client.createApiClient({
      retryDelayBase: 1,
      maxRetries: 1,
    });
    await expect(transport.get("/retry-probe")).resolves.toBe("ok");
    expect(headers).toEqual(["Bearer old", "Bearer old"]);
    expect(client.getToken()).toBe("new");
  });

  it.each([
    { ...authorization("platform"), scope: "tenant" },
    { ...authorization("platform"), role: "admin" },
    { ...authorization("platform"), expire: "invalid" },
    { ...authorization("platform"), token: "" },
    { ...authorization("platform"), expire: "2001-01-01" },
  ])(
    "rejects malformed or mismatched platform login credentials",
    async (payload) => {
      server.use(
        http.post(`${base}/auth/login`, () =>
          HttpResponse.json({ code: 200, data: payload }),
        ),
      );
      await expect(
        auth.login(
          { username: "platform", password: "fixture" },
          false,
          "platform",
        ),
      ).rejects.toBeInstanceOf(client.ApiError);
      expect(client.getToken()).toBeNull();
      expect(helpers.getStoredScopeHint()).toBeNull();
    },
  );

  it("pure scope reading does not clear malformed/expired credentials or make requests", () => {
    localStorage.setItem(client.TOKEN_KEY, "old");
    localStorage.setItem(client.TOKEN_EXPIRE_KEY, "invalid");
    localStorage.setItem(client.SCOPE_KEY, "platform");
    expect(helpers.getStoredScopeHint()).toBeNull();
    expect(localStorage.getItem(client.TOKEN_KEY)).toBe("old");
    expect(observed).toEqual([]);
    expect(client.getToken()).toBeNull();
    expect(localStorage.getItem(client.TOKEN_KEY)).toBeNull();
  });

  it("generation changes invalidate snapshots and notify removable listeners", () => {
    client.setToken("same", expiry, true, "tenant");
    const snapshot = client.getCredentialSnapshot();
    const listener = vi.fn();
    const unsubscribe = client.subscribeCredentialChanges(listener);
    client.beginCredentialChange();
    expect(client.isCurrentCredential(snapshot)).toBe(false);
    expect(listener).toHaveBeenCalledOnce();
    unsubscribe();
    client.clearToken();
    expect(listener).toHaveBeenCalledOnce();
  });
});
