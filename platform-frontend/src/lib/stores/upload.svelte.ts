import { browser } from "$app/environment";
import { useSSE } from "$stores/sse.svelte";
import * as uploadApi from "$api/endpoints/upload";
import { ApiError } from "$api/client";

// ===== Types =====

export type UploadStatus =
  | "pending"
  | "uploading"
  | "processing"
  | "paused"
  | "completed"
  | "failed"
  | "cancelled";

export interface UploadTask {
  id: string;
  file: File;
  clientId: string | null;
  status: UploadStatus;
  progress: number;
  processProgress: number;
  serverProgress: number;
  uploadedChunks: number[];
  totalChunks: number;
  chunkSize: number; // 该任务使用的分片大小
  speed: number; // bytes/sec
  error: string | null;
  startTime: number | null;
  endTime: number | null;
}

// ===== Configuration =====

// 动态分片配置
const CHUNK_CONFIG = {
  MIN_SIZE: 2 * 1024 * 1024, // 2MB 最小分片
  MAX_SIZE: 80 * 1024 * 1024, // 80MB 最大分片 (后端 Dubbo 限制)
  // 基于文件大小的分片规则
  RULES: [
    { maxFileSize: 10 * 1024 * 1024, chunkSize: 2 * 1024 * 1024 }, // < 10MB: 2MB
    { maxFileSize: 100 * 1024 * 1024, chunkSize: 5 * 1024 * 1024 }, // < 100MB: 5MB
    { maxFileSize: 500 * 1024 * 1024, chunkSize: 10 * 1024 * 1024 }, // < 500MB: 10MB
    { maxFileSize: 2 * 1024 * 1024 * 1024, chunkSize: 20 * 1024 * 1024 }, // < 2GB: 20MB
    { maxFileSize: Infinity, chunkSize: 50 * 1024 * 1024 }, // >= 2GB: 50MB
  ],
} as const;

const MAX_CONCURRENT_UPLOADS = 3;
const MAX_CONCURRENT_CHUNKS = 3;

/**
 * 根据文件大小计算最优分片大小
 */
function calculateOptimalChunkSize(fileSize: number): number {
  for (const rule of CHUNK_CONFIG.RULES) {
    if (fileSize <= rule.maxFileSize) {
      return rule.chunkSize;
    }
  }
  return CHUNK_CONFIG.MAX_SIZE;
}

// ===== State =====

let tasks = $state<UploadTask[]>([]);

// Track uploaded chunks per task using Set for thread-safe concurrent updates
const uploadedChunkSets = new Map<string, Set<number>>();

// ===== Derived =====

const pendingTasks = $derived(tasks.filter((t) => t.status === "pending"));
const activeTasks = $derived(tasks.filter((t) => t.status === "uploading"));
const processingTasks = $derived(
  tasks.filter((t) => t.status === "processing"),
);
const completedTasks = $derived(tasks.filter((t) => t.status === "completed"));
const failedTasks = $derived(tasks.filter((t) => t.status === "failed"));
const cancelledTasks = $derived(tasks.filter((t) => t.status === "cancelled"));
const totalProgress = $derived(
  tasks.length > 0
    ? Math.round(
        tasks.reduce((sum, t) => {
          const visibleProgress =
            t.status === "processing" ? t.processProgress : t.progress;
          return sum + visibleProgress;
        }, 0) / tasks.length,
      )
    : 0,
);

// ===== Internal Helpers =====

function generateId(): string {
  return `upload-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

function updateTask(id: string, updates: Partial<UploadTask>): void {
  tasks = tasks.map((t) => (t.id === id ? { ...t, ...updates } : t));
}

function calculateChunks(
  fileSize: number,
  chunkSize: number = CHUNK_CONFIG.RULES[1].chunkSize, // 默认 5MB
): number {
  return Math.ceil(fileSize / chunkSize);
}

const sse = useSSE();
let unsubscribeSSE: (() => void) | null = null;
let visibilityListenerBound = false;

const progressPollTimeouts = new Map<string, ReturnType<typeof setTimeout>>();
const VISIBLE_POLL_INTERVAL_MS = 1500;
const HIDDEN_POLL_INTERVAL_MS = 8000;

function isPageVisible(): boolean {
  return browser && document.visibilityState === "visible";
}

function ensureSideEffectsInitialized(): void {
  if (!browser) return;

  if (!visibilityListenerBound) {
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "visible") {
        for (const t of tasks) {
          if (
            t.clientId &&
            (t.status === "uploading" || t.status === "processing")
          ) {
            startProgressPolling(t.id, true);
          }
        }
      }
    });
    visibilityListenerBound = true;
  }

  if (!unsubscribeSSE) {
    unsubscribeSSE = sse.subscribe((message) => {
      if (
        message.type === "file-record-success" ||
        message.type === "file-record-failed"
      ) {
        for (const t of tasks) {
          if (t.clientId && t.status === "processing") {
            startProgressPolling(t.id, true);
          }
        }
      }
    });
  }
}

function stopProgressPolling(id: string): void {
  const timeout = progressPollTimeouts.get(id);
  if (timeout) {
    clearTimeout(timeout);
    progressPollTimeouts.delete(id);
  }
}

function scheduleNextPoll(id: string): void {
  stopProgressPolling(id);

  const delay = isPageVisible()
    ? VISIBLE_POLL_INTERVAL_MS
    : HIDDEN_POLL_INTERVAL_MS;
  const timeout = setTimeout(() => {
    void pollServerProgress(id);
  }, delay);
  progressPollTimeouts.set(id, timeout);
}

async function pollServerProgress(id: string): Promise<void> {
  const task = tasks.find((t) => t.id === id);
  if (!task?.clientId) {
    stopProgressPolling(id);
    return;
  }

  if (!(task.status === "uploading" || task.status === "processing")) {
    stopProgressPolling(id);
    return;
  }

  try {
    const progress = await uploadApi.getUploadProgress(task.clientId);
    const nextProgress = Math.max(task.progress, progress.uploadProgress);

    updateTask(id, {
      progress: nextProgress,
      processProgress: progress.processProgress,
      serverProgress: progress.progress,
    });

    if (
      task.status === "processing" &&
      (progress.status === "completed" ||
        progress.progress >= 100 ||
        progress.processProgress >= 100)
    ) {
      updateTask(id, {
        status: "completed",
        progress: 100,
        processProgress: 100,
        serverProgress: 100,
        endTime: Date.now(),
      });
      stopProgressPolling(id);
      return;
    }
  } catch (error) {
    // 如果返回 40006，说明会话已被清理（上传已完成或过期）
    // 后端约定：UPLOAD_SESSION_NOT_FOUND = 40006
    if (error instanceof ApiError && error.code === 40006) {
      updateTask(id, {
        status: "completed",
        progress: 100,
        processProgress: 100,
        serverProgress: 100,
        endTime: Date.now(),
      });
      stopProgressPolling(id);
      return;
    }
    // 其他错误继续重试
  }

  scheduleNextPoll(id);
}

function startProgressPolling(id: string, immediate: boolean = false): void {
  ensureSideEffectsInitialized();

  if (immediate) {
    stopProgressPolling(id);
    void pollServerProgress(id);
    return;
  }

  scheduleNextPoll(id);
}

// ===== Upload Logic =====

async function uploadChunks(task: UploadTask): Promise<void> {
  if (!task.clientId) return;

  const chunkSize = task.chunkSize;
  const file = task.file;

  // Initialize chunk tracking Set if not exists
  if (!uploadedChunkSets.has(task.id)) {
    uploadedChunkSets.set(task.id, new Set(task.uploadedChunks));
  }
  const chunkSet = uploadedChunkSets.get(task.id)!;

  // Find chunks that need to be uploaded
  const chunksToUpload: number[] = [];
  for (let i = 0; i < task.totalChunks; i++) {
    if (!chunkSet.has(i)) {
      chunksToUpload.push(i);
    }
  }

  // Speed calculation state
  let lastTime = Date.now();

  // Upload chunks with concurrency control
  const uploadChunk = async (chunkNumber: number): Promise<void> => {
    // Check if paused or cancelled
    const currentTask = tasks.find((t) => t.id === task.id);
    if (!currentTask || currentTask.status !== "uploading") {
      return;
    }

    const start = chunkNumber * chunkSize;
    const end = Math.min(start + chunkSize, file.size);
    const chunk = file.slice(start, end);
    const chunkBytes = end - start;

    await uploadApi.uploadChunk(task.clientId!, chunkNumber, chunk);

    // Thread-safe: add to Set (Set.add is atomic for primitive values)
    chunkSet.add(chunkNumber);

    // Calculate speed based on total progress
    const now = Date.now();
    const timeDiff = (now - lastTime) / 1000;
    const speed = timeDiff > 0 ? chunkBytes / timeDiff : 0;
    lastTime = now;

    // Update task state from the Set (single source of truth)
    const nextProgress = Math.round((chunkSet.size / task.totalChunks) * 100);
    const current = tasks.find((t) => t.id === task.id);
    const nextServerProgress = Math.max(
      current?.serverProgress ?? 0,
      nextProgress,
    );

    updateTask(task.id, {
      uploadedChunks: Array.from(chunkSet),
      progress: nextProgress,
      serverProgress: nextServerProgress,
      speed,
    });
  };

  // Process chunks with concurrency limit
  const queue = [...chunksToUpload];
  const workers: Promise<void>[] = [];

  const processQueue = async () => {
    while (queue.length > 0) {
      const currentTask = tasks.find((t) => t.id === task.id);
      if (!currentTask || currentTask.status !== "uploading") {
        break;
      }

      const chunkNumber = queue.shift()!;
      await uploadChunk(chunkNumber);
    }
  };

  for (let i = 0; i < MAX_CONCURRENT_CHUNKS; i++) {
    workers.push(processQueue());
  }

  await Promise.all(workers);
}

// ===== Actions =====

async function addFile(file: File): Promise<string> {
  const id = generateId();
  const chunkSize = calculateOptimalChunkSize(file.size);
  const totalChunks = calculateChunks(file.size, chunkSize);

  const task: UploadTask = {
    id,
    file,
    clientId: null,
    status: "pending",
    progress: 0,
    processProgress: 0,
    serverProgress: 0,
    uploadedChunks: [],
    totalChunks,
    chunkSize,
    speed: 0,
    error: null,
    startTime: null,
    endTime: null,
  };

  tasks = [...tasks, task];

  // Auto start if below concurrent limit
  if (activeTasks.length < MAX_CONCURRENT_UPLOADS) {
    startUpload(id);
  }

  return id;
}

async function addFiles(files: File[]): Promise<string[]> {
  return Promise.all(files.map((f) => addFile(f)));
}

async function startUpload(id: string): Promise<void> {
  const task = tasks.find((t) => t.id === id);
  if (!task || task.status === "uploading") return;

  updateTask(id, {
    status: "uploading",
    startTime: Date.now(),
    error: null,
    processProgress: 0,
    serverProgress: 0,
  });

  try {
    // Initialize upload
    const result = await uploadApi.startUpload({
      fileName: task.file.name,
      fileSize: task.file.size,
      contentType: task.file.type || "application/octet-stream",
      chunkSize: task.chunkSize,
      totalChunks: task.totalChunks,
    });

    const initialProgress = Math.round(
      (result.processedChunks.length / task.totalChunks) * 100,
    );

    updateTask(id, {
      clientId: result.clientId,
      uploadedChunks: result.processedChunks,
      progress: initialProgress,
      processProgress: 0,
      serverProgress: initialProgress,
    });

    startProgressPolling(id, true);

    // Initialize chunk set with server-reported processed chunks
    uploadedChunkSets.set(id, new Set(result.processedChunks));

    // Upload chunks
    await uploadChunks(tasks.find((t) => t.id === id)!);

    // Check final status
    const finalTask = tasks.find((t) => t.id === id);
    if (finalTask?.status === "uploading") {
      await uploadApi.completeUpload(finalTask.clientId!);
      updateTask(id, {
        status: "processing",
        progress: 100,
        processProgress: Math.max(finalTask.processProgress, 0),
        serverProgress: Math.max(finalTask.serverProgress, 0),
      });
      startProgressPolling(id, true);
    }
  } catch (err) {
    stopProgressPolling(id);
    updateTask(id, {
      status: "failed",
      error: err instanceof Error ? err.message : "上传失败",
      endTime: Date.now(),
    });
  }

  // Start next pending upload
  const next = pendingTasks[0];
  if (next && activeTasks.length < MAX_CONCURRENT_UPLOADS) {
    startUpload(next.id);
  }
}

async function pauseUpload(id: string): Promise<void> {
  const task = tasks.find((t) => t.id === id);
  if (!task || task.status !== "uploading") return;

  updateTask(id, { status: "paused" });
  stopProgressPolling(id);

  if (task.clientId) {
    try {
      await uploadApi.pauseUpload(task.clientId);
    } catch {
      // Ignore pause errors
    }
  }
}

async function resumeUpload(id: string): Promise<void> {
  const task = tasks.find((t) => t.id === id);
  if (!task || task.status !== "paused") return;

  if (task.clientId) {
    try {
      const result = await uploadApi.resumeUpload(task.clientId);
      // Sync chunk set with server state
      uploadedChunkSets.set(id, new Set(result.processedChunks));
      updateTask(id, {
        uploadedChunks: result.processedChunks,
        progress: Math.round(
          (result.processedChunks.length / task.totalChunks) * 100,
        ),
      });
    } catch {
      // Continue with existing state
    }
  }

  updateTask(id, { status: "uploading" });
  startProgressPolling(id, true);
  await uploadChunks(tasks.find((t) => t.id === id)!);

  const finalTask = tasks.find((t) => t.id === id);
  if (finalTask?.status === "uploading") {
    await uploadApi.completeUpload(finalTask.clientId!);
    updateTask(id, {
      status: "processing",
      progress: 100,
      processProgress: Math.max(finalTask.processProgress, 0),
      serverProgress: Math.max(finalTask.serverProgress, 0),
    });
    startProgressPolling(id, true);
  }
}

async function cancelUpload(id: string): Promise<void> {
  const task = tasks.find((t) => t.id === id);
  if (!task) return;

  updateTask(id, { status: "cancelled", endTime: Date.now() });
  stopProgressPolling(id);

  if (task.clientId) {
    try {
      await uploadApi.cancelUpload(task.clientId);
    } catch {
      // Ignore cancel errors
    }
  }
}

async function retryUpload(id: string): Promise<void> {
  const task = tasks.find((t) => t.id === id);
  if (!task || !["failed", "cancelled"].includes(task.status)) return;

  uploadedChunkSets.delete(id);
  stopProgressPolling(id);

  updateTask(id, {
    status: "pending",
    progress: 0,
    processProgress: 0,
    serverProgress: 0,
    uploadedChunks: [],
    clientId: null,
    speed: 0,
    startTime: null,
    endTime: null,
    error: null,
  });

  await startUpload(id);
}

function removeTask(id: string): void {
  const task = tasks.find((t) => t.id === id);
  if (task && ["uploading", "paused", "processing"].includes(task.status)) {
    cancelUpload(id);
  }

  stopProgressPolling(id);
  tasks = tasks.filter((t) => t.id !== id);
  uploadedChunkSets.delete(id);
}

function clearCompleted(): void {
  const completedIds = tasks
    .filter((t) => t.status === "completed")
    .map((t) => t.id);
  tasks = tasks.filter((t) => t.status !== "completed");
  completedIds.forEach((id) => {
    stopProgressPolling(id);
    uploadedChunkSets.delete(id);
  });
}

function clearFailedAndCancelled(): void {
  const ids = tasks
    .filter((t) => t.status === "failed" || t.status === "cancelled")
    .map((t) => t.id);

  tasks = tasks.filter(
    (t) => t.status !== "failed" && t.status !== "cancelled",
  );

  ids.forEach((id) => {
    stopProgressPolling(id);
    uploadedChunkSets.delete(id);
  });
}

async function retryAllFailedAndCancelled(): Promise<number> {
  const ids = tasks
    .filter((t) => t.status === "failed" || t.status === "cancelled")
    .map((t) => t.id);

  for (const id of ids) {
    await retryUpload(id);
  }

  return ids.length;
}

async function cancelAllActiveAndProcessing(): Promise<number> {
  const ids = tasks
    .filter(
      (t) =>
        t.status === "uploading" ||
        t.status === "paused" ||
        t.status === "processing",
    )
    .map((t) => t.id);

  for (const id of ids) {
    await cancelUpload(id);
  }

  return ids.length;
}

// ===== Export Hook =====

export function useUpload() {
  return {
    // State
    get tasks() {
      return tasks;
    },
    get isUploading() {
      return activeTasks.length > 0;
    },
    get pendingTasks() {
      return pendingTasks;
    },
    get activeTasks() {
      return activeTasks;
    },
    get processingTasks() {
      return processingTasks;
    },
    get completedTasks() {
      return completedTasks;
    },
    get failedTasks() {
      return failedTasks;
    },
    get cancelledTasks() {
      return cancelledTasks;
    },
    get totalProgress() {
      return totalProgress;
    },

    // Actions
    addFile,
    addFiles,
    startUpload,
    pauseUpload,
    resumeUpload,
    cancelUpload,
    retryUpload,
    retryAllFailedAndCancelled,
    cancelAllActiveAndProcessing,
    removeTask,
    clearCompleted,
    clearFailedAndCancelled,
  };
}
