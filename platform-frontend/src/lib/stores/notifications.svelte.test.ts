import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * 重新加载通知 store，避免模块级状态在用例间污染。
 *
 * @returns 通知 store 实例。
 */
async function loadNotificationsStore() {
  vi.resetModules();
  const mod = await import("./notifications.svelte");
  return mod.useNotifications();
}

describe("notifications store", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("add/success/warning/info/error 应正确入队通知", async () => {
    const notifications = await loadNotificationsStore();

    const id1 = notifications.add({ type: "info", title: "t1", message: "m1", duration: 0 });
    const id2 = notifications.success("ok", "done", 0);
    const id3 = notifications.warning("warn", "care", 0);
    const id4 = notifications.info("tip", "check", 0);
    const id5 = notifications.error("err", "boom", 0);

    expect([id1, id2, id3, id4, id5]).toHaveLength(5);
    expect(notifications.notifications).toHaveLength(5);
    expect(notifications.notifications.map((n) => n.type)).toEqual([
      "info",
      "success",
      "warning",
      "info",
      "error",
    ]);
  });

  it("dismiss 与 dismissAll 应清空对应通知", async () => {
    const notifications = await loadNotificationsStore();

    const id1 = notifications.success("a", "b", 0);
    notifications.warning("c", "d", 0);

    notifications.dismiss(id1);
    expect(notifications.notifications).toHaveLength(1);

    notifications.dismissAll();
    expect(notifications.notifications).toHaveLength(0);
  });

  it("默认 duration 应自动移除通知", async () => {
    vi.useFakeTimers();

    try {
      const notifications = await loadNotificationsStore();
      notifications.info("auto", "remove");
      expect(notifications.notifications).toHaveLength(1);

      await vi.advanceTimersByTimeAsync(5000);
      expect(notifications.notifications).toHaveLength(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it("error 的默认时长应为 8000ms", async () => {
    vi.useFakeTimers();

    try {
      const notifications = await loadNotificationsStore();
      notifications.error("err", "persist");

      await vi.advanceTimersByTimeAsync(7000);
      expect(notifications.notifications).toHaveLength(1);

      await vi.advanceTimersByTimeAsync(1000);
      expect(notifications.notifications).toHaveLength(0);
    } finally {
      vi.useRealTimers();
    }
  });
});
