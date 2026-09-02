import { cleanup, fireEvent, render, waitFor } from "@testing-library/svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getUploadPolicy: vi.fn(),
  addFile: vi.fn(),
  warning: vi.fn(),
}));

vi.mock("$api/endpoints/upload", () => ({
  getUploadPolicy: mocks.getUploadPolicy,
}));

vi.mock("$stores/upload.svelte", () => ({
  useUpload: () => ({
    tasks: [],
    failedTasks: [],
    activeTasks: [],
    completedTasks: [],
    addFile: mocks.addFile,
    removeTask: vi.fn(),
    clearCompleted: vi.fn(),
    clearFailedAndCancelled: vi.fn(),
    retryAllFailedAndCancelled: vi.fn(),
    cancelAllActiveAndProcessing: vi.fn(),
    pauseUpload: vi.fn(),
    resumeUpload: vi.fn(),
    retryUpload: vi.fn(),
  }),
}));

vi.mock("$stores/notifications.svelte", () => ({
  useNotifications: () => ({
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: mocks.warning,
  }),
}));

import UploadPage from "./+page.svelte";

const policy = {
  maxFileSizeBytes: 1024,
  fileTypes: [
    {
      extension: "pdf",
      category: "document",
      categoryLabel: "文档",
      previewMode: "pdf" as const,
      mimeTypes: ["application/pdf"],
    },
  ],
};

describe("upload page policy wiring", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.addFile.mockResolvedValue("task-id");
  });

  afterEach(() => {
    cleanup();
  });

  it("applies the server accept contract and rejects mismatches before adding a task", async () => {
    mocks.getUploadPolicy.mockResolvedValue(policy);
    const view = render(UploadPage);
    const input =
      view.container.querySelector<HTMLInputElement>('input[type="file"]');
    const dropTarget =
      view.container.querySelector<HTMLElement>('[role="button"]');
    expect(input).not.toBeNull();
    expect(dropTarget).not.toBeNull();

    await waitFor(() => expect(input?.getAttribute("accept")).toBe(".pdf"));
    expect(view.getByText(/支持文档，单文件最大/)).toBeTruthy();

    await fireEvent.drop(dropTarget!, {
      dataTransfer: {
        files: [new File(["bad"], "payload.exe", { type: "image/png" })],
      },
    });
    expect(mocks.addFile).not.toHaveBeenCalled();
    expect(mocks.warning).toHaveBeenCalledWith(
      "文件类型不受支持",
      expect.stringContaining(".exe；可上传类型：文档"),
    );

    const accepted = new File(["pdf"], "paper.pdf", {
      type: "application/pdf",
    });
    await fireEvent.drop(dropTarget!, {
      dataTransfer: { files: [accepted] },
    });
    await waitFor(() => expect(mocks.addFile).toHaveBeenCalledWith(accepted));
  });

  it("keeps the selector unconstrained and delegates final validation when policy loading fails", async () => {
    mocks.getUploadPolicy.mockRejectedValue(new Error("offline"));
    const view = render(UploadPage);

    await waitFor(() =>
      expect(
        view.getByText("上传策略暂时无法加载，将由服务器最终校验"),
      ).toBeTruthy(),
    );
    const input =
      view.container.querySelector<HTMLInputElement>('input[type="file"]');
    expect(input?.getAttribute("accept")).toBe("");

    const fallbackFile = new File(["unknown"], "sample.unknown", {
      type: "application/octet-stream",
    });
    await fireEvent.drop(
      view.container.querySelector<HTMLElement>('[role="button"]')!,
      { dataTransfer: { files: [fallbackFile] } },
    );
    await waitFor(() =>
      expect(mocks.addFile).toHaveBeenCalledWith(fallbackFile),
    );
  });
});
