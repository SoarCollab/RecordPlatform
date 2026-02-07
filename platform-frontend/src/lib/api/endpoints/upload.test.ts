import { beforeEach, describe, expect, it, vi } from "vitest";

const clientMocks = vi.hoisted(() => {
  return {
    api: {
      get: vi.fn(),
      post: vi.fn(),
      put: vi.fn(),
      delete: vi.fn(),
      patch: vi.fn(),
      upload: vi.fn(),
    },
  };
});

vi.mock("../client", () => ({
  api: clientMocks.api,
}));

import * as uploadApi from "./upload";

/**
 * 从 URLSearchParams 中读取键值，便于断言请求体。
 *
 * @param params URLSearchParams 实例。
 * @returns 普通对象。
 */
function paramsToObject(params: URLSearchParams): Record<string, string> {
  return Object.fromEntries(params.entries());
}

describe("upload endpoints", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("startUpload 应使用 URLSearchParams 适配后端 @RequestParam", async () => {
    clientMocks.api.post.mockResolvedValue({ clientId: "c1", processedChunks: [] });

    await uploadApi.startUpload({
      fileName: "report.pdf",
      fileSize: 1024,
      contentType: "application/pdf",
      totalChunks: 2,
      chunkSize: 512,
    });

    const [, body] = clientMocks.api.post.mock.calls[0];
    const payload = paramsToObject(body as URLSearchParams);
    expect(clientMocks.api.post).toHaveBeenCalledWith(
      "/upload-sessions",
      expect.any(URLSearchParams),
    );
    expect(payload).toEqual({
      fileName: "report.pdf",
      fileSize: "1024",
      contentType: "application/pdf",
      totalChunks: "2",
      chunkSize: "512",
    });
  });

  it("uploadChunk 应构建 FormData 并调用 PUT 新路径", async () => {
    clientMocks.api.put.mockResolvedValue(undefined);
    const blob = new Blob(["abc"], { type: "text/plain" });

    await uploadApi.uploadChunk("client-1", 3, blob);

    const [, formData] = clientMocks.api.put.mock.calls[0];
    expect(clientMocks.api.put).toHaveBeenCalledWith(
      "/upload-sessions/client-1/chunks/3",
      expect.any(FormData),
    );
    expect((formData as FormData).get("file")).toBeInstanceOf(Blob);
  });

  it("complete/pause/resume/cancel 应携带 clientId 参数", async () => {
    clientMocks.api.post
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce({ clientId: "client-1", processedChunks: [1] });
    clientMocks.api.delete.mockResolvedValueOnce(undefined);

    await uploadApi.completeUpload("client-1");
    await uploadApi.pauseUpload("client-1");
    await uploadApi.resumeUpload("client-1");
    await uploadApi.cancelUpload("client-1");

    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      1,
      "/upload-sessions/client-1/complete",
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      2,
      "/upload-sessions/client-1/pause",
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      3,
      "/upload-sessions/client-1/resume",
    );
    expect(clientMocks.api.delete).toHaveBeenNthCalledWith(
      1,
      "/upload-sessions/client-1",
    );
  });

  it("checkUploadStatus/getUploadProgress 应走 GET 并透传 clientId", async () => {
    clientMocks.api.get
      .mockResolvedValueOnce({ status: "uploading" })
      .mockResolvedValueOnce({ progress: 80, uploadProgress: 80, processProgress: 0 });

    await uploadApi.checkUploadStatus("client-2");
    await uploadApi.getUploadProgress("client-2");

    expect(clientMocks.api.get).toHaveBeenNthCalledWith(1, "/upload-sessions/client-2");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      2,
      "/upload-sessions/client-2/progress",
    );
  });
});
