import { beforeEach, describe, expect, it, vi } from "vitest";
import { ShareType } from "../types";

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

const cryptoMocks = vi.hoisted(() => {
  return {
    decryptFile: vi.fn(),
    arrayToBlob: vi.fn(),
  };
});

vi.mock("../client", () => ({
  api: clientMocks.api,
}));

vi.mock("$utils/crypto", () => ({
  decryptFile: cryptoMocks.decryptFile,
  arrayToBlob: cryptoMocks.arrayToBlob,
}));

import * as filesApi from "./files";

/**
 * 生成可复用的 Blob 测试对象。
 *
 * @returns 用于下载结果断言的 Blob。
 */
function createBlob(): Blob {
  return new Blob(["plain"], { type: "text/plain" });
}

/**
 * 将普通对象转换为 URLSearchParams 断言友好的键值对。
 *
 * @param value 待断言对象。
 * @returns 标准对象形式。
 */
function normalizeParams(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object") {
    return {};
  }
  return value as Record<string, unknown>;
}

describe("files endpoints", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    cryptoMocks.decryptFile.mockResolvedValue(new Uint8Array([9, 9]));
    cryptoMocks.arrayToBlob.mockReturnValue(createBlob());
  });

  it("基础查询接口应透传路径与参数", async () => {
    clientMocks.api.get
      .mockResolvedValueOnce({ records: [], total: 0 })
      .mockResolvedValueOnce({ totalFiles: 10 })
      .mockResolvedValueOnce({ id: "f1" })
      .mockResolvedValueOnce({ hash: "h1" })
      .mockResolvedValueOnce(["u1"])
      .mockResolvedValueOnce({ initialKey: "k", chunkCount: 1 })
      .mockResolvedValueOnce({ records: [], total: 0 })
      .mockResolvedValueOnce(["u1", "u2"])
      .mockResolvedValueOnce({ tx: "t1" })
      .mockResolvedValueOnce({ records: [], total: 0 })
      .mockResolvedValueOnce({ count: 1 })
      .mockResolvedValueOnce({ path: [] });

    await filesApi.getFiles({ pageNum: 1, pageSize: 20 });
    await filesApi.getUserFileStats();
    await filesApi.getFile("f1");
    await filesApi.getFileByHash("h1");
    await filesApi.downloadEncryptedChunks("h1");
    await filesApi.getDecryptInfo("h1");
    await filesApi.getMyShares({ pageNum: 1, pageSize: 5 });
    await filesApi.getDownloadAddress("h1");
    await filesApi.getTransaction("tx-hash");
    await filesApi.getShareAccessLogs("code1", { pageNum: 2 });
    await filesApi.getShareAccessStats("code1");
    await filesApi.getFileProvenance("file-id");

    expect(clientMocks.api.get).toHaveBeenNthCalledWith(1, "/files", {
      params: { pageNum: 1, pageSize: 20 },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(2, "/files/stats");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(3, "/files/f1");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(4, "/files/hash/h1");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(5, "/files/hash/h1/chunks");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      6,
      "/files/hash/h1/decrypt-info",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(7, "/files/shares", {
      params: { pageNum: 1, pageSize: 5 },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      8,
      "/files/hash/h1/addresses",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(9, "/transactions/tx-hash");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      10,
      "/files/share/code1/access-logs",
      { params: { pageNum: 2 } },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      11,
      "/files/share/code1/stats",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      12,
      "/files/file-id/provenance",
    );
  });

  it("写操作接口应透传路径与负载", async () => {
    clientMocks.api.post
      .mockResolvedValueOnce("share-code")
      .mockResolvedValueOnce(undefined);
    clientMocks.api.patch.mockResolvedValue(undefined);
    clientMocks.api.delete.mockResolvedValue(undefined);

    await filesApi.createShare({ fileHash: ["h1"], expireMinutes: 30, shareType: ShareType.PUBLIC });
    await filesApi.updateShare({ shareCode: "code1", shareType: ShareType.PRIVATE });
    await filesApi.deleteFile("file-id");
    await filesApi.cancelShare("share-code");
    await filesApi.saveSharedFiles({ sharingFileIdList: ["f1"], shareCode: "share-code" });

    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      1,
      "/shares",
      expect.objectContaining({ fileHash: ["h1"], expireMinutes: 30 }),
    );
    expect(clientMocks.api.patch).toHaveBeenCalledWith(
      "/shares/code1",
      expect.objectContaining({ shareCode: "code1" }),
    );
    expect(clientMocks.api.delete).toHaveBeenNthCalledWith(1, "/files/delete", {
      params: { identifiers: ["file-id"] },
    });
    expect(clientMocks.api.delete).toHaveBeenNthCalledWith(
      2,
      "/files/share/share-code",
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(2, "/shares/share-code/files/save", {
      sharingFileIdList: ["f1"],
      shareCode: "share-code",
    });
  });

  it("getShareByCode 应抛出后端未实现错误", async () => {
    await expect(filesApi.getShareByCode("abc")).rejects.toThrow(
      "后端未提供 GET /files/share/{code} 接口",
    );
  });

  it("getSharedFiles 应携带 skipAuth", async () => {
    clientMocks.api.get.mockResolvedValue([{ id: "shared-1" }]);

    const result = await filesApi.getSharedFiles("code-a");

    expect(result).toEqual([{ id: "shared-1" }]);
    expect(clientMocks.api.get).toHaveBeenCalledWith("/shares/code-a/files", {
      skipAuth: true,
    });
  });

  it("downloadFile 应下载分片、解密并转 Blob", async () => {
    const blob = createBlob();
    cryptoMocks.arrayToBlob.mockReturnValue(blob);
    clientMocks.api.get
      .mockResolvedValueOnce(["AQID", "BAUG"])
      .mockResolvedValueOnce({ initialKey: "k1", contentType: "text/plain" });

    const result = await filesApi.downloadFile("hash-1");

    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      1,
      "/files/hash/hash-1/chunks",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      2,
      "/files/hash/hash-1/decrypt-info",
    );
    expect(cryptoMocks.decryptFile).toHaveBeenCalledWith(
      expect.any(Array),
      "k1",
    );
    expect(cryptoMocks.arrayToBlob).toHaveBeenCalledWith(
      new Uint8Array([9, 9]),
      "text/plain",
    );
    expect(result).toBe(blob);
  });

  it("publicDownloadFile 应走公开接口链路", async () => {
    const blob = createBlob();
    cryptoMocks.arrayToBlob.mockReturnValue(blob);
    clientMocks.api.get
      .mockResolvedValueOnce(["AQID"])
      .mockResolvedValueOnce({ initialKey: "k2", contentType: "application/pdf" });

    const result = await filesApi.publicDownloadFile("share-public", "hash-2");

    expect(result).toBe(blob);
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      1,
      "/public/shares/share-public/files/hash-2/chunks",
      { skipAuth: true },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      2,
      "/public/shares/share-public/files/hash-2/decrypt-info",
      { skipAuth: true },
    );
  });

  it("shareDownloadFile 应走私密分享接口链路", async () => {
    const blob = createBlob();
    cryptoMocks.arrayToBlob.mockReturnValue(blob);
    clientMocks.api.get
      .mockResolvedValueOnce(["AQID"])
      .mockResolvedValueOnce({ initialKey: "k3", contentType: "application/zip" });

    const result = await filesApi.shareDownloadFile("share-private", "hash-3");

    expect(result).toBe(blob);
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      1,
      "/shares/share-private/files/hash-3/chunks",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      2,
      "/shares/share-private/files/hash-3/decrypt-info",
    );
  });

  it("downloadSharedFile 应按分享类型分流", async () => {
    clientMocks.api.get
      .mockResolvedValueOnce(["AQID"])
      .mockResolvedValueOnce({ initialKey: "k4", contentType: "text/plain" })
      .mockResolvedValueOnce(["AQID"])
      .mockResolvedValueOnce({ initialKey: "k5", contentType: "text/plain" });

    await filesApi.downloadSharedFile("code-public", "hash-p", ShareType.PUBLIC);
    await filesApi.downloadSharedFile("code-private", "hash-s", ShareType.PRIVATE);

    const calls = clientMocks.api.get.mock.calls.map((call) => {
      return [call[0], normalizeParams(call[1])];
    });

    expect(calls[0][0]).toBe("/public/shares/code-public/files/hash-p/chunks");
    expect(calls[2][0]).toBe("/shares/code-private/files/hash-s/chunks");
  });
});
