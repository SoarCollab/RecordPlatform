import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

interface LeaderCallbacks {
  onBecomeLeader: ReturnType<typeof vi.fn>;
  onBecomeFollower: ReturnType<typeof vi.fn>;
  onMessage: ReturnType<typeof vi.fn>;
  onStatusChange: ReturnType<typeof vi.fn>;
}

/**
 * 创建 leader 回调 mock 集合，便于断言选主状态迁移。
 *
 * @returns 选主回调对象。
 */
function createCallbacks(): LeaderCallbacks {
  return {
    onBecomeLeader: vi.fn(),
    onBecomeFollower: vi.fn(),
    onMessage: vi.fn(),
    onStatusChange: vi.fn(),
  };
}

/**
 * 重新导入 SSE leader store，保证用例间状态隔离。
 *
 * @returns store 实例。
 */
async function loadLeaderStore() {
  vi.resetModules();
  const mod = await import("./sse-leader.svelte");
  return mod.useSSELeader();
}

/**
 * 向指定频道发送广播消息，模拟其他标签页行为。
 *
 * @param userId 用户 ID。
 * @param data 广播消息。
 */
function postChannelMessage(userId: string, data: unknown): void {
  const channel = new BroadcastChannel(`sse-leader-${userId}`);
  channel.postMessage(data);
  channel.close();
}

describe("sse-leader store", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("init 后在无竞争场景应成为 leader", async () => {
    const leader = await loadLeaderStore();
    const callbacks = createCallbacks();

    leader.init("u1", callbacks);
    await vi.advanceTimersByTimeAsync(220);

    expect(leader.role).toBe("leader");
    expect(leader.isLeader).toBe(true);
    expect(callbacks.onBecomeLeader).toHaveBeenCalledTimes(1);
    expect(callbacks.onBecomeFollower).not.toHaveBeenCalled();

    leader.cleanup();
    expect(leader.role).toBe("init");
  });

  it("electing 阶段收到 leader-confirm 应转为 follower", async () => {
    const leader = await loadLeaderStore();
    const callbacks = createCallbacks();

    leader.init("u2", callbacks);
    await vi.advanceTimersByTimeAsync(60);

    postChannelMessage("u2", { type: "leader-confirm", tabId: "other-tab" });
    await vi.advanceTimersByTimeAsync(1);

    expect(leader.role).toBe("follower");
    expect(leader.isFollower).toBe(true);
    expect(callbacks.onBecomeFollower).toHaveBeenCalledTimes(1);

    leader.cleanup();
  });

  it("follower 模式下应接收 leader 广播的消息与状态", async () => {
    const leader = await loadLeaderStore();
    const callbacks = createCallbacks();

    leader.init("u3", callbacks);
    await vi.advanceTimersByTimeAsync(60);
    postChannelMessage("u3", { type: "leader-confirm", tabId: "other-tab" });
    await vi.advanceTimersByTimeAsync(1);

    postChannelMessage("u3", { type: "sse-message", message: { type: "notification" } });
    postChannelMessage("u3", { type: "sse-status", status: "connected" });
    await vi.advanceTimersByTimeAsync(1);

    expect(callbacks.onMessage).toHaveBeenCalledWith({ type: "notification" });
    expect(callbacks.onStatusChange).toHaveBeenCalledWith("connected");

    leader.cleanup();
  });

  it("leader 超时后 follower 应重新发起选举并成为 leader", async () => {
    const leader = await loadLeaderStore();
    const callbacks = createCallbacks();

    leader.init("u4", callbacks);
    await vi.advanceTimersByTimeAsync(60);
    postChannelMessage("u4", { type: "leader-confirm", tabId: "other-tab" });
    await vi.advanceTimersByTimeAsync(1);
    expect(leader.role).toBe("follower");

    await vi.advanceTimersByTimeAsync(6000);
    await vi.advanceTimersByTimeAsync(300);

    expect(leader.role).toBe("leader");
    expect(callbacks.onBecomeLeader).toHaveBeenCalled();

    leader.cleanup();
  });

  it("leader 模式下 broadcastSSEMessage 与 broadcastSSEStatus 应对 follower 生效", async () => {
    const leader = await loadLeaderStore();
    const callbacks = createCallbacks();

    leader.init("u5", callbacks);
    await vi.advanceTimersByTimeAsync(220);
    expect(leader.isLeader).toBe(true);

    const followerChannel = new BroadcastChannel("sse-leader-u5");
    const received: Array<unknown> = [];
    followerChannel.onmessage = (event) => {
      received.push(event.data);
    };

    leader.broadcastSSEMessage({ type: "badge-update" });
    leader.broadcastSSEStatus("error");
    await vi.advanceTimersByTimeAsync(1);

    expect(received).toEqual(
      expect.arrayContaining([
        { type: "sse-message", message: { type: "badge-update" } },
        { type: "sse-status", status: "error" },
      ]),
    );

    followerChannel.close();
    leader.cleanup();
  });

  it("BroadcastChannel 不可用时应直接退化为 leader", async () => {
    const original = (globalThis as { BroadcastChannel?: unknown }).BroadcastChannel;

    try {
      delete (globalThis as { BroadcastChannel?: unknown }).BroadcastChannel;

      const leader = await loadLeaderStore();
      const callbacks = createCallbacks();
      leader.init("u6", callbacks);

      expect(leader.isLeader).toBe(true);
      expect(callbacks.onBecomeLeader).toHaveBeenCalledTimes(1);

      leader.cleanup();
    } finally {
      (globalThis as { BroadcastChannel?: unknown }).BroadcastChannel = original;
    }
  });
});

describe("sse-leader store extra branches", () => {
  it("重复 init 时应告警并跳过重复初始化", async () => {
    vi.useFakeTimers();

    try {
      const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
      const leader = await loadLeaderStore();
      const callbacks = createCallbacks();

      leader.init("u7", callbacks);
      leader.init("u7", callbacks);

      expect(warnSpy).toHaveBeenCalledWith(
        "SSE Leader: Already initialized, skipping",
      );

      leader.cleanup();
      warnSpy.mockRestore();
    } finally {
      vi.useRealTimers();
    }
  });

  it("leader 收到更高优先级 claim 时应让位为 follower", async () => {
    vi.useFakeTimers();

    try {
      const leader = await loadLeaderStore();
      const callbacks = createCallbacks();

      leader.init("u8", callbacks);
      await vi.advanceTimersByTimeAsync(220);
      expect(leader.role).toBe("leader");

      postChannelMessage("u8", {
        type: "leader-claim",
        tabId: "other-tab",
        timestamp: 0,
      });
      await vi.advanceTimersByTimeAsync(1);

      expect(leader.role).toBe("follower");
      expect(callbacks.onBecomeFollower).toHaveBeenCalled();

      leader.cleanup();
    } finally {
      vi.useRealTimers();
    }
  });

  it("leader 收到低优先级 claim 时应广播 leader-confirm", async () => {
    vi.useFakeTimers();

    try {
      const leader = await loadLeaderStore();
      const callbacks = createCallbacks();
      const receiver = new BroadcastChannel("sse-leader-u9");
      const received: Array<unknown> = [];
      receiver.onmessage = (event) => {
        received.push(event.data);
      };

      leader.init("u9", callbacks);
      await vi.advanceTimersByTimeAsync(220);

      postChannelMessage("u9", {
        type: "leader-claim",
        tabId: "zz-tab",
        timestamp: Date.now() + 10_000,
      });
      await vi.advanceTimersByTimeAsync(1);

      expect(received).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ type: "leader-confirm" }),
        ]),
      );

      receiver.close();
      leader.cleanup();
    } finally {
      vi.useRealTimers();
    }
  });

  it("收到 heartbeat 时若不是 follower 应切换为 follower", async () => {
    vi.useFakeTimers();

    try {
      const leader = await loadLeaderStore();
      const callbacks = createCallbacks();

      leader.init("u10", callbacks);
      await vi.advanceTimersByTimeAsync(60);
      expect(leader.role).toBe("electing");

      postChannelMessage("u10", {
        type: "leader-heartbeat",
        tabId: "leader-tab",
      });
      await vi.advanceTimersByTimeAsync(1);

      expect(leader.role).toBe("follower");
      expect(callbacks.onBecomeFollower).toHaveBeenCalled();

      leader.cleanup();
    } finally {
      vi.useRealTimers();
    }
  });

  it("follower 收到 leader-step-down 时应重新选举", async () => {
    vi.useFakeTimers();

    try {
      const leader = await loadLeaderStore();
      const callbacks = createCallbacks();

      leader.init("u11", callbacks);
      await vi.advanceTimersByTimeAsync(60);
      postChannelMessage("u11", { type: "leader-confirm", tabId: "leader-1" });
      await vi.advanceTimersByTimeAsync(1);
      expect(leader.role).toBe("follower");

      postChannelMessage("u11", { type: "leader-step-down", tabId: "leader-1" });
      await vi.advanceTimersByTimeAsync(200);

      expect(["electing", "leader"]).toContain(leader.role);

      leader.cleanup();
    } finally {
      vi.useRealTimers();
    }
  });
});
