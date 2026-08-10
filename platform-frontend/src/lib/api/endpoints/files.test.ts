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

/** 构造通过 OpenAPI transport 运行时收窄的下载 metadata。 */
function createDownloadMetadataTransport(
  fileHash = "h1",
  accessKind = "OWNER",
) {
  const manifestHash = `sha256:${"b".repeat(64)}`;
  return {
    fileId: "file-1",
    fileHash,
    fileName: "download.bin",
    fileSize: 1,
    contentType: "application/octet-stream",
    manifestSchemaId: "cn.flying.chunk-manifest.v1",
    manifestHash,
    canonicalManifestJson: "{}",
    manifestStatus: "ACTIVE",
    manifestClassification: "ALREADY_MANIFEST",
    legacyDownloadAllowed: false,
    hashAlgorithm: "SHA-256",
    encryptionAlgorithm: "NONE",
    storageBackend: "S3",
    chunkSize: 1,
    totalChunks: 1,
    accessIdentity: {
      accessKind,
      identityHash: `sha256:${"a".repeat(64)}`,
      fileVersion: 1,
      manifestHash,
      algorithmSuite: "NONE",
    },
    parts: [
      {
        index: 0,
        size: 1,
        downloadUrl: "https://storage.example/part-0",
        expiresAtEpochSeconds: 2_000_000_000,
        storagePath: "tenant/1/part-0",
        plainHash: `sha256:${"c".repeat(64)}`,
        cipherHash: `sha256:${"c".repeat(64)}`,
      },
    ],
  };
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
      .mockResolvedValueOnce({ enforcementMode: "SHADOW" })
      .mockResolvedValueOnce({ id: "f1" })
      .mockResolvedValueOnce({ hash: "h1" })
      .mockResolvedValueOnce(["u1"])
      .mockResolvedValueOnce({ initialKey: "k", chunkCount: 1 })
      .mockResolvedValueOnce({ records: [], total: 0 })
      .mockResolvedValueOnce(["u1", "u2"])
      .mockResolvedValueOnce(createDownloadMetadataTransport())
      .mockResolvedValueOnce({ tx: "t1" })
      .mockResolvedValueOnce({ records: [], total: 0 })
      .mockResolvedValueOnce({ count: 1 })
      .mockResolvedValueOnce({ path: [] });

    await filesApi.getFiles({
      pageNum: 1,
      pageSize: 20,
      keywordMode: "PREFIX",
      startTime: "2026-02-01T00:00:00.000Z",
      endTime: "2026-02-10T00:00:00.000Z",
    });
    await filesApi.getUserFileStats();
    await filesApi.getQuotaStatus();
    await filesApi.getFile("f1");
    await filesApi.getFileByHash("h1");
    await filesApi.downloadEncryptedChunks("h1");
    await filesApi.getDecryptInfo("h1", "download-session-1234");
    await filesApi.getMyShares({ pageNum: 1, pageSize: 5 });
    await filesApi.getDownloadAddress("h1");
    await filesApi.getDownloadMetadata("h1", "download-session-1234");
    await filesApi.getTransaction("tx-hash");
    await filesApi.getShareAccessLogs("code1", { pageNum: 2 });
    await filesApi.getShareAccessStats("code1");
    await filesApi.getFileProvenance("file-id");

    expect(clientMocks.api.get).toHaveBeenNthCalledWith(1, "/files", {
      params: {
        pageNum: 1,
        pageSize: 20,
        keywordMode: "PREFIX",
        startTime: "2026-02-01T00:00:00.000Z",
        endTime: "2026-02-10T00:00:00.000Z",
      },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(2, "/files/stats");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(3, "/files/quota");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(4, "/files/f1");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(5, "/files/hash/h1");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      6,
      "/files/hash/h1/chunks",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      7,
      "/files/hash/h1/decrypt-info",
      {
        headers: {
          "X-Key-Delivery-Protocol": "grant-v1",
          "X-Download-Session-ID": "download-session-1234",
        },
      },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(8, "/files/shares", {
      params: { pageNum: 1, pageSize: 5 },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      9,
      "/files/hash/h1/addresses",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      10,
      "/files/hash/h1/download-metadata",
      {
        headers: {
          "X-Key-Delivery-Protocol": "grant-v1",
          "X-Download-Session-ID": "download-session-1234",
        },
      },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      11,
      "/transactions/tx-hash",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      12,
      "/files/share/code1/access-logs",
      { params: { pageNum: 2 } },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      13,
      "/files/share/code1/stats",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      14,
      "/files/file-id/provenance",
    );
  });

  it("共享 metadata 端点应使用生成 transport 并保留认证边界", async () => {
    clientMocks.api.get
      .mockResolvedValueOnce(
        createDownloadMetadataTransport("public-hash", "PUBLIC_SHARE"),
      )
      .mockResolvedValueOnce(
        createDownloadMetadataTransport("private-hash", "AUTHENTICATED_SHARE"),
      );

    await filesApi.getPublicShareDownloadMetadata(
      "public-code",
      "public-hash",
      "public-session",
    );
    await filesApi.getAuthenticatedShareDownloadMetadata(
      "private-code",
      "private-hash",
      "private-session",
    );

    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      1,
      "/public/shares/public-code/files/public-hash/download-metadata",
      {
        skipAuth: true,
        skipTenant: true,
        headers: {
          "X-Key-Delivery-Protocol": "grant-v1",
          "X-Download-Session-ID": "public-session",
        },
      },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      2,
      "/shares/private-code/files/private-hash/download-metadata",
      {
        headers: {
          "X-Key-Delivery-Protocol": "grant-v1",
          "X-Download-Session-ID": "private-session",
        },
      },
    );
  });

  it("应在 endpoint 边界拒绝缺失或漂移的访问身份", async () => {
    const missingIdentity = createDownloadMetadataTransport();
    Reflect.deleteProperty(missingIdentity, "accessIdentity");
    clientMocks.api.get.mockResolvedValueOnce(missingIdentity);

    await expect(
      filesApi.getDownloadMetadata("h1", "download-session-1234"),
    ).rejects.toThrow("accessIdentity");

    const missingVersion = createDownloadMetadataTransport();
    Reflect.deleteProperty(missingVersion.accessIdentity, "fileVersion");
    clientMocks.api.get.mockResolvedValueOnce(missingVersion);
    await expect(
      filesApi.getDownloadMetadata("h1", "download-session-1234"),
    ).rejects.toThrow("fileVersion");

    const driftedIdentity = createDownloadMetadataTransport();
    driftedIdentity.accessIdentity.manifestHash = `sha256:${"d".repeat(64)}`;
    clientMocks.api.get.mockResolvedValueOnce(driftedIdentity);

    await expect(
      filesApi.getDownloadMetadata("h1", "download-session-1234"),
    ).rejects.toThrow("manifestHash 不一致");

    clientMocks.api.get.mockResolvedValueOnce(
      createDownloadMetadataTransport("public-hash", "OWNER"),
    );
    await expect(
      filesApi.getPublicShareDownloadMetadata(
        "public-code",
        "public-hash",
        "public-session",
      ),
    ).rejects.toThrow("accessKind 与端点不一致");
  });

  it("写操作接口应透传路径与负载", async () => {
    clientMocks.api.post
      .mockResolvedValueOnce("share-code")
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce("ok");
    clientMocks.api.patch.mockResolvedValue(undefined);
    clientMocks.api.delete.mockResolvedValue(undefined);

    await filesApi.createShare({
      fileHash: ["h1"],
      expireMinutes: 30,
      shareType: ShareType.PUBLIC,
    });
    await filesApi.updateShare({
      shareCode: "code1",
      shareType: ShareType.PRIVATE,
    });
    await filesApi.deleteFile("file-id");
    await filesApi.cancelShare("share-code");
    await filesApi.saveSharedFiles({
      sharingFileIdList: ["f1"],
      shareCode: "share-code",
    });
    await filesApi.reportBatchDownloadMetrics({
      batchId: "batch-1",
      total: 2,
      successCount: 1,
      failedCount: 1,
      retryCount: 1,
      durationMs: 1200,
      failureReasons: { network_error: 1 },
    });

    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      1,
      "/shares",
      expect.objectContaining({ fileHash: ["h1"], expireMinutes: 30 }),
    );
    expect(clientMocks.api.patch).toHaveBeenCalledWith(
      "/shares/code1",
      expect.objectContaining({ shareCode: "code1" }),
    );
    expect(clientMocks.api.delete).toHaveBeenNthCalledWith(1, "/files", {
      params: { identifiers: ["file-id"] },
    });
    expect(clientMocks.api.delete).toHaveBeenNthCalledWith(
      2,
      "/files/share/share-code",
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      2,
      "/shares/share-code/files/save",
      {
        sharingFileIdList: ["f1"],
        shareCode: "share-code",
      },
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      3,
      "/files/download-batches/report",
      {
        batchId: "batch-1",
        total: 2,
        successCount: 1,
        failedCount: 1,
        retryCount: 1,
        durationMs: 1200,
        failureReasons: { network_error: 1 },
      },
    );
  });

  it("getSharedFiles 应携带 skipAuth", async () => {
    clientMocks.api.get.mockResolvedValue([{ id: "shared-1" }]);

    const result = await filesApi.getSharedFiles("code-a");

    expect(result).toEqual([{ id: "shared-1" }]);
    expect(clientMocks.api.get).toHaveBeenCalledWith("/shares/code-a/files", {
      skipAuth: true,
      skipTenant: true,
    });
  });

  it("downloadFile 应下载分片、解密并转 Blob", async () => {
    const blob = createBlob();
    cryptoMocks.arrayToBlob.mockReturnValue(blob);
    clientMocks.api.get
      .mockResolvedValueOnce(["AQID", "BAUG"])
      .mockResolvedValueOnce({
        keyGrant: {
          reference: "A".repeat(43),
          protocol: "grant-v1",
          expiresAt: "2030-01-01T00:00:00Z",
        },
        contentType: "text/plain",
      });
    clientMocks.api.post.mockResolvedValueOnce({
      initialKey: "k1",
      protocol: "grant-v1",
    });

    const result = await filesApi.downloadFile("hash-1");

    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      1,
      "/files/hash/hash-1/chunks",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      2,
      "/files/hash/hash-1/decrypt-info",
      {
        headers: {
          "X-Key-Delivery-Protocol": "grant-v1",
          "X-Download-Session-ID": expect.any(String),
        },
      },
    );
    expect(cryptoMocks.decryptFile).toHaveBeenCalledWith(
      expect.any(Array),
      "k1",
    );
    const downloadSessionId = clientMocks.api.get.mock.calls[1][1].headers[
      "X-Download-Session-ID"
    ] as string;
    expect(clientMocks.api.post).toHaveBeenCalledWith(
      "/files/key-grants/consume",
      { grantReference: "A".repeat(43), sessionId: downloadSessionId },
      { retries: 1 },
    );
    expect(cryptoMocks.arrayToBlob).toHaveBeenCalledWith(
      new Uint8Array([9, 9]),
      "text/plain",
    );
    expect(result).toBe(blob);
  });

  it("downloadFile 应在分片下载完成后才签发并消费短期 grant", async () => {
    let resolveChunks: ((chunks: string[]) => void) | undefined;
    const chunksPromise = new Promise<string[]>((resolve) => {
      resolveChunks = resolve;
    });
    clientMocks.api.get
      .mockReturnValueOnce(chunksPromise)
      .mockResolvedValueOnce({
        keyGrant: {
          reference: "J".repeat(43),
          protocol: "grant-v1",
          expiresAt: "2030-01-01T00:00:00Z",
        },
        contentType: "text/plain",
      });
    clientMocks.api.post.mockResolvedValueOnce({
      initialKey: "jit-key",
      protocol: "grant-v1",
    });

    const pendingDownload = filesApi.downloadFile("hash-jit");
    await vi.waitFor(() =>
      expect(clientMocks.api.get).toHaveBeenCalledTimes(1),
    );
    expect(clientMocks.api.post).not.toHaveBeenCalled();

    resolveChunks?.(["AQID"]);
    await pendingDownload;

    expect(clientMocks.api.get).toHaveBeenCalledTimes(2);
    expect(clientMocks.api.post).toHaveBeenCalledTimes(1);
  });

  it("downloadFile 应直接合并 NONE 分片且不请求 grant", async () => {
    const blob = createBlob();
    cryptoMocks.arrayToBlob.mockReturnValue(blob);
    clientMocks.api.get
      .mockResolvedValueOnce(["AQID", "BAU="])
      .mockResolvedValueOnce({
        contentType: "application/octet-stream",
        chunkCount: 2,
      });

    const result = await filesApi.downloadFile("hash-none");

    expect(clientMocks.api.post).not.toHaveBeenCalled();
    expect(cryptoMocks.decryptFile).not.toHaveBeenCalled();
    expect(cryptoMocks.arrayToBlob).toHaveBeenCalledWith(
      new Uint8Array([1, 2, 3, 4, 5]),
      "application/octet-stream",
    );
    expect(result).toBe(blob);
  });

  it("consumeDownloadKeyGrant 应在发请求前拒绝未知协议", async () => {
    await expect(
      filesApi.consumeDownloadKeyGrant(
        {
          reference: "U".repeat(43),
          protocol: "future-v2" as "grant-v1",
          expiresAt: "2030-01-01T00:00:00Z",
        },
        "download-session-1234",
      ),
    ).rejects.toThrow("不支持的下载密钥授权协议");

    expect(clientMocks.api.post).not.toHaveBeenCalled();
  });
});
