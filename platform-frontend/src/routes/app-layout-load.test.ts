import { describe, expect, it, vi } from "vitest";

/**
 * 按场景加载 layout 模块，覆盖 browser/getToken 分支。
 *
 * @param options mock 参数。
 * @returns 加载后的模块与 redirect mock。
 */
async function loadLayoutModule(options: {
  browser: boolean;
  token: string | null;
  scope?: "tenant" | "platform";
  failure?: Error;
}) {
  vi.resetModules();

  const redirect = vi.fn((status: number, location: string) => {
    throw { __redirect: true, status, location };
  });
  const initializeSession = vi.fn(async () => {
    if (options.failure) throw options.failure;
  });

  vi.doMock("@sveltejs/kit", () => ({ redirect }));
  vi.doMock("$app/environment", () => ({ browser: options.browser }));
  vi.doMock("$api/client", () => ({ getToken: () => options.token }));
  vi.doMock("$stores/auth.svelte", () => ({
    useAuth: () => ({ scope: options.scope ?? "tenant", initializeSession }),
  }));

  const mod = await import("./(app)/+layout");
  return { mod, redirect, initializeSession };
}

describe("(app) +layout load", () => {
  it("浏览器环境且无 token 时应抛出重定向", async () => {
    const { mod, redirect } = await loadLayoutModule({
      browser: true,
      token: null,
    });

    await expect(mod.load({} as never)).rejects.toEqual({
      __redirect: true,
      status: 302,
      location: "/login",
    });
    expect(redirect).toHaveBeenCalledWith(302, "/login");
  });

  it("浏览器环境有 token 时应正常返回空对象", async () => {
    const { mod } = await loadLayoutModule({ browser: true, token: "jwt" });

    await expect(mod.load({} as never)).resolves.toEqual({});
  });

  it("非浏览器环境应跳过 token 校验", async () => {
    const { mod } = await loadLayoutModule({ browser: false, token: null });

    await expect(mod.load({} as never)).resolves.toEqual({});
  });

  it("redirects a validated platform identity before rendering the tenant shell", async () => {
    const { mod, initializeSession } = await loadLayoutModule({
      browser: true,
      token: "platform",
      scope: "platform",
    });
    await expect(mod.load({} as never)).rejects.toMatchObject({
      status: 303,
      location: "/platform",
    });
    expect(initializeSession).toHaveBeenCalledOnce();
  });

  it("does not admit a credential whose authoritative initialization fails", async () => {
    const { mod } = await loadLayoutModule({
      browser: true,
      token: "invalid",
      failure: new Error("Session unavailable"),
    });
    await expect(mod.load({} as never)).rejects.toThrow("Session unavailable");
  });
});
