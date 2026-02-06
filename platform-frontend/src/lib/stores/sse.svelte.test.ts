import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SSEConnectionOptions, SSEMessage } from "$api/endpoints/sse";

const mocks = vi.hoisted(() => {
  const leaderState = {
    isLeader: true,
    isFollower: false,
    init: vi.fn(),
    cleanup: vi.fn(),
    broadcastSSEMessage: vi.fn(),
    broadcastSSEStatus: vi.fn(),
  };

  return {
    getToken: vi.fn(),
    createSSEConnection: vi.fn(),
    closeSSEConnection: vi.fn(),
    leaderState,
  };
});

vi.mock("$api/client", () => ({
  getToken: mocks.getToken,
}));

vi.mock("$api/endpoints/sse", () => ({
  createSSEConnection: mocks.createSSEConnection,
  closeSSEConnection: mocks.closeSSEConnection,
}));

vi.mock("./sse-leader.svelte", () => ({
  useSSELeader: () => mocks.leaderState,
}));

/**
 * 重新导入 SSE store，隔离模块级状态并返回实例。
 */
async function loadSseStore() {
  vi.resetModules();
  const mod = await import("./sse.svelte");
  return mod.useSSE();
}

/**
 * 获取最近一次建立 SSE 连接时传入的回调参数。
 *
 * @returns SSE 连接选项。
 */
function getLatestConnectionOptions(): SSEConnectionOptions {
  const call = mocks.createSSEConnection.mock.calls.at(-1);
  return call?.[0] as SSEConnectionOptions;
}

describe("sse store", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getToken.mockReturnValue("jwt");
    mocks.leaderState.isLeader = true;
    mocks.leaderState.isFollower = false;
    mocks.createSSEConnection.mockResolvedValue({ close: vi.fn() });
  });

  it("connect: 非 leader 时应跳过连接", async () => {
    mocks.leaderState.isLeader = false;

    const sse = await loadSseStore();
    sse.connect();

    expect(mocks.createSSEConnection).not.toHaveBeenCalled();
  });

  it("connect: 无 token 时应保持 disconnected", async () => {
    mocks.getToken.mockReturnValue(null);

    const sse = await loadSseStore();
    sse.connect();

    expect(sse.status).toBe("disconnected");
    expect(mocks.createSSEConnection).not.toHaveBeenCalled();
  });

  it("connect 成功后应处理 open/message/error/close 回调", async () => {
    const sse = await loadSseStore();
    const handler = vi.fn();
    const unsubscribe = sse.subscribe(handler);

    sse.connect();
    expect(sse.status).toBe("connecting");

    const options = getLatestConnectionOptions();
    const message: SSEMessage = {
      type: "notification",
      data: { title: "x" },
      timestamp: "t",
    };

    options.onOpen?.();
    expect(sse.status).toBe("connected");

    options.onMessage?.(message);
    expect(handler).toHaveBeenCalledWith(message);
    expect(mocks.leaderState.broadcastSSEMessage).toHaveBeenCalledWith(message);

    options.onError?.(new Event("error"));
    expect(sse.status).toBe("error");
    expect(sse.canManualReconnect).toBe(true);

    options.onClose?.();
    expect(sse.status).toBe("disconnected");

    unsubscribe();
  });

  it("manualReconnect 应清空手动重连标记并发起连接", async () => {
    const sse = await loadSseStore();

    sse.connect();
    const options = getLatestConnectionOptions();
    options.onError?.(new Event("error"));

    expect(sse.canManualReconnect).toBe(true);
    sse.manualReconnect();
    expect(mocks.createSSEConnection).toHaveBeenCalledTimes(2);
  });

  it("onClose 达到最大重试次数后应进入 error 状态", async () => {
    vi.useFakeTimers();

    try {
      const sse = await loadSseStore();
      sse.connect();

      for (let i = 0; i < 6; i++) {
        const options = getLatestConnectionOptions();
        options.onClose?.();
        await vi.advanceTimersByTimeAsync(31000);
      }

      expect(sse.status).toBe("error");
      expect(sse.canManualReconnect).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it("init 应注册 leader 回调并在成为 leader 时连接", async () => {
    const sse = await loadSseStore();

    sse.init("user-1");
    expect(mocks.leaderState.init).toHaveBeenCalledTimes(1);

    const initArgs = mocks.leaderState.init.mock.calls[0];
    const callbacks = initArgs[1] as {
      onBecomeLeader: () => void;
      onBecomeFollower: () => void;
      onMessage: (message: SSEMessage) => void;
      onStatusChange: (status: string) => void;
    };

    callbacks.onBecomeLeader();
    expect(mocks.createSSEConnection).toHaveBeenCalledTimes(1);

    callbacks.onMessage({ type: "notification", data: {}, timestamp: "t" });
    expect(sse.lastMessage).toEqual({ type: "notification", data: {}, timestamp: "t" });

    callbacks.onStatusChange("error");
    expect(sse.status).toBe("error");
    expect(sse.canManualReconnect).toBe(true);

    await Promise.resolve();
    callbacks.onBecomeFollower();
    expect(mocks.closeSSEConnection).toHaveBeenCalled();
  });

  it("页面从 hidden 切回 visible 且状态异常时应触发重连", async () => {
    const sse = await loadSseStore();
    sse.connect();

    let options = getLatestConnectionOptions();
    options.onError?.(new Event("error"));

    (globalThis as unknown as { __setDocumentVisibility?: (state: DocumentVisibilityState) => void }).__setDocumentVisibility?.("hidden");
    (globalThis as unknown as { __setDocumentVisibility?: (state: DocumentVisibilityState) => void }).__setDocumentVisibility?.("visible");

    expect(mocks.createSSEConnection.mock.calls.length).toBeGreaterThan(1);

    options = getLatestConnectionOptions();
    options.onOpen?.();
    expect(["connecting", "connected", "error"]).toContain(sse.status);
  });

  it("cleanup 应断开连接并清理 leader 资源", async () => {
    const sse = await loadSseStore();

    sse.connect();
    sse.cleanup();

    expect(sse.status).toBe("disconnected");
    expect(mocks.leaderState.cleanup).toHaveBeenCalledTimes(1);
  });
});
