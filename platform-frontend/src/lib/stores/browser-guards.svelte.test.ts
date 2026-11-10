import { describe, expect, it, vi } from "vitest";

// `vitest.setup.ts` mocks `$app/env` with `browser: true` for the whole suite.
// These stores guard their browser-only side effects, so this file flips the flag
// to cover the server-side branch of every guard.
vi.mock("$app/env", () => ({
  browser: false,
  dev: false,
  building: false,
  version: "test",
}));

describe("store guards outside the browser", () => {
  it("badge 拉取与自动刷新在非浏览器环境下直接返回", async () => {
    const { useBadges } = await import("./badges.svelte");
    const badges = useBadges();

    await expect(badges.fetch()).resolves.toBeUndefined();
    badges.startAutoRefresh();
    badges.stopAutoRefresh();

    expect(badges.lastFetched).toBeNull();
    expect(badges.isLoading).toBe(false);
  });

  it("SSE 初始化与清理在非浏览器环境下跳过事件监听", async () => {
    const { useSSE } = await import("./sse.svelte");
    const sse = useSSE();

    expect(() => sse.init("user-1")).not.toThrow();
    expect(() => sse.cleanup()).not.toThrow();
    expect(sse.isConnected).toBe(false);
  });

  it("SSE 主节点选举在非浏览器环境下不注册", async () => {
    const { useSSELeader } = await import("./sse-leader.svelte");
    const leader = useSSELeader();

    expect(() =>
      leader.init("user-1", {
        onBecomeLeader: vi.fn(),
        onBecomeFollower: vi.fn(),
        onMessage: vi.fn(),
        onStatusChange: vi.fn(),
      }),
    ).not.toThrow();
    expect(leader.isLeader).toBe(false);
  });

  it("上传副作用初始化在非浏览器环境下直接返回", async () => {
    const { useUpload } = await import("./upload.svelte");
    const upload = useUpload();

    expect(() => upload.addFiles([])).not.toThrow();
    expect(upload.tasks).toHaveLength(0);
  });
});
