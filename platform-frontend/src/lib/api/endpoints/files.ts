import { api } from "../client";
import type {
  Page,
  PageParams,
  FileVO,
  FileQueryParams,
  FileShareVO,
  TransactionVO,
  SharedFileVO,
  SaveShareFileRequest,
  FileDecryptInfoVO,
  CreateShareRequest,
  UpdateShareRequest,
  UserFileStatsVO,
  QuotaStatusVO,
  BatchDownloadMetricsReportRequest,
  ShareAccessLogVO,
  ShareAccessStatsVO,
  FileProvenanceVO,
  FileDownloadMetadataVO,
  FileDownloadMetadataTransport,
  FileDownloadPartTransport,
  FileDownloadPartVO,
  DownloadAccessIdentityTransport,
  DownloadAccessIdentityVO,
  DownloadKeyGrantVO,
  DownloadKeyMaterialVO,
} from "../types";

// Re-export types for use in other modules
export type { FileDecryptInfoVO, FileDownloadMetadataVO } from "../types";

const BASE = "/files";

const KEY_DELIVERY_PROTOCOL = "grant-v1";
const OWNER_DOWNLOAD_ACCESS_KINDS = new Set(["OWNER", "ADMIN", "FRIEND_SHARE"]);
const PUBLIC_DOWNLOAD_ACCESS_KINDS = new Set(["PUBLIC_SHARE"]);
const AUTHENTICATED_SHARE_ACCESS_KINDS = new Set(["AUTHENTICATED_SHARE"]);

/** 将 OpenAPI transport 字段收窄为非空字符串。 */
function requireDownloadText(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`下载 metadata 字段 ${field} 无效`);
  }
  return value;
}

/** 将 OpenAPI transport 字段收窄为安全整数。 */
function requireDownloadInteger(
  value: unknown,
  field: string,
  minimum = 0,
): number {
  if (!Number.isSafeInteger(value) || (value as number) < minimum) {
    throw new Error(`下载 metadata 字段 ${field} 无效`);
  }
  return value as number;
}

/** 校验并收窄单个生成的分片 transport。 */
function validateDownloadPartTransport(
  part: FileDownloadPartTransport,
): FileDownloadPartVO {
  return {
    ...part,
    index: requireDownloadInteger(part.index, "parts.index"),
    size: requireDownloadInteger(part.size, "parts.size", 1),
    downloadUrl: requireDownloadText(part.downloadUrl, "parts.downloadUrl"),
    expiresAtEpochSeconds: requireDownloadInteger(
      part.expiresAtEpochSeconds,
      "parts.expiresAtEpochSeconds",
      1,
    ),
    storagePath: requireDownloadText(part.storagePath, "parts.storagePath"),
    plainHash: requireDownloadText(part.plainHash, "parts.plainHash"),
    cipherHash: requireDownloadText(part.cipherHash, "parts.cipherHash"),
  };
}

/** 校验并收窄服务端生成的刷新身份栅栏。 */
function validateDownloadAccessIdentity(
  identity: DownloadAccessIdentityTransport | undefined,
): DownloadAccessIdentityVO {
  if (!identity) {
    throw new Error("下载 metadata 缺少 accessIdentity");
  }
  const identityHash = requireDownloadText(
    identity.identityHash,
    "accessIdentity.identityHash",
  );
  const manifestHash = requireDownloadText(
    identity.manifestHash,
    "accessIdentity.manifestHash",
  );
  if (!/^sha256:[0-9a-f]{64}$/u.test(identityHash)) {
    throw new Error("下载 metadata 的 accessIdentity.identityHash 无效");
  }
  if (
    identity.fileVersion !== null &&
    (!Number.isSafeInteger(identity.fileVersion) || identity.fileVersion <= 0)
  ) {
    throw new Error("下载 metadata 的 accessIdentity.fileVersion 无效");
  }
  return {
    ...identity,
    accessKind: requireDownloadText(
      identity.accessKind,
      "accessIdentity.accessKind",
    ),
    identityHash,
    manifestHash,
    algorithmSuite: requireDownloadText(
      identity.algorithmSuite,
      "accessIdentity.algorithmSuite",
    ),
  };
}

/**
 * 将生成的 OpenAPI transport 收窄为下载器可执行合同，拒绝缺失字段而不是使用类型断言。
 */
function validateDownloadMetadataTransport(
  transport: FileDownloadMetadataTransport,
  allowedAccessKinds: ReadonlySet<string>,
): FileDownloadMetadataVO {
  if (!Array.isArray(transport.parts)) {
    throw new Error("下载 metadata 缺少 parts");
  }
  const parts = transport.parts.map(validateDownloadPartTransport);
  const accessIdentity = validateDownloadAccessIdentity(
    transport.accessIdentity,
  );
  if (!allowedAccessKinds.has(accessIdentity.accessKind)) {
    throw new Error("下载 metadata 的 accessIdentity.accessKind 与端点不一致");
  }
  const manifestHash = requireDownloadText(
    transport.manifestHash,
    "manifestHash",
  );
  if (accessIdentity.manifestHash !== manifestHash) {
    throw new Error("下载 metadata 的 accessIdentity.manifestHash 不一致");
  }
  if (transport.keyGrant) {
    requireDownloadText(transport.keyGrant.reference, "keyGrant.reference");
    if (transport.keyGrant.protocol !== KEY_DELIVERY_PROTOCOL) {
      throw new Error("下载 metadata 的 keyGrant.protocol 无效");
    }
    requireDownloadText(transport.keyGrant.expiresAt, "keyGrant.expiresAt");
  }
  if (typeof transport.legacyDownloadAllowed !== "boolean") {
    throw new Error("下载 metadata 字段 legacyDownloadAllowed 无效");
  }
  const keyGrant: DownloadKeyGrantVO | null = transport.keyGrant
    ? {
        reference: requireDownloadText(
          transport.keyGrant.reference,
          "keyGrant.reference",
        ),
        protocol: KEY_DELIVERY_PROTOCOL,
        expiresAt: requireDownloadText(
          transport.keyGrant.expiresAt,
          "keyGrant.expiresAt",
        ),
      }
    : null;
  const { keyGrant: _transportKeyGrant, ...safeTransport } = transport;
  return {
    ...safeTransport,
    fileId: requireDownloadText(transport.fileId, "fileId"),
    fileHash: requireDownloadText(transport.fileHash, "fileHash"),
    fileName: requireDownloadText(transport.fileName, "fileName"),
    fileSize: requireDownloadInteger(transport.fileSize, "fileSize"),
    contentType: requireDownloadText(transport.contentType, "contentType"),
    manifestSchemaId: requireDownloadText(
      transport.manifestSchemaId,
      "manifestSchemaId",
    ),
    manifestHash,
    canonicalManifestJson: requireDownloadText(
      transport.canonicalManifestJson,
      "canonicalManifestJson",
    ),
    manifestStatus: requireDownloadText(
      transport.manifestStatus,
      "manifestStatus",
    ),
    manifestClassification: requireDownloadText(
      transport.manifestClassification,
      "manifestClassification",
    ),
    legacyDownloadAllowed: transport.legacyDownloadAllowed,
    hashAlgorithm: requireDownloadText(
      transport.hashAlgorithm,
      "hashAlgorithm",
    ),
    storageBackend: requireDownloadText(
      transport.storageBackend,
      "storageBackend",
    ),
    chunkSize: requireDownloadInteger(transport.chunkSize, "chunkSize", 1),
    totalChunks: requireDownloadInteger(
      transport.totalChunks,
      "totalChunks",
      1,
    ),
    parts,
    accessIdentity,
    ...(keyGrant ? { keyGrant } : {}),
  };
}

/**
 * 创建仅保存在当前下载执行作用域内的随机会话标识。
 */
export function createDownloadSessionId(): string {
  const bytes = new Uint8Array(24);
  globalThis.crypto.getRandomValues(bytes);
  try {
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary)
      .replaceAll("+", "-")
      .replaceAll("/", "_")
      .replace(/=+$/u, "");
  } finally {
    bytes.fill(0);
  }
}

/**
 * 通过 POST 请求体即时消费 grant，reference 与密钥均不进入 URL。
 */
export async function consumeDownloadKeyGrant(
  grant: DownloadKeyGrantVO,
  sessionId: string,
  publicAccess = false,
): Promise<DownloadKeyMaterialVO> {
  if (grant.protocol !== KEY_DELIVERY_PROTOCOL) {
    throw new Error("不支持的下载密钥授权协议");
  }
  const path = publicAccess
    ? "/public/key-grants/consume"
    : `${BASE}/key-grants/consume`;
  return api.post<DownloadKeyMaterialVO>(
    path,
    { grantReference: grant.reference, sessionId },
    publicAccess
      ? { skipAuth: true, skipTenant: true, retries: 1 }
      : { retries: 1 },
  );
}

/**
 * 即时解析 grant-v1 或受控 plaintext-v0 响应中的密钥。
 */
async function resolveInitialKey(
  decryptInfo: FileDecryptInfoVO,
  sessionId: string,
  publicAccess = false,
): Promise<string> {
  if (decryptInfo.keyGrant) {
    const keyMaterial = await consumeDownloadKeyGrant(
      decryptInfo.keyGrant,
      sessionId,
      publicAccess,
    );
    if (!keyMaterial.initialKey) {
      throw new Error("下载密钥授权未返回可用密钥");
    }
    if (keyMaterial.protocol !== decryptInfo.keyGrant.protocol) {
      throw new Error("下载密钥授权协议不一致");
    }
    return keyMaterial.initialKey;
  }
  if (decryptInfo.initialKey) {
    return decryptInfo.initialKey;
  }
  throw new Error("缺少加密文件下载密钥授权");
}

/**
 * 将未加密分片按服务端顺序合并，避免 NONE 文件被错误地要求提供 grant。
 */
function concatenatePlainChunks(chunks: Uint8Array[]): Uint8Array {
  if (chunks.length === 0) {
    throw new Error("没有分片数据");
  }
  const totalLength = chunks.reduce((sum, chunk) => {
    const next = sum + chunk.byteLength;
    if (!Number.isSafeInteger(next)) {
      throw new Error("文件分片总长度超出安全范围");
    }
    return next;
  }, 0);
  const result = new Uint8Array(totalLength);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

/**
 * 在分片下载完成后即时消费加密 grant；NONE 文件则直接合并原始分片。
 */
async function materializeDownloadedChunks(
  chunks: Uint8Array[],
  decryptInfo: FileDecryptInfoVO,
  sessionId: string,
  publicAccess = false,
): Promise<Blob> {
  const { decryptFile, arrayToBlob } = await import("$utils/crypto");
  if (!decryptInfo.keyGrant && !decryptInfo.initialKey) {
    return arrayToBlob(concatenatePlainChunks(chunks), decryptInfo.contentType);
  }

  const initialKey = await resolveInitialKey(
    decryptInfo,
    sessionId,
    publicAccess,
  );
  const decryptedData = await decryptFile(chunks, initialKey);
  return arrayToBlob(decryptedData, decryptInfo.contentType);
}

/**
 * 获取文件列表（分页）。
 *
 * @param params 查询参数
 * @returns 文件分页
 */
export async function getFiles(
  params?: PageParams & FileQueryParams,
): Promise<Page<FileVO>> {
  return api.get<Page<FileVO>>(BASE, { params });
}

/**
 * 获取用户文件统计信息（用于 Dashboard）。
 *
 * @returns 统计信息
 */
export async function getUserFileStats(): Promise<UserFileStatsVO> {
  return api.get<UserFileStatsVO>(`${BASE}/stats`);
}

/**
 * 获取当前配额状态。
 *
 * @returns 配额状态
 */
export async function getQuotaStatus(): Promise<QuotaStatusVO> {
  return api.get<QuotaStatusVO>(`${BASE}/quota`);
}

/**
 * 获取单个文件信息。
 *
 * @param id 文件 ID
 * @returns 文件详情
 */
export async function getFile(id: string): Promise<FileVO> {
  return api.get<FileVO>(`${BASE}/${id}`);
}

/**
 * 通过哈希获取文件信息。
 *
 * @param hash 文件哈希
 * @returns 文件详情
 */
export async function getFileByHash(hash: string): Promise<FileVO> {
  return api.get<FileVO>(`${BASE}/hash/${hash}`);
}

/**
 * 删除文件。
 *
 * @param fileHashOrId 文件哈希或文件 ID
 */
export async function deleteFile(fileHashOrId: string): Promise<void> {
  await api.delete(BASE, {
    params: { identifiers: [fileHashOrId] },
  });
}

/**
 * 下载文件（获取加密分片）。
 *
 * @param fileHash 文件哈希
 * @returns 加密分片 Base64 数组
 */
export async function downloadEncryptedChunks(
  fileHash: string,
): Promise<string[]> {
  return api.get<string[]>(`${BASE}/hash/${fileHash}/chunks`);
}

/**
 * 获取文件解密信息。
 *
 * @param fileHash 文件哈希
 * @returns 解密信息
 */
export async function getDecryptInfo(
  fileHash: string,
  sessionId: string,
): Promise<FileDecryptInfoVO> {
  return api.get<FileDecryptInfoVO>(`${BASE}/hash/${fileHash}/decrypt-info`, {
    headers: {
      "X-Key-Delivery-Protocol": KEY_DELIVERY_PROTOCOL,
      "X-Download-Session-ID": sessionId,
    },
  });
}

/**
 * 创建文件分享。
 *
 * @param payload 分享参数
 * @returns 分享码
 */
export async function createShare(
  payload: CreateShareRequest,
): Promise<string> {
  return api.post<string>("/shares", payload);
}

/**
 * 更新分享设置（类型/有效期）。
 *
 * @param payload 更新参数
 */
export async function updateShare(payload: UpdateShareRequest): Promise<void> {
  if (!payload.shareCode) {
    throw new Error("shareCode 不能为空");
  }
  await api.patch(`/shares/${payload.shareCode}`, payload);
}

/**
 * 获取分享文件列表。
 *
 * @param sharingCode 分享码
 * @returns 分享文件列表
 */
export async function getSharedFiles(
  sharingCode: string,
): Promise<SharedFileVO[]> {
  return api.get<SharedFileVO[]>(`/shares/${sharingCode}/files`, {
    skipAuth: true,
    skipTenant: true,
  });
}

/**
 * 取消分享。
 *
 * @param shareCode 分享码
 */
export async function cancelShare(shareCode: string): Promise<void> {
  await api.delete(`${BASE}/share/${shareCode}`);
}

/**
 * 获取我的分享列表。
 *
 * @param params 分页参数
 * @returns 分享分页
 */
export async function getMyShares(
  params?: PageParams,
): Promise<Page<FileShareVO>> {
  return api.get<Page<FileShareVO>>(`${BASE}/shares`, { params });
}

/**
 * 获取文件下载地址（预签名 URL 列表）。
 *
 * @param fileHash 文件哈希
 * @returns 地址列表
 */
export async function getDownloadAddress(fileHash: string): Promise<string[]> {
  return api.get<string[]>(`${BASE}/hash/${fileHash}/addresses`);
}

/**
 * 获取文件预签名分片下载元数据。
 *
 * @param fileHash 文件哈希
 * @returns manifest、解密信息和有序分片下载 URL
 */
export async function getDownloadMetadata(
  fileHash: string,
  sessionId: string,
): Promise<FileDownloadMetadataVO> {
  const transport = await api.get<FileDownloadMetadataTransport>(
    `${BASE}/hash/${fileHash}/download-metadata`,
    {
      headers: {
        "X-Key-Delivery-Protocol": KEY_DELIVERY_PROTOCOL,
        "X-Download-Session-ID": sessionId,
      },
    },
  );
  return validateDownloadMetadataTransport(
    transport,
    OWNER_DOWNLOAD_ACCESS_KINDS,
  );
}

/** 获取公开分享的 manifest 驱动下载元数据。 */
export async function getPublicShareDownloadMetadata(
  shareCode: string,
  fileHash: string,
  sessionId: string,
): Promise<FileDownloadMetadataVO> {
  const transport = await api.get<FileDownloadMetadataTransport>(
    `/public/shares/${shareCode}/files/${fileHash}/download-metadata`,
    {
      skipAuth: true,
      skipTenant: true,
      headers: {
        "X-Key-Delivery-Protocol": KEY_DELIVERY_PROTOCOL,
        "X-Download-Session-ID": sessionId,
      },
    },
  );
  return validateDownloadMetadataTransport(
    transport,
    PUBLIC_DOWNLOAD_ACCESS_KINDS,
  );
}

/** 获取认证分享的 manifest 驱动下载元数据。 */
export async function getAuthenticatedShareDownloadMetadata(
  shareCode: string,
  fileHash: string,
  sessionId: string,
): Promise<FileDownloadMetadataVO> {
  const transport = await api.get<FileDownloadMetadataTransport>(
    `/shares/${shareCode}/files/${fileHash}/download-metadata`,
    {
      headers: {
        "X-Key-Delivery-Protocol": KEY_DELIVERY_PROTOCOL,
        "X-Download-Session-ID": sessionId,
      },
    },
  );
  return validateDownloadMetadataTransport(
    transport,
    AUTHENTICATED_SHARE_ACCESS_KINDS,
  );
}

/**
 * 上报批量下载质量指标。
 *
 * @param payload 批次质量聚合指标。
 * @returns 后端固定响应 `ok`。
 */
export async function reportBatchDownloadMetrics(
  payload: BatchDownloadMetricsReportRequest,
): Promise<string> {
  return api.post<string>(`${BASE}/download-batches/report`, payload);
}

/**
 * 获取区块链交易记录。
 *
 * @param transactionHash 交易哈希
 * @returns 交易记录
 */
export async function getTransaction(
  transactionHash: string,
): Promise<TransactionVO> {
  return api.get<TransactionVO>(`/transactions/${transactionHash}`);
}

/**
 * 保存分享文件到我的账户。
 *
 * @param request 保存参数
 */
export async function saveSharedFiles(
  request: SaveShareFileRequest,
): Promise<void> {
  await api.post(`/shares/${request.shareCode}/files/save`, request);
}

/**
 * 下载并解密文件。
 *
 * @param fileHash 文件哈希
 * @returns 解密后的 Blob
 */
export async function downloadFile(fileHash: string): Promise<Blob> {
  const chunksBase64 = await downloadEncryptedChunks(fileHash);
  const sessionId = createDownloadSessionId();
  const decryptInfo = await getDecryptInfo(fileHash, sessionId);

  const chunks = chunksBase64.map((base64) =>
    Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)),
  );

  return materializeDownloadedChunks(chunks, decryptInfo, sessionId);
}

// ==================== 审计端点 ====================

/**
 * 获取分享访问日志。
 *
 * @param shareCode 分享码
 * @param params 分页参数
 * @returns 访问日志分页
 */
export async function getShareAccessLogs(
  shareCode: string,
  params?: PageParams,
): Promise<Page<ShareAccessLogVO>> {
  return api.get<Page<ShareAccessLogVO>>(
    `${BASE}/share/${shareCode}/access-logs`,
    {
      params,
    },
  );
}

/**
 * 获取分享访问统计。
 *
 * @param shareCode 分享码
 * @returns 统计数据
 */
export async function getShareAccessStats(
  shareCode: string,
): Promise<ShareAccessStatsVO> {
  return api.get<ShareAccessStatsVO>(`${BASE}/share/${shareCode}/stats`);
}

/**
 * 获取文件溯源信息。
 *
 * @param fileId 文件 ID
 * @returns 溯源信息
 */
export async function getFileProvenance(
  fileId: string,
): Promise<FileProvenanceVO> {
  return api.get<FileProvenanceVO>(`${BASE}/${fileId}/provenance`);
}
