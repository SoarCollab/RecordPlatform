import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

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

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("startUpload 应使用 URLSearchParams 适配后端 @RequestParam", async () => {
    clientMocks.api.post.mockResolvedValue({
      clientId: "c1",
      processedChunks: [],
    });

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

  it("startUpload 在提供 fileId 时应透传参数", async () => {
    clientMocks.api.post.mockResolvedValue({
      clientId: "c2",
      processedChunks: [],
    });

    await uploadApi.startUpload({
      fileName: "report-v2.pdf",
      fileSize: 2048,
      contentType: "application/pdf",
      totalChunks: 4,
      chunkSize: 512,
      fileId: "ext_file_id",
    });

    const [, body] = clientMocks.api.post.mock.calls[0];
    const payload = paramsToObject(body as URLSearchParams);
    expect(payload.fileId).toBe("ext_file_id");
  });

  it("startDirectUpload 应使用 JSON body 创建直传会话", async () => {
    clientMocks.api.post.mockResolvedValue({
      clientId: "direct-1",
      parts: [],
    });

    const payload = {
      fileName: "large.bin",
      fileSize: 4096,
      contentType: "application/octet-stream",
      totalChunks: 2,
      chunkSize: 2048,
      parts: [
        {
          index: 0,
          size: 2048,
          plainHash: "sha256:a",
          cipherHash: "sha256:a",
          checksumAlgorithm: "SHA-256",
        },
      ],
    };

    await uploadApi.startDirectUpload(payload);

    expect(clientMocks.api.post).toHaveBeenCalledWith(
      "/upload-sessions/direct",
      payload,
    );
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

  it("uploadDirectPart 应直接 PUT 到预签名 URL 并返回 ETag", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(null, {
        status: 200,
        headers: { ETag: '"etag-1"' },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const blob = new Blob(["abc"], { type: "text/plain" });

    const eTag = await uploadApi.uploadDirectPart(
      "https://storage.example/upload",
      blob,
    );

    expect(fetchMock).toHaveBeenCalledWith("https://storage.example/upload", {
      method: "PUT",
      body: blob,
    });
    expect(clientMocks.api.put).not.toHaveBeenCalled();
    expect(eTag).toBe('"etag-1"');
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

  it("completeDirectUpload/abortDirectUpload 应使用 direct 路径", async () => {
    clientMocks.api.post.mockResolvedValueOnce({
      clientId: "direct-1",
      status: "completed",
    });
    clientMocks.api.delete.mockResolvedValueOnce(undefined);

    await uploadApi.completeDirectUpload("direct-1", {
      parts: [{ index: 0, eTag: '"etag-1"' }],
    });
    await uploadApi.abortDirectUpload("direct-1");

    expect(clientMocks.api.post).toHaveBeenCalledWith(
      "/upload-sessions/direct-1/direct/complete",
      { parts: [{ index: 0, eTag: '"etag-1"' }] },
    );
    expect(clientMocks.api.delete).toHaveBeenCalledWith(
      "/upload-sessions/direct-1/direct",
    );
  });

  it("checkUploadStatus/getUploadProgress 应走 GET 并透传 clientId", async () => {
    clientMocks.api.get
      .mockResolvedValueOnce({ status: "uploading" })
      .mockResolvedValueOnce({
        progress: 80,
        uploadProgress: 80,
        processProgress: 0,
      });

    await uploadApi.checkUploadStatus("client-2");
    await uploadApi.getUploadProgress("client-2");

    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      1,
      "/upload-sessions/client-2",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      2,
      "/upload-sessions/client-2/progress",
    );
  });
});
