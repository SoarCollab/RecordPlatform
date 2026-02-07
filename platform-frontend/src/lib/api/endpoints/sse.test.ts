import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => {
  return {
    getToken: vi.fn(),
    getSseToken: vi.fn(),
  };
});

vi.mock("../client", () => ({
  getToken: mocks.getToken,
}));

vi.mock("./auth", () => ({
  getSseToken: mocks.getSseToken,
}));

import { closeSSEConnection, createSSEConnection } from "./sse";

/**
 * 获取最新创建的 EventSource mock 实例。
 *
 * @returns 当前测试中的最后一个 EventSource 实例。
 */
function getLatestEventSource(): {
  emitOpen: () => void;
  emitMessage: (data: string) => void;
  emitNamedEvent: (type: string, data: string) => void;
  emitError: () => void;
  close: () => void;
  readyState: number;
  url: string;
} {
  const eventSourceClass = globalThis.EventSource as unknown as {
    instances: Array<unknown>;
  };
  return eventSourceClass.instances.at(-1) as {
    emitOpen: () => void;
    emitMessage: (data: string) => void;
    emitNamedEvent: (type: string, data: string) => void;
    emitError: () => void;
    close: () => void;
    readyState: number;
    url: string;
  };
}

describe("sse endpoints", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("无 token 时应直接返回 null", async () => {
    mocks.getToken.mockReturnValue(null);
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});

    const result = await createSSEConnection({});

    expect(result).toBeNull();
    expect(warnSpy).toHaveBeenCalledWith("SSE: No auth token available");
    expect(mocks.getSseToken).not.toHaveBeenCalled();

    warnSpy.mockRestore();
  });

  it("握手 token 获取失败时应触发 onError 并返回 null", async () => {
    mocks.getToken.mockReturnValue("jwt");
    mocks.getSseToken.mockRejectedValue(new Error("no sse token"));
    const onError = vi.fn();

    const result = await createSSEConnection({ onError });

    expect(result).toBeNull();
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError.mock.calls[0][0]).toBeInstanceOf(Event);
  });

  it("成功连接后应支持 open/message/命名事件与 error 关闭", async () => {
    mocks.getToken.mockReturnValue("jwt");
    mocks.getSseToken.mockResolvedValue({ sseToken: "short-token" });

    const onOpen = vi.fn();
    const onMessage = vi.fn();
    const onError = vi.fn();
    const onClose = vi.fn();
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});

    const eventSource = await createSSEConnection({
      connectionId: "conn-1",
      onOpen,
      onMessage,
      onError,
      onClose,
    });

    expect(eventSource).not.toBeNull();
    const es = getLatestEventSource();
    expect(es.url).toContain("/sse/connect?");
    expect(es.url).toContain("token=short-token");
    expect(es.url).toContain("connectionId=conn-1");

    es.emitOpen();
    expect(onOpen).toHaveBeenCalledTimes(1);

    es.emitMessage(JSON.stringify({ type: "notification", data: { a: 1 }, timestamp: "t" }));
    expect(onMessage).toHaveBeenCalledWith({
      type: "notification",
      data: { a: 1 },
      timestamp: "t",
    });

    es.emitMessage("not-json");
    expect(errorSpy).toHaveBeenCalled();

    es.emitNamedEvent(
      "message-received",
      JSON.stringify({ senderName: "alice", content: "hello" }),
    );
    expect(onMessage).toHaveBeenCalledWith(
      expect.objectContaining({
        type: "message-received",
        data: { senderName: "alice", content: "hello" },
      }),
    );

    es.emitNamedEvent(
      "file-record-success",
      JSON.stringify({ fileName: "contract.pdf" }),
    );
    expect(onMessage).toHaveBeenCalledWith(
      expect.objectContaining({
        type: "file-record-success",
        data: { fileName: "contract.pdf" },
      }),
    );

    es.emitNamedEvent("friend-share", "bad-json");
    expect(errorSpy).toHaveBeenCalled();

    es.emitError();
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);

    errorSpy.mockRestore();
  });

  it("closeSSEConnection 应关闭连接，传 null 时应安全退出", () => {
    const closeFn = vi.fn();

    closeSSEConnection({ close: closeFn } as unknown as EventSource);
    closeSSEConnection(null);

    expect(closeFn).toHaveBeenCalledTimes(1);
  });
});
