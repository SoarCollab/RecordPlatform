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

  it("getUploadPolicy 应读取服务器权威上传策略", async () => {
    const policy = { maxFileSizeBytes: 1024, fileTypes: [] };
    clientMocks.api.get.mockResolvedValue(policy);

    await expect(uploadApi.getUploadPolicy()).resolves.toEqual(policy);
    expect(clientMocks.api.get).toHaveBeenCalledWith("/upload-sessions/policy");
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

  it("uploadDirectPart 应在对象存储未暴露 ETag 时失败关闭", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 200 })),
    );

    await expect(
      uploadApi.uploadDirectPart(
        "https://storage.example/upload",
        new Blob(["abc"]),
      ),
    ).rejects.toThrow("对象存储未暴露 ETag");

    expect(clientMocks.api.post).not.toHaveBeenCalled();
  });

  it("uploadDirectPart 应拒绝对象存储返回的空白 ETag", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: { get: () => "   " },
      }),
    );

    await expect(
      uploadApi.uploadDirectPart(
        "https://storage.example/upload",
        new Blob(["abc"]),
      ),
    ).rejects.toThrow("ETag 必须是 1 到 255 个可见 ASCII 字符");

    expect(clientMocks.api.post).not.toHaveBeenCalled();
  });

  it.each([
    ["1", "x"],
    ["255", "x".repeat(255)],
  ])(
    "uploadDirectPart 应原样返回合法边界 ETag（长度 %s）",
    async (_length, eTag) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue({
          ok: true,
          status: 200,
          headers: { get: () => eTag },
        }),
      );

      await expect(
        uploadApi.uploadDirectPart(
          "https://storage.example/upload",
          new Blob(["abc"]),
        ),
      ).resolves.toBe(eTag);
    },
  );

  it.each([
    ["前后空格", ' "etag" '],
    ["控制字符", "etag\nbreak"],
    ["DEL 字符", "etag\u007f"],
    ["非 ASCII", '"étag"'],
    ["超过长度上限", "x".repeat(256)],
  ])("uploadDirectPart 应拒绝%s的 ETag", async (_caseName, eTag) => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: { get: () => eTag },
      }),
    );

    await expect(
      uploadApi.uploadDirectPart(
        "https://storage.example/upload",
        new Blob(["abc"]),
      ),
    ).rejects.toThrow("ETag 必须是 1 到 255 个可见 ASCII 字符");
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
