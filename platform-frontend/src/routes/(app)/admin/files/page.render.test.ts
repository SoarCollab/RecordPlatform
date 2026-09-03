import { cleanup, fireEvent, render, waitFor } from "@testing-library/svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const adminApiMocks = vi.hoisted(() => ({
  getAllFiles: vi.fn(),
  getFileDetail: vi.fn(),
  updateFileStatus: vi.fn(),
  forceDeleteFile: vi.fn(),
  getAllShares: vi.fn(),
  forceCancelShare: vi.fn(),
  getShareAccessLogs: vi.fn(),
}));
const fileApiMocks = vi.hoisted(() => ({ downloadFile: vi.fn() }));

vi.mock("$api/endpoints/admin", () => adminApiMocks);
vi.mock("$api/endpoints/files", () => fileApiMocks);

import AdminFilesPage from "./+page.svelte";

describe("admin file preview dialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    adminApiMocks.getAllFiles.mockResolvedValue({
      records: [
        {
          id: "file-1",
          fileName: "wide.sql",
          fileHash: "hash-1",
          fileSize: 1024,
          contentType: "text/plain",
          status: 1,
          statusDesc: "已完成",
          ownerId: "owner-1",
          ownerName: "tester",
          isOriginal: true,
          depth: 0,
          createTime: "2026-09-03 09:45:50",
        },
      ],
      total: 1,
    });
    fileApiMocks.downloadFile.mockResolvedValue(
      new Blob(["SELECT " + "column_name,".repeat(200)], {
        type: "text/plain",
      }),
    );
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("wide line")),
    );
    vi.stubGlobal("URL", {
      ...URL,
      createObjectURL: vi.fn(() => "blob:preview-file-1"),
      revokeObjectURL: vi.fn(),
    });
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("provides a bounded scroll region and resets expanded mode after close", async () => {
    const view = render(AdminFilesPage);
    await waitFor(() => expect(view.getByText("wide.sql")).toBeTruthy());

    await fireEvent.click(view.getByTitle("预览"));
    await waitFor(() => expect(view.getByText("放大查看")).toBeTruthy());

    const scrollRegion = view.getByTestId("file-preview-scroll-region");
    expect(scrollRegion.className).toContain("overflow-auto");
    await waitFor(() =>
      expect(scrollRegion.querySelector("pre")).not.toBeNull(),
    );
    expect(scrollRegion.querySelector("pre")?.className).toContain("min-w-max");
    expect(scrollRegion.querySelector("pre")?.className).not.toContain(
      "overflow-auto",
    );
    const dialog = scrollRegion.closest('[data-slot="dialog-content"]');
    expect(dialog?.getAttribute("data-preview-expanded")).toBe("false");

    await fireEvent.click(view.getByRole("button", { name: "放大查看" }));
    expect(view.getByRole("button", { name: "恢复窗口" })).toBeTruthy();
    expect(dialog?.getAttribute("data-preview-expanded")).toBe("true");

    await fireEvent.click(view.getByRole("button", { name: "关闭" }));
    await fireEvent.click(view.getByTitle("预览"));
    await waitFor(() => expect(view.getByText("放大查看")).toBeTruthy());
    expect(
      view
        .getByTestId("file-preview-scroll-region")
        .closest('[data-slot="dialog-content"]')
        ?.getAttribute("data-preview-expanded"),
    ).toBe("false");
  });
});
