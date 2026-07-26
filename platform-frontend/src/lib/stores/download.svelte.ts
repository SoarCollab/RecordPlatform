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
  saveTask,
  getChunks,
  getPendingTasks,
  clearTaskData,
  clearAllDownloadData,
  cleanupExpiredData,
  type PersistedDownloadTask,
  type DownloadSource,
} from "$utils/downloadStorage";
import { arrayToBlob, downloadBlob } from "$utils/crypto";
import {
  executeBoundedDownload,
  executeLegacyFallbackDownload,
} from "$utils/boundedDownloader";
import {
  createFileSystemDownloadSink,
  MemoryDownloadSink,
} from "$utils/downloadSink";
import {
  DownloadMetricsTracker,
  type DownloadStreamMetrics,
} from "$utils/downloadMetrics";
import type { FileDownloadMetadataVO } from "$api/types";
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
  MAX_SAFE_INMEMORY_SIZE,
} from "$utils/fileSize";

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
  /** 已验证的 manifest 元数据，供重试复用同一合同。 */
  downloadMetadata: FileDownloadMetadataVO | null;
  /** 最近一次有界下载的内存、认证与写入指标。 */
  downloadMetrics: DownloadStreamMetrics | null;
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

type PresignedUrlMetadata = {
  urls: string[];
  decryptInfo: fileApi.FileDecryptInfoVO;
  encryptionAlgorithm: string | null;
  metadata: FileDownloadMetadataVO | null;
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
    try {
      const metadata = await fileApi.getDownloadMetadata(task.fileHash);
      const orderedParts = [...metadata.parts].sort(
        (a, b) => a.index - b.index,
      );
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
        metadata,
      };
    } catch (metadataError) {
      if (!isMissingManifestError(metadataError)) {
        throw metadataError;
      }
      console.warn(
        "[download] manifest metadata unavailable, using legacy endpoints",
        metadataError,
      );
      return fetchLegacyPresignedUrls(task.fileHash);
    }
  }

  // For shared files, we don't have presigned URL endpoint yet
  // This will throw if not implemented
  throw new Error(
    "Presigned URLs not available for shared files. Use fallback download.",
  );
}

/**
 * 通过旧地址与解密信息接口恢复无 manifest 自有文件的下载元数据。
 */
async function fetchLegacyPresignedUrls(
  fileHash: string,
): Promise<PresignedUrlMetadata> {
  const [urls, decryptInfo] = await Promise.all([
    fileApi.getDownloadAddress(fileHash),
    fileApi.getDecryptInfo(fileHash),
  ]);
  return {
    urls,
    decryptInfo,
    encryptionAlgorithm: decryptInfo.initialKey ? null : "NONE",
    metadata: null,
  };
}

/**
 * 判断 metadata 失败是否由历史文件缺少分片 manifest 引起。
 */
function isMissingManifestError(error: unknown): boolean {
  return (
    error instanceof Error && error.message.includes("文件缺少分片 manifest")
  );
}

async function executeDownload(task: DownloadTask): Promise<void> {
  const taskId = task.id;
  const abortController = new AbortController();
  let metricsTracker: DownloadMetricsTracker | null = null;
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
    let downloadMetadata = task.downloadMetadata;
    const authContextHash = await getAuthContextHash();

    if (urls.length === 0 || areUrlsExpired(task.urlsFetchedAt)) {
      updateTask(taskId, { status: "fetching_urls" });

      const fetchedMetadata = await fetchPresignedUrls(task);
      const {
        urls: newUrls,
        decryptInfo,
        encryptionAlgorithm: metadataEncryptionAlgorithm,
      } = fetchedMetadata;
      urls = newUrls;
      initialKey = decryptInfo.initialKey ?? null;
      totalChunks = decryptInfo.chunkCount;
      contentType = decryptInfo.contentType;
      fileName = decryptInfo.fileName;
      fileSize = decryptInfo.fileSize;
      encryptionAlgorithm = metadataEncryptionAlgorithm;
      downloadMetadata = fetchedMetadata.metadata;

      updateTask(taskId, {
        presignedUrls: urls,
        urlsFetchedAt: Date.now(),
        initialKey,
        encryptionAlgorithm,
        totalChunks,
        contentType,
        fileName,
        fileSize,
        downloadMetadata,
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

    // Manifest-backed downloads use the bounded reader for all three formats.
    if (downloadMetadata) {
      const sink = new MemoryDownloadSink(fileSize, MAX_SAFE_INMEMORY_SIZE);
      metricsTracker = new DownloadMetricsTracker();
      updateTask(taskId, { status: "downloading" });
      const metrics = await executeBoundedDownload({
        metadata: downloadMetadata,
        expectedFileHash: task.fileHash,
        sink,
        metrics: metricsTracker,
        signal: abortController.signal,
        onPartComplete: (completed, total) => {
          updateTask(taskId, {
            status: "downloading",
            downloadedChunks: completed,
            progress: Math.round((completed / total) * 100),
          });
        },
      });
      if (abortController.signal.aborted) {
        throw new Error("Download cancelled");
      }
      downloadBlob(arrayToBlob(sink.getData(), contentType), fileName);
      await clearTaskData(taskId);
      downloadedChunksMap.delete(taskId);
      updateTask(taskId, {
        status: "completed",
        progress: 100,
        downloadedChunks: totalChunks,
        completedAt: Date.now(),
        abortController: null,
        downloadMetrics: metrics,
      });
      return;
    }

    // 历史无 manifest 文件也必须逐分片读取，并对 v1 密文执行硬性 part 上限。
    const sink = new MemoryDownloadSink(fileSize, MAX_SAFE_INMEMORY_SIZE);
    metricsTracker = new DownloadMetricsTracker();
    updateTask(taskId, { status: "downloading" });
    const metrics = await executeLegacyFallbackDownload({
      urls,
      fileSize,
      totalChunks,
      initialKey,
      encrypted: !isPlainDownload(encryptionAlgorithm),
      sink,
      signal: abortController.signal,
      metrics: metricsTracker,
      onPartComplete: (completed, total) => {
        updateTask(taskId, {
          status: "downloading",
          downloadedChunks: completed,
          progress: Math.round((completed / total) * 100),
        });
      },
    });
    if (abortController.signal.aborted) {
      throw new Error("Download cancelled");
    }
    downloadBlob(arrayToBlob(sink.getData(), contentType), fileName);
    await clearTaskData(taskId);
    downloadedChunksMap.delete(taskId);
    updateTask(taskId, {
      status: "completed",
      progress: 100,
      downloadedChunks: totalChunks,
      completedAt: Date.now(),
      abortController: null,
      downloadMetrics: metrics,
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
          downloadMetrics: metricsTracker?.snapshot() ?? null,
        });
      }
      return;
    }

    updateTask(taskId, {
      status: "failed",
      error: err.message,
      abortController: null,
      downloadMetrics: metricsTracker?.snapshot() ?? null,
    });
  }
}

// ===== Streaming Download (File System Access API) =====

/**
 * Execute streaming download for large files
 * Uses File System Access API to write directly to disk
 */
async function executeStreamingDownload(task: DownloadTask): Promise<void> {
  const taskId = task.id;
  const abortController = new AbortController();
  let metricsTracker: DownloadMetricsTracker | null = null;
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
    let downloadMetadata = task.downloadMetadata;

    if (urls.length === 0 || areUrlsExpired(task.urlsFetchedAt)) {
      updateTask(taskId, { status: "fetching_urls" });

      const fetchedMetadata = await fetchPresignedUrls(task);
      const {
        urls: newUrls,
        decryptInfo,
        encryptionAlgorithm: metadataEncryptionAlgorithm,
      } = fetchedMetadata;
      urls = newUrls;
      initialKey = decryptInfo.initialKey ?? null;
      totalChunks = decryptInfo.chunkCount;
      contentType = decryptInfo.contentType;
      fileName = decryptInfo.fileName;
      fileSize = decryptInfo.fileSize;
      encryptionAlgorithm = metadataEncryptionAlgorithm;
      downloadMetadata = fetchedMetadata.metadata;

      updateTask(taskId, {
        presignedUrls: urls,
        urlsFetchedAt: Date.now(),
        initialKey,
        encryptionAlgorithm,
        totalChunks,
        contentType,
        fileName,
        fileSize,
        downloadMetadata,
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

    let result: { success: boolean; error?: string };
    let completedMetrics: DownloadStreamMetrics | null = null;
    if (downloadMetadata) {
      const sink = await createFileSystemDownloadSink(fileHandle);
      metricsTracker = new DownloadMetricsTracker();
      const metrics = await executeBoundedDownload({
        metadata: downloadMetadata,
        expectedFileHash: task.fileHash,
        sink,
        signal: abortController.signal,
        metrics: metricsTracker,
        onPartComplete: (completed, total) => {
          updateTask(taskId, {
            status: "streaming",
            downloadedChunks: completed,
            progress: Math.round((completed / total) * 100),
          });
        },
      });
      completedMetrics = metrics;
      if (abortController.signal.aborted) {
        throw new Error("Download cancelled");
      }
      result = { success: true };
    } else {
      const sink = await createFileSystemDownloadSink(fileHandle);
      metricsTracker = new DownloadMetricsTracker();
      const metrics = await executeLegacyFallbackDownload({
        urls,
        fileSize,
        totalChunks,
        initialKey,
        encrypted: !isPlainDownload(encryptionAlgorithm),
        sink,
        signal: abortController.signal,
        metrics: metricsTracker,
        onPartComplete: (completed, total) => {
          updateTask(taskId, {
            status: "streaming",
            downloadedChunks: completed,
            progress: Math.round((completed / total) * 100),
          });
        },
      });
      completedMetrics = metrics;
      if (abortController.signal.aborted) {
        throw new Error("Download cancelled");
      }
      result = { success: true };
    }

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
      downloadMetrics: completedMetrics,
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
          downloadMetrics: metricsTracker?.snapshot() ?? null,
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
      downloadMetrics: metricsTracker?.snapshot() ?? null,
    });
  }
}

// ===== Fallback for shared files (backend proxy) =====

const UNSUPPORTED_LARGE_DOWNLOAD_MESSAGE =
  "当前浏览器不支持超过 64 MiB 的有界保存，请使用 Chrome 或 Edge。";

/** 将不允许进入 Blob/backend proxy 的任务置为失败。 */
function rejectUnboundedDownload(
  taskId: string,
  reason = UNSUPPORTED_LARGE_DOWNLOAD_MESSAGE,
): void {
  updateTask(taskId, {
    status: "failed",
    error: reason,
    abortController: null,
  });
}

/** 按来源、策略和文件大小统一调度下载，避免绕过内存硬上限。 */
function dispatchDownloadTask(task: DownloadTask): void {
  if (task.source.type !== "owned") {
    if (
      !Number.isSafeInteger(task.fileSize) ||
      task.fileSize < 0 ||
      task.fileSize > MAX_SAFE_INMEMORY_SIZE
    ) {
      rejectUnboundedDownload(
        task.id,
        "共享文件大小无效或超过 64 MiB，当前浏览器不支持有界保存。",
      );
      return;
    }
    void executeBackendProxyDownload(task);
    return;
  }

  if (task.strategy === "backend_proxy") {
    rejectUnboundedDownload(task.id);
    return;
  }

  if (task.strategy === "streaming") {
    if (canUseStreaming()) {
      void executeStreamingDownload(task);
      return;
    }
    if (
      Number.isSafeInteger(task.fileSize) &&
      task.fileSize > 0 &&
      task.fileSize <= MAX_SAFE_INMEMORY_SIZE
    ) {
      void executeDownload(task);
      return;
    }
    rejectUnboundedDownload(task.id);
    return;
  }

  if (task.fileSize > MAX_SAFE_INMEMORY_SIZE) {
    rejectUnboundedDownload(task.id);
    return;
  }
  void executeDownload(task);
}

async function executeBackendProxyDownload(task: DownloadTask): Promise<void> {
  const taskId = task.id;

  if (
    !Number.isSafeInteger(task.fileSize) ||
    task.fileSize < 0 ||
    task.fileSize > MAX_SAFE_INMEMORY_SIZE
  ) {
    rejectUnboundedDownload(taskId);
    return;
  }

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

    // 共享文件可能未携带预先知道的大小，必须以响应 Blob 的实际大小再次封顶。
    if (
      !blob ||
      !Number.isSafeInteger(blob.size) ||
      blob.size > MAX_SAFE_INMEMORY_SIZE ||
      (task.fileSize > 0 && blob.size !== task.fileSize)
    ) {
      throw new Error("共享文件响应超过 64 MiB 或与声明大小不一致");
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
    downloadMetadata: null,
    downloadMetrics: null,
  };

  tasks = [...tasks, task];

  dispatchDownloadTask(task);

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

  dispatchDownloadTask(updatedTask);
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
    downloadMetrics: null,
  });

  // Get fresh task reference after update
  const updatedTask = getTask(id);
  if (!updatedTask) return;

  dispatchDownloadTask(updatedTask);
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
        downloadMetadata: null,
        downloadMetrics: null,
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
