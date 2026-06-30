/**
 * Download Manager Store
 * Manages file download tasks with presigned URL direct S3 access
 * Supports concurrent downloads, progress tracking, resumable downloads, and streaming for large files
 */

import { browser } from "$app/environment";
import { env } from "$env/dynamic/public";
import { getToken } from "$api/client";
import * as fileApi from "$api/endpoints/files";
import {
  downloadAllChunks,
  type ChunkDownloadResult,
  type DownloadProgress,
} from "$utils/chunkDownloader";
import {
  saveTask,
  saveChunk,
  getChunks,
  getPendingTasks,
  clearTaskData,
  clearAllDownloadData,
  cleanupExpiredData,
  type PersistedDownloadTask,
  type DownloadSource,
} from "$utils/downloadStorage";
import { decryptFile, arrayToBlob, downloadBlob } from "$utils/crypto";
import {
  buildBatchMetricsPayload,
  calculateRetryCount,
} from "$utils/downloadBatchMetrics";
import {
  performPreDownloadCheck,
  type DownloadStrategy,
  type DownloadDecision,
  type BrowserCapabilities,
  formatFileSize,
  isStreamingSupported,
} from "$utils/fileSize";
import {
  executeBufferedStreamingDownload,
  type StreamingPhase,
} from "$utils/streamingDownloader";

// Re-export types
export type { DownloadSource } from "$utils/downloadStorage";
export type {
  DownloadStrategy,
  DownloadDecision,
  BrowserCapabilities,
} from "$utils/fileSize";

// ===== Types =====

export type DownloadStatus =
  | "pending"
  | "fetching_urls"
  | "downloading"
  | "streaming"
  | "paused"
  | "decrypting"
  | "writing"
  | "completed"
  | "failed"
  | "cancelled";

export interface ChunkState {
  index: number;
  status: "pending" | "downloading" | "completed" | "failed";
  retryCount: number;
  error?: string;
}

export interface DownloadTask {
  id: string;
  fileHash: string;
  fileName: string;
  fileSize: number;
  contentType: string;
  status: DownloadStatus;
  error: string | null;
  totalChunks: number;
  downloadedChunks: number;
  progress: number;
  presignedUrls: string[];
  urlsFetchedAt: number | null;
  chunks: ChunkState[];
  initialKey: string | null;
  encryptionAlgorithm: string | null;
  source: DownloadSource;
  createdAt: number;
  startedAt: number | null;
  completedAt: number | null;
  abortController: AbortController | null;
  /** Download strategy used for this task */
  strategy: DownloadStrategy;
}

export interface BatchDownloadItem {
  fileHash: string;
  fileName: string;
  fileSize?: number;
  source?: DownloadSource;
}

export interface BatchDownloadFailure extends BatchDownloadItem {
  reason: string;
  attempts: number;
}

export type BatchDownloadStatus = "idle" | "running" | "completed";

export interface BatchDownloadState {
  id: string;
  status: BatchDownloadStatus;
  total: number;
  completedCount: number;
  activeCount: number;
  successCount: number;
  failedCount: number;
  failures: BatchDownloadFailure[];
  startedAt: number;
  completedAt: number | null;
}

type WritableFileStreamLike = {
  write(data: Blob | BufferSource | string): Promise<void>;
  close(): Promise<void>;
  abort?: () => Promise<void>;
};

type WritableFileHandleLike = {
  createWritable(): Promise<WritableFileStreamLike>;
};

type PresignedUrlMetadata = {
  urls: string[];
  decryptInfo: fileApi.FileDecryptInfoVO;
  encryptionAlgorithm: string | null;
};

// ===== Pre-download Check Result =====

export interface PreDownloadCheckResult {
  canProceed: boolean;
  decision: DownloadDecision;
  capabilities: BrowserCapabilities;
  formattedSize: string;
}

// ===== Configuration =====

const DEFAULT_CONCURRENCY = 3;
const DEFAULT_BATCH_CONCURRENCY = 3;
const MAX_BATCH_FILES = 100;
const DEFAULT_BATCH_RETRIES = 2;
const URL_EXPIRY_BUFFER_MS = 60 * 60 * 1000; // 1 hour buffer before 24h expiry
const PRESIGNED_URL_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours

// ===== State =====

let tasks = $state<DownloadTask[]>([]);
let concurrency = $state(DEFAULT_CONCURRENCY);
let initialized = $state(false);
let batchState = $state<BatchDownloadState | null>(null);

// Track downloaded chunks in memory for active downloads
const downloadedChunksMap = new Map<string, Map<number, Uint8Array>>();

// ===== Derived =====

const pendingTasks = $derived(tasks.filter((t) => t.status === "pending"));
const activeTasks = $derived(
  tasks.filter(
    (t) =>
      t.status === "downloading" ||
      t.status === "fetching_urls" ||
      t.status === "streaming" ||
      t.status === "writing",
  ),
);
const streamingTasks = $derived(
  tasks.filter((t) => t.status === "streaming" || t.status === "writing"),
);
const pausedTasks = $derived(tasks.filter((t) => t.status === "paused"));
const completedTasks = $derived(tasks.filter((t) => t.status === "completed"));
const failedTasks = $derived(tasks.filter((t) => t.status === "failed"));

// ===== Internal Helpers =====

function generateId(): string {
  return `download-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

function updateTask(id: string, updates: Partial<DownloadTask>): void {
  tasks = tasks.map((t) => (t.id === id ? { ...t, ...updates } : t));
}

function getTask(id: string): DownloadTask | undefined {
  return tasks.find((t) => t.id === id);
}

function areUrlsExpired(urlsFetchedAt: number | null): boolean {
  if (!urlsFetchedAt) return true;
  const age = Date.now() - urlsFetchedAt;
  return age > PRESIGNED_URL_TTL_MS - URL_EXPIRY_BUFFER_MS;
}

/**
 * 判断下载元数据是否声明对象分片未经过前端加密。
 */
function isPlainDownload(
  encryptionAlgorithm: string | null | undefined,
): boolean {
  return encryptionAlgorithm?.trim().toUpperCase() === "NONE";
}

/**
 * 读取加密下载必需的 initialKey；缺失时立即失败，避免把 null 传给解密器。
 */
function requireInitialKey(initialKey: string | null | undefined): string {
  if (!initialKey) {
    throw new Error("缺少加密文件初始密钥");
  }
  return initialKey;
}

/**
 * 将未加密分片按顺序拼接成单个字节数组。
 */
function concatenatePlainChunks(chunks: Uint8Array[]): Uint8Array {
  const totalLength = chunks.reduce((sum, chunk) => sum + chunk.byteLength, 0);
  const result = new Uint8Array(totalLength);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

/**
 * 计算当前登录上下文的不可逆摘要，用于隔离同源浏览器中的下载恢复记录。
 *
 * @returns 当前 token 与租户绑定的 SHA-256 摘要；不可用时返回 null。
 */
async function getAuthContextHash(): Promise<string | null> {
  if (!browser || !globalThis.crypto?.subtle) return null;

  const token = getToken();
  if (!token) return null;

  const tenantId = env.PUBLIC_TENANT_ID || "0";
  const digest = await globalThis.crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(`${tenantId}:${token}`),
  );
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * 生成批量下载批次 ID。
 *
 * @returns 批次唯一标识。
 */
function generateBatchId(): string {
  return `batch-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

/**
 * 休眠指定时长，用于批量重试退避。
 *
 * @param ms 休眠毫秒数。
 */
async function sleep(ms: number): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 获取批量任务最终失败原因，优先返回任务错误信息。
 *
 * @param taskId 下载任务 ID。
 * @returns 失败原因文本。
 */
function getTaskFailureReason(taskId: string): string {
  const task = getTask(taskId);
  if (!task) return "任务不存在";
  return task.error ?? "下载失败";
}

/**
 * 等待下载任务进入终态（completed/failed/cancelled）。
 *
 * @param taskId 下载任务 ID。
 * @returns 终态任务快照，不存在时返回 undefined。
 */
async function waitForTaskTerminal(
  taskId: string,
): Promise<DownloadTask | undefined> {
  while (true) {
    const task = getTask(taskId);
    if (!task) return undefined;
    if (
      task.status === "completed" ||
      task.status === "failed" ||
      task.status === "cancelled" ||
      task.status === "paused"
    ) {
      return task;
    }
    await sleep(120);
  }
}

/**
 * 更新当前批次进度快照。
 *
 * @param activeCount 当前活跃任务数。
 * @param completedCount 已完成任务数。
 * @param successCount 成功任务数。
 * @param failures 失败任务列表。
 */
function updateBatchProgress(
  activeCount: number,
  completedCount: number,
  successCount: number,
  failures: BatchDownloadFailure[],
): void {
  if (!batchState) return;
  batchState = {
    ...batchState,
    activeCount,
    completedCount,
    successCount,
    failedCount: failures.length,
    failures: [...failures],
  };
}

/**
 * 异步上报批量下载指标，失败仅记录告警，不影响主下载流程。
 *
 * @param snapshot 批次完成快照。
 * @param retryCount 累计重试次数。
 */
async function reportBatchMetricsInBackground(
  snapshot: BatchDownloadState,
  retryCount: number,
): Promise<void> {
  try {
    const payload = buildBatchMetricsPayload(snapshot, retryCount);
    await fileApi.reportBatchDownloadMetrics(payload);
  } catch (error) {
    console.warn("[download-batch-metrics] report failed", error);
  }
}

/**
 * 在批量下载中执行单文件下载，并按策略进行自动重试。
 *
 * @param item 批量项。
 * @param retryTimes 最大重试次数（不含首次尝试）。
 * @returns 结果对象（成功或失败原因）。
 */
async function executeBatchItem(
  item: BatchDownloadItem,
  retryTimes: number,
): Promise<
  | { success: true; attempts: number }
  | { success: false; reason: string; attempts: number }
> {
  let taskId: string | null = null;
  let attempts = 0;

  while (attempts <= retryTimes) {
    attempts++;
    try {
      if (!taskId) {
        taskId = await startDownload(
          item.fileHash,
          item.fileName,
          item.source ?? { type: "owned" },
          item.fileSize,
          "inmemory",
        );
      } else {
        await retryDownload(taskId);
      }

      const terminalTask = await waitForTaskTerminal(taskId);
      if (terminalTask?.status === "completed") {
        return { success: true, attempts };
      }
      if (terminalTask?.status === "paused") {
        return {
          success: false,
          reason: terminalTask.error ?? "下载已暂停",
          attempts,
        };
      }
    } catch (error) {
      if (attempts > retryTimes) {
        const reason = (error as Error).message || "下载失败";
        return { success: false, reason, attempts };
      }
    }

    if (attempts <= retryTimes) {
      const backoff = 500 * 2 ** (attempts - 1);
      await sleep(backoff);
    }
  }

  const fallbackReason = taskId ? getTaskFailureReason(taskId) : "下载失败";
  return { success: false, reason: fallbackReason, attempts };
}

// ===== Core Download Logic =====

async function fetchPresignedUrls(
  task: DownloadTask,
): Promise<PresignedUrlMetadata> {
  if (task.source.type === "owned") {
    const metadata = await fileApi.getDownloadMetadata(task.fileHash);
    const orderedParts = [...metadata.parts].sort((a, b) => a.index - b.index);
    const urls = orderedParts.map((part) => part.downloadUrl);
    const decryptInfo: fileApi.FileDecryptInfoVO = {
      initialKey: metadata.initialKey,
      fileName: metadata.fileName,
      fileSize: metadata.fileSize,
      contentType: metadata.contentType,
      chunkCount: metadata.totalChunks,
      fileHash: metadata.fileHash,
    };
    return {
      urls,
      decryptInfo,
      encryptionAlgorithm: metadata.encryptionAlgorithm ?? null,
    };
  }

  // For shared files, we don't have presigned URL endpoint yet
  // This will throw if not implemented
  throw new Error(
    "Presigned URLs not available for shared files. Use fallback download.",
  );
}

async function executeDownload(task: DownloadTask): Promise<void> {
  const taskId = task.id;
  const abortController = new AbortController();
  updateTask(taskId, { abortController });

  try {
    // Step 1: Fetch presigned URLs if needed
    let urls = task.presignedUrls;
    let initialKey = task.initialKey;
    let totalChunks = task.totalChunks;
    let contentType = task.contentType;
    let fileName = task.fileName;
    let fileSize = task.fileSize;
    let encryptionAlgorithm = task.encryptionAlgorithm;
    const authContextHash = await getAuthContextHash();

    if (urls.length === 0 || areUrlsExpired(task.urlsFetchedAt)) {
      updateTask(taskId, { status: "fetching_urls" });

      const {
        urls: newUrls,
        decryptInfo,
        encryptionAlgorithm: metadataEncryptionAlgorithm,
      } = await fetchPresignedUrls(task);
      urls = newUrls;
      initialKey = decryptInfo.initialKey ?? null;
      totalChunks = decryptInfo.chunkCount;
      contentType = decryptInfo.contentType;
      fileName = decryptInfo.fileName;
      fileSize = decryptInfo.fileSize;
      encryptionAlgorithm = metadataEncryptionAlgorithm;

      updateTask(taskId, {
        presignedUrls: urls,
        urlsFetchedAt: Date.now(),
        initialKey,
        encryptionAlgorithm,
        totalChunks,
        contentType,
        fileName,
        fileSize,
        chunks: Array.from({ length: totalChunks }, (_, i) => ({
          index: i,
          status: "pending" as const,
          retryCount: 0,
        })),
      });

      // Persist task metadata
      if (authContextHash) {
        const persistedTask: PersistedDownloadTask = {
          id: taskId,
          fileHash: task.fileHash,
          fileName,
          fileSize,
          contentType,
          totalChunks,
          source: task.source,
          authContextHash,
          createdAt: task.createdAt,
        };
        await saveTask(persistedTask);
      }
    }

    // Step 2: Load existing chunks from IndexedDB (for resume)
    let existingChunks = downloadedChunksMap.get(taskId);
    if (!existingChunks) {
      existingChunks = authContextHash ? await getChunks(taskId) : new Map();
      downloadedChunksMap.set(taskId, existingChunks);
    }

    // Update status
    updateTask(taskId, {
      status: "downloading",
      startedAt: task.startedAt ?? Date.now(),
      downloadedChunks: existingChunks.size,
      progress: Math.round((existingChunks.size / totalChunks) * 100),
    });

    // Step 3: Download chunks
    const allChunks = await downloadAllChunks(urls, {
      concurrency,
      signal: abortController.signal,
      existingChunks,
      onChunkComplete: async (result: ChunkDownloadResult) => {
        // Update memory cache
        existingChunks!.set(result.index, result.data);

        // Persist to IndexedDB
        if (authContextHash) {
          await saveChunk(taskId, result.index, result.data);
        }

        // Update chunk state
        const currentTask = getTask(taskId);
        if (currentTask) {
          const chunks = [...currentTask.chunks];
          chunks[result.index] = {
            ...chunks[result.index],
            status: "completed",
          };
          updateTask(taskId, { chunks });
        }
      },
      onProgress: (progress: DownloadProgress) => {
        updateTask(taskId, {
          downloadedChunks: progress.completed,
          progress: Math.round((progress.completed / progress.total) * 100),
        });
      },
    });

    // Check if cancelled during download
    const currentTask = getTask(taskId);
    if (
      !currentTask ||
      currentTask.status === "cancelled" ||
      currentTask.status === "paused"
    ) {
      return;
    }

    // Step 4: Build the output bytes
    const plainDownload = isPlainDownload(encryptionAlgorithm);
    updateTask(taskId, { status: plainDownload ? "writing" : "decrypting" });

    const outputData = plainDownload
      ? concatenatePlainChunks(allChunks)
      : await decryptFile(allChunks, requireInitialKey(initialKey));
    const blob = arrayToBlob(outputData, contentType);

    // Step 5: Trigger browser download
    downloadBlob(blob, fileName);

    // Step 6: Cleanup and mark complete
    await clearTaskData(taskId);
    downloadedChunksMap.delete(taskId);

    updateTask(taskId, {
      status: "completed",
      progress: 100,
      completedAt: Date.now(),
      abortController: null,
    });
  } catch (error) {
    const err = error as Error;

    // Check if cancelled
    if (
      abortController.signal.aborted ||
      err.message === "Download cancelled"
    ) {
      const currentTask = getTask(taskId);
      if (currentTask?.status !== "paused") {
        updateTask(taskId, {
          status: "cancelled",
          abortController: null,
        });
      }
      return;
    }

    updateTask(taskId, {
      status: "failed",
      error: err.message,
      abortController: null,
    });
  }
}

// ===== Streaming Download (File System Access API) =====

/**
 * 流式下载未加密分片并直接写入用户选择的文件。
 */
async function executePlainStreamingDownload(params: {
  contentType: string;
  totalChunks: number;
  presignedUrls: string[];
  fileHandle: unknown;
  signal: AbortSignal;
  onProgress: (phase: StreamingPhase, current: number, total: number) => void;
}): Promise<{ success: boolean; bytesWritten?: number; error?: string }> {
  const fileHandle = params.fileHandle as Partial<WritableFileHandleLike>;
  if (typeof fileHandle?.createWritable !== "function") {
    return {
      success: false,
      error: "Streaming download file handle is invalid",
    };
  }

  let writable: WritableFileStreamLike | null = null;
  try {
    writable = await fileHandle.createWritable();
    let bytesWritten = 0;
    for (let index = 0; index < params.presignedUrls.length; index++) {
      if (params.signal.aborted) {
        throw new Error("Download cancelled");
      }
      const response = await fetch(params.presignedUrls[index], {
        signal: params.signal,
      });
      if (!response.ok) {
        throw new Error(`分片 ${index + 1} 下载失败: ${response.status}`);
      }
      const chunk = new Uint8Array(await response.arrayBuffer());
      if (params.signal.aborted) {
        throw new Error("Download cancelled");
      }
      await writable.write(new Blob([chunk], { type: params.contentType }));
      bytesWritten += chunk.byteLength;
      params.onProgress("downloading", index + 1, params.totalChunks);
    }
    await writable.close();
    params.onProgress("completed", params.totalChunks, params.totalChunks);
    return { success: true, bytesWritten };
  } catch (error) {
    if (writable?.abort) {
      await writable.abort();
    }
    if (params.signal.aborted) {
      return { success: false, error: "Download cancelled" };
    }
    return { success: false, error: (error as Error).message };
  }
}

/**
 * Execute streaming download for large files
 * Uses File System Access API to write directly to disk
 */
async function executeStreamingDownload(task: DownloadTask): Promise<void> {
  const taskId = task.id;
  const abortController = new AbortController();
  updateTask(taskId, { abortController });

  let fileHandle: unknown;

  try {
    // Step 0: Prompt save picker immediately (preserve user activation)
    const showSaveFilePicker = (
      window as unknown as {
        showSaveFilePicker?: (options?: {
          suggestedName?: string;
        }) => Promise<unknown>;
      }
    ).showSaveFilePicker;

    if (!showSaveFilePicker) {
      throw new Error("Streaming download not supported in this browser");
    }

    fileHandle = await showSaveFilePicker({
      suggestedName: task.fileName,
    });

    // If paused/cancelled while picker was open, stop here
    if (abortController.signal.aborted) {
      const currentTask = getTask(taskId);
      if (currentTask?.status !== "paused") {
        updateTask(taskId, {
          status: "cancelled",
          abortController: null,
        });
      }
      return;
    }

    // Step 1: Fetch presigned URLs if needed
    let urls = task.presignedUrls;
    let initialKey = task.initialKey;
    let totalChunks = task.totalChunks;
    let contentType = task.contentType;
    let fileName = task.fileName;
    let fileSize = task.fileSize;
    let encryptionAlgorithm = task.encryptionAlgorithm;

    if (urls.length === 0 || areUrlsExpired(task.urlsFetchedAt)) {
      updateTask(taskId, { status: "fetching_urls" });

      const {
        urls: newUrls,
        decryptInfo,
        encryptionAlgorithm: metadataEncryptionAlgorithm,
      } = await fetchPresignedUrls(task);
      urls = newUrls;
      initialKey = decryptInfo.initialKey ?? null;
      totalChunks = decryptInfo.chunkCount;
      contentType = decryptInfo.contentType;
      fileName = decryptInfo.fileName;
      fileSize = decryptInfo.fileSize;
      encryptionAlgorithm = metadataEncryptionAlgorithm;

      updateTask(taskId, {
        presignedUrls: urls,
        urlsFetchedAt: Date.now(),
        initialKey,
        encryptionAlgorithm,
        totalChunks,
        contentType,
        fileName,
        fileSize,
        chunks: Array.from({ length: totalChunks }, (_, i) => ({
          index: i,
          status: "pending" as const,
          retryCount: 0,
        })),
      });
    }

    // Step 2: Execute streaming download
    updateTask(taskId, {
      status: "streaming",
      startedAt: task.startedAt ?? Date.now(),
    });

    const onStreamingProgress = (
      phase: StreamingPhase,
      current: number,
      total: number,
    ) => {
      const currentTask = getTask(taskId);
      if (
        !currentTask ||
        currentTask.status === "cancelled" ||
        currentTask.status === "paused"
      ) {
        return;
      }

      const progress = Math.round((current / total) * 100);

      switch (phase) {
        case "downloading":
          updateTask(taskId, {
            status: "streaming",
            downloadedChunks: current,
            progress,
          });
          break;
        case "decrypting":
          updateTask(taskId, {
            status: "decrypting",
            progress,
          });
          break;
        case "writing":
          updateTask(taskId, {
            status: "writing",
            progress,
          });
          break;
        case "completed":
          break;
      }
    };

    const result = isPlainDownload(encryptionAlgorithm)
      ? await executePlainStreamingDownload({
          contentType,
          totalChunks,
          presignedUrls: urls,
          fileHandle,
          signal: abortController.signal,
          onProgress: onStreamingProgress,
        })
      : await executeBufferedStreamingDownload({
          fileName,
          contentType,
          totalChunks,
          initialKey: requireInitialKey(initialKey),
          presignedUrls: urls,
          fileHandle,
          signal: abortController.signal,
          onProgress: onStreamingProgress,
        });

    // Check result
    if (!result.success) {
      if (result.error === "Download cancelled") {
        const currentTask = getTask(taskId);
        if (currentTask?.status !== "paused") {
          updateTask(taskId, {
            status: "cancelled",
            abortController: null,
          });
        }
        return;
      }

      if (result.error === "File save cancelled by user") {
        updateTask(taskId, {
          status: "cancelled",
          abortController: null,
        });
        return;
      }

      throw new Error(result.error);
    }

    // Success
    updateTask(taskId, {
      status: "completed",
      progress: 100,
      completedAt: Date.now(),
      abortController: null,
    });
  } catch (error) {
    const err = error as Error;

    if (
      abortController.signal.aborted ||
      err.message === "Download cancelled"
    ) {
      const currentTask = getTask(taskId);
      if (currentTask?.status !== "paused") {
        updateTask(taskId, {
          status: "cancelled",
          abortController: null,
        });
      }
      return;
    }

    // User cancelled the save picker
    if (err.name === "AbortError") {
      updateTask(taskId, {
        status: "cancelled",
        abortController: null,
      });
      return;
    }

    updateTask(taskId, {
      status: "failed",
      error: err.message,
      abortController: null,
    });
  }
}

// ===== Fallback for shared files (backend proxy) =====

async function executeBackendProxyDownload(task: DownloadTask): Promise<void> {
  const taskId = task.id;

  try {
    updateTask(taskId, { status: "downloading", startedAt: Date.now() });

    let blob: Blob;
    const source = task.source;

    if (source.type === "public_share" && source.shareCode) {
      blob = await fileApi.publicDownloadFile(source.shareCode, task.fileHash);
    } else if (source.type === "private_share" && source.shareCode) {
      blob = await fileApi.shareDownloadFile(source.shareCode, task.fileHash);
    } else {
      // Fallback for owned files without presigned URLs
      blob = await fileApi.downloadFile(task.fileHash);
    }

    downloadBlob(blob, task.fileName);

    updateTask(taskId, {
      status: "completed",
      progress: 100,
      completedAt: Date.now(),
    });
  } catch (error) {
    updateTask(taskId, {
      status: "failed",
      error: (error as Error).message,
    });
  }
}

// ===== Actions =====

/**
 * Check file size and get download strategy recommendation
 * Call this before starting a large file download to warn the user
 */
function checkFileSize(fileSizeBytes: number): PreDownloadCheckResult {
  const check = performPreDownloadCheck(fileSizeBytes);
  return {
    ...check,
    formattedSize: formatFileSize(fileSizeBytes),
  };
}

/**
 * Check if streaming download is available in this browser
 */
function canUseStreaming(): boolean {
  return isStreamingSupported();
}

/**
 * Start a new download task
 * @param fileHash File hash identifier
 * @param fileName Display name for the file
 * @param source Download source (owned, public_share, private_share)
 * @param fileSize Optional file size for strategy decision (if known)
 * @param forceStrategy Optional strategy override (user confirmed)
 */
async function startDownload(
  fileHash: string,
  fileName: string,
  source: DownloadSource = { type: "owned" },
  fileSize?: number,
  forceStrategy?: DownloadStrategy,
): Promise<string> {
  const id = generateId();

  // Determine initial strategy
  let strategy: DownloadStrategy = forceStrategy ?? "inmemory";
  if (!forceStrategy) {
    if (fileSize) {
      const check = checkFileSize(fileSize);
      strategy = check.decision.strategy;
    } else if (source.type === "owned" && canUseStreaming()) {
      // If size is unknown, prefer streaming (avoids OOM on large files).
      strategy = "streaming";
    }
  }

  const task: DownloadTask = {
    id,
    fileHash,
    fileName,
    fileSize: fileSize ?? 0,
    contentType: "application/octet-stream",
    status: "pending",
    error: null,
    totalChunks: 0,
    downloadedChunks: 0,
    progress: 0,
    presignedUrls: [],
    urlsFetchedAt: null,
    chunks: [],
    initialKey: null,
    encryptionAlgorithm: null,
    source,
    createdAt: Date.now(),
    startedAt: null,
    completedAt: null,
    abortController: null,
    strategy,
  };

  tasks = [...tasks, task];

  // Choose execution method based on source and strategy
  if (source.type !== "owned") {
    // Shared files use backend proxy
    executeBackendProxyDownload(task);
  } else if (strategy === "streaming" && canUseStreaming()) {
    // Large files with streaming support
    executeStreamingDownload(task);
  } else {
    // Default in-memory download
    executeDownload(task);
  }

  return id;
}

/**
 * 启动批量下载任务，采用批次内并发调度并对单文件自动重试。
 *
 * @param items 批量文件列表。
 * @param options 调度选项（并发与重试次数）。
 * @returns 最终批次状态。
 */
async function startBatchDownload(
  items: BatchDownloadItem[],
  options?: { concurrency?: number; retryTimes?: number },
): Promise<BatchDownloadState> {
  if (items.length === 0) {
    throw new Error("至少选择一个文件");
  }
  if (items.length > MAX_BATCH_FILES) {
    throw new Error(`批量下载文件数不能超过 ${MAX_BATCH_FILES} 个`);
  }
  if (batchState?.status === "running") {
    throw new Error("已有批量下载正在执行");
  }

  const batchId = generateBatchId();
  const batchConcurrency = Math.max(
    1,
    Math.min(10, options?.concurrency ?? DEFAULT_BATCH_CONCURRENCY),
  );
  const retryTimes = Math.max(0, options?.retryTimes ?? DEFAULT_BATCH_RETRIES);
  const queue = [...items];
  const failures: BatchDownloadFailure[] = [];
  let cursor = 0;
  let activeCount = 0;
  let completedCount = 0;
  let successCount = 0;
  let totalRetryCount = 0;

  batchState = {
    id: batchId,
    status: "running",
    total: queue.length,
    completedCount: 0,
    activeCount: 0,
    successCount: 0,
    failedCount: 0,
    failures: [],
    startedAt: Date.now(),
    completedAt: null,
  };

  /**
   * 单 worker 循环拉取队列并执行下载，直到队列耗尽。
   */
  const worker = async (): Promise<void> => {
    while (true) {
      const index = cursor++;
      if (index >= queue.length) {
        return;
      }
      const item = queue[index];
      activeCount++;
      updateBatchProgress(activeCount, completedCount, successCount, failures);

      const result = await executeBatchItem(item, retryTimes);
      totalRetryCount += calculateRetryCount(result.attempts);

      activeCount--;
      completedCount++;
      if (result.success) {
        successCount++;
      } else {
        failures.push({
          ...item,
          reason: result.reason,
          attempts: result.attempts,
        });
      }
      updateBatchProgress(activeCount, completedCount, successCount, failures);
    }
  };

  const workers = Array.from(
    { length: Math.min(batchConcurrency, queue.length) },
    () => worker(),
  );
  await Promise.all(workers);

  updateBatchProgress(0, completedCount, successCount, failures);
  if (batchState) {
    const completedSnapshot: BatchDownloadState = {
      ...batchState,
      status: "completed",
      completedAt: Date.now(),
    };
    batchState = completedSnapshot;
    void reportBatchMetricsInBackground(completedSnapshot, totalRetryCount);
    return completedSnapshot;
  }

  // 理论上不会进入此分支，仅作为类型兜底。
  throw new Error("批量下载状态异常");
}

/**
 * 基于最近一个批次的失败清单，重新发起批量重试。
 *
 * @returns 新批次状态；无失败项时返回 null。
 */
async function retryBatchFailed(): Promise<BatchDownloadState | null> {
  if (!batchState || batchState.failures.length === 0) {
    return null;
  }
  const retryItems = batchState.failures.map((failure) => ({
    fileHash: failure.fileHash,
    fileName: failure.fileName,
    fileSize: failure.fileSize,
    source: failure.source,
  }));
  return startBatchDownload(retryItems, {
    concurrency: DEFAULT_BATCH_CONCURRENCY,
    retryTimes: DEFAULT_BATCH_RETRIES,
  });
}

/**
 * 清空批次状态，便于页面在完成后手动重置展示。
 */
function clearBatchState(): void {
  batchState = null;
}

/**
 * Pause a downloading task
 */
function pauseDownload(id: string): void {
  const task = getTask(id);
  if (
    !task ||
    (task.status !== "downloading" &&
      task.status !== "streaming" &&
      task.status !== "writing")
  ) {
    return;
  }

  task.abortController?.abort();
  updateTask(id, {
    status: "paused",
    abortController: null,
  });
}

/**
 * Resume a paused task
 */
async function resumeDownload(id: string): Promise<void> {
  const task = getTask(id);
  if (!task || task.status !== "paused") return;

  updateTask(id, { status: "pending", error: null });

  // Get fresh task reference after update
  const updatedTask = getTask(id);
  if (!updatedTask) return;

  // Choose execution method based on source and strategy
  if (updatedTask.source.type !== "owned") {
    executeBackendProxyDownload(updatedTask);
  } else if (updatedTask.strategy === "streaming" && canUseStreaming()) {
    executeStreamingDownload(updatedTask);
  } else {
    executeDownload(updatedTask);
  }
}

/**
 * Cancel a download task
 */
async function cancelDownload(id: string): Promise<void> {
  const task = getTask(id);
  if (!task) return;

  task.abortController?.abort();

  updateTask(id, {
    status: "cancelled",
    abortController: null,
  });

  // Cleanup persisted data
  await clearTaskData(id);
  downloadedChunksMap.delete(id);
}

/**
 * Retry a failed task
 */
async function retryDownload(id: string): Promise<void> {
  const task = getTask(id);
  if (!task || !["failed", "cancelled"].includes(task.status)) return;

  updateTask(id, {
    status: "pending",
    error: null,
    downloadedChunks: 0,
    progress: 0,
  });

  // Get fresh task reference after update
  const updatedTask = getTask(id);
  if (!updatedTask) return;

  // Choose execution method based on source and strategy
  if (updatedTask.source.type !== "owned") {
    executeBackendProxyDownload(updatedTask);
  } else if (updatedTask.strategy === "streaming" && canUseStreaming()) {
    executeStreamingDownload(updatedTask);
  } else {
    executeDownload(updatedTask);
  }
}

/**
 * Remove a task from the list
 */
async function removeTask(id: string): Promise<void> {
  const task = getTask(id);
  if (!task) return;

  // Cancel if active
  if (
    task.status === "downloading" ||
    task.status === "fetching_urls" ||
    task.status === "streaming" ||
    task.status === "writing"
  ) {
    task.abortController?.abort();
  }

  // Cleanup
  await clearTaskData(id);
  downloadedChunksMap.delete(id);

  tasks = tasks.filter((t) => t.id !== id);
}

/**
 * Clear all completed tasks
 */
function clearCompleted(): void {
  const completedIds = completedTasks.map((t) => t.id);
  tasks = tasks.filter((t) => t.status !== "completed");

  // Cleanup (already done on completion, but just in case)
  completedIds.forEach((id) => {
    downloadedChunksMap.delete(id);
  });
}

/**
 * Clear all in-memory and persisted download state for the current browser profile.
 */
async function clearAllDownloads(): Promise<void> {
  for (const task of tasks) {
    if (
      task.status === "downloading" ||
      task.status === "fetching_urls" ||
      task.status === "streaming" ||
      task.status === "writing"
    ) {
      task.abortController?.abort();
    }
  }

  tasks = [];
  batchState = null;
  downloadedChunksMap.clear();
  initialized = false;
  await clearAllDownloadData();
}

/**
 * Restore pending tasks from IndexedDB (call on app init)
 */
async function restoreTasks(): Promise<void> {
  if (!browser || initialized) return;

  try {
    // Cleanup expired data first
    await cleanupExpiredData();

    // Load pending tasks
    const persistedTasks = await getPendingTasks();
    const authContextHash = await getAuthContextHash();

    for (const pt of persistedTasks) {
      if (!authContextHash || pt.authContextHash !== authContextHash) {
        await clearTaskData(pt.id);
        continue;
      }

      // Check if already in memory
      if (tasks.find((t) => t.id === pt.id)) continue;

      // Load chunk count
      const chunks = await getChunks(pt.id);

      const task: DownloadTask = {
        id: pt.id,
        fileHash: pt.fileHash,
        fileName: pt.fileName,
        fileSize: pt.fileSize,
        contentType: pt.contentType,
        status: "paused", // Restored as paused
        error: null,
        totalChunks: pt.totalChunks,
        downloadedChunks: chunks.size,
        progress:
          pt.totalChunks > 0
            ? Math.round((chunks.size / pt.totalChunks) * 100)
            : 0,
        presignedUrls: [],
        urlsFetchedAt: null,
        chunks: Array.from({ length: pt.totalChunks }, (_, i) => ({
          index: i,
          status: chunks.has(i) ? ("completed" as const) : ("pending" as const),
          retryCount: 0,
        })),
        initialKey: null,
        encryptionAlgorithm: null,
        source: pt.source,
        createdAt: pt.createdAt,
        startedAt: null,
        completedAt: null,
        abortController: null,
        // Restored tasks use in-memory strategy since we've already downloaded some chunks
        // (streaming doesn't support resuming from partial chunks)
        strategy: "inmemory",
      };

      tasks = [...tasks, task];
      downloadedChunksMap.set(pt.id, chunks);
    }

    initialized = true;
  } catch (error) {
    console.error("Failed to restore download tasks:", error);
    initialized = true;
  }
}

/**
 * Set concurrent download limit
 */
function setConcurrency(value: number): void {
  concurrency = Math.max(1, Math.min(10, value));
}

// ===== Network Status Handling =====

if (browser) {
  window.addEventListener("online", () => {
    // Could auto-resume paused tasks here if desired
  });

  window.addEventListener("offline", () => {
    // Pause all active downloads
    activeTasks.forEach((t) => {
      pauseDownload(t.id);
      updateTask(t.id, { error: "network_offline" });
    });
  });
}

// ===== Export Hook =====

export function useDownload() {
  return {
    // State (getters for reactivity)
    get tasks() {
      return tasks;
    },
    get isDownloading() {
      return activeTasks.length > 0;
    },
    get pendingTasks() {
      return pendingTasks;
    },
    get activeTasks() {
      return activeTasks;
    },
    get streamingTasks() {
      return streamingTasks;
    },
    get pausedTasks() {
      return pausedTasks;
    },
    get completedTasks() {
      return completedTasks;
    },
    get failedTasks() {
      return failedTasks;
    },
    get concurrency() {
      return concurrency;
    },
    get initialized() {
      return initialized;
    },
    get batchState() {
      return batchState;
    },

    // Actions
    startDownload,
    startBatchDownload,
    retryBatchFailed,
    clearBatchState,
    pauseDownload,
    resumeDownload,
    cancelDownload,
    retryDownload,
    removeTask,
    clearCompleted,
    clearAllDownloads,
    restoreTasks,
    setConcurrency,

    // File size utilities
    checkFileSize,
    canUseStreaming,
  };
}
