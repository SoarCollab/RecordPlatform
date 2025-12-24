import * as uploadApi from "$api/endpoints/upload";

// ===== Types =====

export type UploadStatus =
  | "pending"
  | "uploading"
  | "paused"
  | "completed"
  | "failed"
  | "cancelled";

export interface UploadTask {
  id: string;
  file: File;
  clientId: string | null;
  status: UploadStatus;
  progress: number; // 0-100
  uploadedChunks: number[];
  totalChunks: number;
  speed: number; // bytes/sec
  error: string | null;
  startTime: number | null;
  endTime: number | null;
}

// ===== Configuration =====

const DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024; // 5MB
const MAX_CONCURRENT_UPLOADS = 3;
const MAX_CONCURRENT_CHUNKS = 3;

// ===== State =====

let tasks = $state<UploadTask[]>([]);

// Track uploaded chunks per task using Set for thread-safe concurrent updates
const uploadedChunkSets = new Map<string, Set<number>>();

// ===== Derived =====

const pendingTasks = $derived(tasks.filter((t) => t.status === "pending"));
const activeTasks = $derived(tasks.filter((t) => t.status === "uploading"));
const completedTasks = $derived(tasks.filter((t) => t.status === "completed"));
const failedTasks = $derived(tasks.filter((t) => t.status === "failed"));
const totalProgress = $derived(
  tasks.length > 0
    ? Math.round(tasks.reduce((sum, t) => sum + t.progress, 0) / tasks.length)
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
  chunkSize: number = DEFAULT_CHUNK_SIZE,
): number {
  return Math.ceil(fileSize / chunkSize);
}

// ===== Upload Logic =====

async function uploadChunks(task: UploadTask): Promise<void> {
  if (!task.clientId) return;

  const chunkSize = DEFAULT_CHUNK_SIZE;
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
    updateTask(task.id, {
      uploadedChunks: Array.from(chunkSet),
      progress: Math.round((chunkSet.size / task.totalChunks) * 100),
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
  const totalChunks = calculateChunks(file.size);

  const task: UploadTask = {
    id,
    file,
    clientId: null,
    status: "pending",
    progress: 0,
    uploadedChunks: [],
    totalChunks,
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

  updateTask(id, { status: "uploading", startTime: Date.now(), error: null });

  try {
    // Initialize upload
    const result = await uploadApi.startUpload({
      fileName: task.file.name,
      fileSize: task.file.size,
      contentType: task.file.type || "application/octet-stream",
      chunkSize: DEFAULT_CHUNK_SIZE,
      totalChunks: task.totalChunks,
    });

    updateTask(id, {
      clientId: result.clientId,
      uploadedChunks: result.processedChunks,
      progress: Math.round(
        (result.processedChunks.length / task.totalChunks) * 100,
      ),
    });

    // Initialize chunk set with server-reported processed chunks
    uploadedChunkSets.set(id, new Set(result.processedChunks));

    // Upload chunks
    await uploadChunks(tasks.find((t) => t.id === id)!);

    // Check final status
    const finalTask = tasks.find((t) => t.id === id);
    if (finalTask?.status === "uploading") {
      // Complete upload
      await uploadApi.completeUpload(finalTask.clientId!);
      updateTask(id, {
        status: "completed",
        progress: 100,
        endTime: Date.now(),
      });
    }
  } catch (err) {
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
  await uploadChunks(tasks.find((t) => t.id === id)!);

  const finalTask = tasks.find((t) => t.id === id);
  if (finalTask?.status === "uploading") {
    await uploadApi.completeUpload(finalTask.clientId!);
    updateTask(id, { status: "completed", progress: 100, endTime: Date.now() });
  }
}

async function cancelUpload(id: string): Promise<void> {
  const task = tasks.find((t) => t.id === id);
  if (!task) return;

  updateTask(id, { status: "cancelled", endTime: Date.now() });

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

  // Clear chunk tracking for fresh start
  uploadedChunkSets.delete(id);

  updateTask(id, {
    status: "pending",
    progress: 0,
    uploadedChunks: [],
    clientId: null,
    error: null,
  });

  await startUpload(id);
}

function removeTask(id: string): void {
  const task = tasks.find((t) => t.id === id);
  if (task?.status === "uploading") {
    cancelUpload(id);
  }
  tasks = tasks.filter((t) => t.id !== id);
  // Clean up chunk tracking
  uploadedChunkSets.delete(id);
}

function clearCompleted(): void {
  const completedIds = tasks
    .filter((t) => t.status === "completed")
    .map((t) => t.id);
  tasks = tasks.filter((t) => t.status !== "completed");
  // Clean up chunk tracking for completed tasks
  completedIds.forEach((id) => uploadedChunkSets.delete(id));
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
    get completedTasks() {
      return completedTasks;
    },
    get failedTasks() {
      return failedTasks;
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
    removeTask,
    clearCompleted,
  };
}
