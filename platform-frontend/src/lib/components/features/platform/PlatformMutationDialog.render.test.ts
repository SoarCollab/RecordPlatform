import "@testing-library/jest-dom/vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { beforeNavigate } from "$app/navigation";
import { ApiError, clearToken, setToken } from "$api/client";
import type {
  PlatformCommand,
  PlatformMutationAttempt,
} from "$utils/platformMutation";
import type { PlatformMutationVO } from "$api/types";
import PlatformMutationDialog from "./PlatformMutationDialog.svelte";

const firstKey = "8914a4ab-c9c2-4e9f-8e48-8cffac10b17c";
const nextKey = "4714a4ab-c9c2-4e9f-8e48-8cffac10b17c";
const command: PlatformCommand = {
  operation: "TENANT_UPDATE",
  tenantId: "tenant-a",
  payload: { name: "Reviewed name", expectedVersion: 0 },
};
const result: PlatformMutationVO = {
  operationId: "op-a",
  resourceId: "tenant-a",
  version: 1,
};

/** Create independently controlled asynchronous mutation results. */
function deferred<T>() {
  let resolve: (value: T) => void = () => {};
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
/** Mount the real shared dialog with a reviewed target and observable callbacks. */
function mount(
  onConfirm = vi
    .fn<(attempt: PlatformMutationAttempt) => Promise<PlatformMutationVO>>()
    .mockResolvedValue(result),
  onReload = vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
  onSuccess = vi
    .fn<(result: PlatformMutationVO) => Promise<void>>()
    .mockResolvedValue(undefined),
) {
  const view = render(PlatformMutationDialog, {
    open: true,
    title: "修改租户名称",
    targetLabel: "Alpha tenant",
    command,
    onConfirm,
    onReload,
    onSuccess,
  });
  return { view, onConfirm, onReload, onSuccess };
}
/** Enter the mandatory reason and advance to the immutable review step. */
async function review(): Promise<void> {
  await fireEvent.input(screen.getByLabelText("操作原因"), {
    target: { value: "Approved change" },
  });
  await fireEvent.click(screen.getByRole("button", { name: "核对变更" }));
  await screen.findByText("核对后确认此操作");
}

beforeEach(() => {
  vi.clearAllMocks();
  setToken("reviewing-operator", "2099-01-01", false, "platform");
  vi.spyOn(crypto, "randomUUID")
    .mockReturnValueOnce(firstKey)
    .mockReturnValue(nextKey);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("reviewed platform mutation interaction", () => {
  it("requires reason and an explicit second confirmation before sending", async () => {
    const { onConfirm } = mount();
    expect(screen.getByRole("button", { name: "核对变更" })).toBeDisabled();
    await review();
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByText("Reviewed name")).toBeInTheDocument();
    expect(screen.getByText("tenant-a")).toBeInTheDocument();
    await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
    await screen.findByText("操作成功");
    expect(onConfirm).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        reason: "Approved change",
        idempotencyKey: firstKey,
        command,
      }),
    );
  });
  it("does not submit a reviewed command after another identity replaces its credential", async () => {
    const { onConfirm } = mount();
    await review();
    setToken("different-operator", "2099-01-01", false, "platform");
    await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
    expect(onConfirm).not.toHaveBeenCalled();
    await screen.findByRole("button", { name: "关闭" });
  });
  it("allows session-loss navigation while a write is still pending", async () => {
    const pending = deferred<PlatformMutationVO>();
    mount(vi.fn().mockReturnValue(pending.promise));
    await review();
    await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
    clearToken();
    const cancel = vi.fn();
    try {
      vi.mocked(beforeNavigate).mock.calls[0]?.[0]({ cancel } as never);
      expect(cancel).not.toHaveBeenCalled();
    } finally {
      pending.resolve(result);
    }
  });
  it("blocks duplicate submit, outside dismissal, escape and navigation while pending", async () => {
    const pending = deferred<PlatformMutationVO>();
    const onConfirm = vi.fn().mockReturnValue(pending.promise);
    mount(onConfirm);
    await review();
    await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
    expect(screen.getByRole("button", { name: "提交中…" })).toBeDisabled();
    await fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    const cancel = vi.fn();
    const callback = vi.mocked(beforeNavigate).mock.calls[0]?.[0];
    expect(callback).toBeDefined();
    callback?.({ cancel } as never);
    expect(cancel).toHaveBeenCalledOnce();
    pending.resolve(result);
    await screen.findByText("操作成功");
    expect(onConfirm).toHaveBeenCalledOnce();
  });
  it.each([90001, 90002, 50024])(
    "retains the identical attempt after code %s and parent draft changes",
    async (code) => {
      const onConfirm = vi
        .fn<(attempt: PlatformMutationAttempt) => Promise<PlatformMutationVO>>()
        .mockRejectedValueOnce(new ApiError(code, "private-provider-data"))
        .mockResolvedValueOnce(result);
      const { view } = mount(onConfirm);
      await review();
      await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
      await screen.findByRole("button", { name: "使用同一操作重试" });
      await view.rerender({
        command: {
          ...command,
          payload: { name: "Changed draft", expectedVersion: 99 },
        },
      });
      expect(screen.queryByText("Changed draft")).not.toBeInTheDocument();
      expect(
        screen.queryByText("private-provider-data"),
      ).not.toBeInTheDocument();
      await fireEvent.click(
        screen.getByRole("button", { name: "使用同一操作重试" }),
      );
      await screen.findByText("操作成功");
      expect(onConfirm.mock.calls[1]?.[0]).toBe(onConfirm.mock.calls[0]?.[0]);
      expect(crypto.randomUUID).toHaveBeenCalledOnce();
    },
  );
  it("reloads a version conflict before permitting a separately reviewed new command", async () => {
    const onConfirm = vi
      .fn<(attempt: PlatformMutationAttempt) => Promise<PlatformMutationVO>>()
      .mockRejectedValueOnce(new ApiError(50022, "conflict"))
      .mockResolvedValueOnce(result);
    const { view, onReload } = mount(onConfirm);
    await review();
    await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
    await screen.findByRole("button", { name: "重新读取并编辑" });
    expect(onConfirm).toHaveBeenCalledOnce();
    await view.rerender({
      command: {
        ...command,
        payload: { name: "Fresh name", expectedVersion: 2 },
      },
    });
    await fireEvent.click(
      screen.getByRole("button", { name: "重新读取并编辑" }),
    );
    expect(onReload).toHaveBeenCalledOnce();
    expect(onConfirm).toHaveBeenCalledOnce();
    await fireEvent.click(screen.getByRole("button", { name: "核对变更" }));
    await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
    await screen.findByText("操作成功");
    expect(onConfirm.mock.calls[1]?.[0]).toMatchObject({
      idempotencyKey: nextKey,
      command: { payload: { expectedVersion: 2 } },
    });
  });
  it.each([50023, 70002, 50021])(
    "does not silently replace or resubmit a rejected attempt for code %s",
    async (code) => {
      const onConfirm = vi
        .fn()
        .mockRejectedValue(new ApiError(code, "private"));
      mount(onConfirm);
      await review();
      await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
      await screen.findByRole("button", { name: "关闭" });
      expect(
        screen.queryByRole("button", { name: "使用同一操作重试" }),
      ).not.toBeInTheDocument();
      expect(onConfirm).toHaveBeenCalledOnce();
      expect(crypto.randomUUID).toHaveBeenCalledOnce();
    },
  );
  it("keeps a confirmed success when refresh fails and retries reads without another write", async () => {
    const onSuccess = vi.fn().mockRejectedValue(new Error("refresh failed"));
    const { onConfirm, onReload } = mount(undefined, undefined, onSuccess);
    await review();
    await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
    await screen.findByText("操作成功");
    await fireEvent.click(
      await screen.findByRole("button", { name: "重新读取数据" }),
    );
    await waitFor(() => expect(onReload).toHaveBeenCalledOnce());
    expect(onConfirm).toHaveBeenCalledOnce();
  });
  it("preserves conflict state if its authoritative reload fails", async () => {
    const { onConfirm } = mount(
      vi.fn().mockRejectedValue(new ApiError(50022, "conflict")),
      vi.fn().mockRejectedValue(new Error("read failed")),
    );
    await review();
    await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
    await fireEvent.click(
      await screen.findByRole("button", { name: "重新读取并编辑" }),
    );
    await screen.findByText("读取最新状态失败，请稍后重试。");
    expect(onConfirm).toHaveBeenCalledOnce();
    expect(
      screen.queryByRole("button", { name: "确认执行" }),
    ).not.toBeInTheDocument();
  });
  it("allows cancellation before submission and does not call the mutation", async () => {
    const { onConfirm } = mount();
    const dialog = screen.getByRole("dialog");
    await fireEvent.click(within(dialog).getByRole("button", { name: "取消" }));
    expect(onConfirm).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
  });
  it("discards an externally closed draft before another target is opened", async () => {
    const { view, onConfirm } = mount();
    await review();
    await view.rerender({ open: false });
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    await view.rerender({
      open: true,
      targetLabel: "Beta tenant",
      command: {
        ...command,
        tenantId: "tenant-b",
        payload: { name: "Beta name", expectedVersion: 2 },
      },
    });
    expect(await screen.findByLabelText("操作原因")).toHaveValue("");
    expect(screen.queryByText("Reviewed name")).not.toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
