import { cleanup, fireEvent, render, waitFor } from "@testing-library/svelte";
import { afterEach, describe, expect, it, vi } from "vitest";

const apiMocks = vi.hoisted(() => ({
  fetchText: vi.fn(),
}));

vi.mock("$api/client", () => ({
  api: apiMocks,
}));

import FilePreview from "./FilePreview.svelte";

describe("FilePreview", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it.each([
    [
      "active.svg",
      "image/svg+xml",
      '<svg onload="window.__executed=true"><script>window.__executed=true</script><image href="https://invalid.example/track" /></svg>',
    ],
    [
      "active.html",
      "text/html",
      '<script>window.__executed=true</script><img src="https://invalid.example/track" onerror="window.__executed=true">',
    ],
  ])(
    "renders hostile %s bytes only as escaped text",
    async (fileName, contentType, activePayload) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(new Response(activePayload, { status: 200 })),
      );

      const view = render(FilePreview, {
        url: "blob:https://example.test/active",
        fileName,
        contentType,
      });

      await waitFor(() => expect(view.getByText(activePayload)).toBeTruthy());
      expect(view.container.querySelector("script")).toBeNull();
      expect(view.container.querySelector("image")).toBeNull();
      expect(view.container.querySelector("img")).toBeNull();
      expect(apiMocks.fetchText).not.toHaveBeenCalled();
    },
  );

  it("falls back to an explicit download action after native decode failure", async () => {
    const view = render(FilePreview, {
      url: "blob:https://example.test/photo",
      fileName: "photo.webp",
      contentType: "image/webp",
    });

    const image = view.getByRole("img", { name: "photo.webp" });
    await fireEvent.error(image);

    expect(view.getByText("浏览器无法解码此文件")).toBeTruthy();
    const download = view.getByRole("link", { name: "下载文件" });
    expect(download.getAttribute("download")).toBe("photo.webp");
  });

  it("does not advertise metadata-only documents as previewable", () => {
    const view = render(FilePreview, {
      url: "blob:https://example.test/document",
      fileName: "report.docx",
      contentType:
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    });

    expect(view.getByText("此文件类型不支持预览")).toBeTruthy();
    expect(view.getByRole("link", { name: "下载文件" })).toBeTruthy();
  });

  it("resets a native decode failure and reloads text when the source changes", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("next text", { status: 200 })),
    );
    const view = render(FilePreview, {
      url: "blob:https://example.test/photo",
      fileName: "photo.webp",
      contentType: "image/webp",
    });

    await fireEvent.error(view.getByRole("img", { name: "photo.webp" }));
    expect(view.getByText("浏览器无法解码此文件")).toBeTruthy();

    await view.rerender({
      url: "blob:https://example.test/next",
      fileName: "next.txt",
      contentType: "text/plain",
    });

    await waitFor(() => expect(view.getByText("next text")).toBeTruthy());
    expect(view.queryByText("浏览器无法解码此文件")).toBeNull();
  });
});
