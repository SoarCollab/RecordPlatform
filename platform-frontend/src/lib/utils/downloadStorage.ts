/**
 * IndexedDB persistence layer for download tasks
 * Enables resumable downloads by persisting task metadata and chunk data
 */

import { browser } from "$app/environment";

// ===== Types =====

export interface PersistedDownloadTask {
  id: string;
  fileHash: string;
  fileName: string;
  fileSize: number;
  contentType: string;
  totalChunks: number;
  initialKey: string | null;
  source: DownloadSource;
  presignedUrls: string[];
  urlsFetchedAt: number | null;
  createdAt: number;
}

export interface DownloadSource {
  type: "owned" | "public_share" | "private_share";
  shareCode?: string;
}

export interface PersistedChunk {
  taskId: string;
  chunkIndex: number;
  data: ArrayBuffer;
  downloadedAt: number;
}

// ===== Constants =====

const DB_NAME = "rp_downloads";
const DB_VERSION = 1;
const TASKS_STORE = "tasks";
const CHUNKS_STORE = "chunks";
const EXPIRY_DAYS = 7; // Auto-cleanup after 7 days

// ===== Database Management =====

let dbPromise: Promise<IDBDatabase> | null = null;

/**
 * Initialize and open IndexedDB connection
 */
function openDB(): Promise<IDBDatabase> {
  if (!browser) {
    return Promise.reject(new Error("IndexedDB is only available in browser"));
  }

  if (dbPromise) {
    return dbPromise;
  }

  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onerror = () => {
      dbPromise = null;
      reject(new Error(`Failed to open IndexedDB: ${request.error?.message}`));
    };

    request.onsuccess = () => {
      resolve(request.result);
    };

    request.onupgradeneeded = (event) => {
      const db = (event.target as IDBOpenDBRequest).result;

      // Tasks store
      if (!db.objectStoreNames.contains(TASKS_STORE)) {
        const taskStore = db.createObjectStore(TASKS_STORE, { keyPath: "id" });
        taskStore.createIndex("fileHash", "fileHash", { unique: false });
        taskStore.createIndex("createdAt", "createdAt", { unique: false });
      }

      // Chunks store with composite key
      if (!db.objectStoreNames.contains(CHUNKS_STORE)) {
        const chunkStore = db.createObjectStore(CHUNKS_STORE, {
          keyPath: ["taskId", "chunkIndex"],
        });
        chunkStore.createIndex("taskId", "taskId", { unique: false });
        chunkStore.createIndex("downloadedAt", "downloadedAt", { unique: false });
      }
    };
  });

  return dbPromise;
}

// ===== Task Operations =====

/**
 * Save download task metadata
 */
export async function saveTask(task: PersistedDownloadTask): Promise<void> {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(TASKS_STORE, "readwrite");
    const store = tx.objectStore(TASKS_STORE);
    const request = store.put(task);

    request.onsuccess = () => resolve();
    request.onerror = () => reject(new Error(`Failed to save task: ${request.error?.message}`));
  });
}

/**
 * Get task by ID
 */
export async function getTask(taskId: string): Promise<PersistedDownloadTask | null> {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(TASKS_STORE, "readonly");
    const store = tx.objectStore(TASKS_STORE);
    const request = store.get(taskId);

    request.onsuccess = () => resolve(request.result || null);
    request.onerror = () => reject(new Error(`Failed to get task: ${request.error?.message}`));
  });
}

/**
 * Get all pending (incomplete) tasks
 */
export async function getPendingTasks(): Promise<PersistedDownloadTask[]> {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(TASKS_STORE, "readonly");
    const store = tx.objectStore(TASKS_STORE);
    const request = store.getAll();

    request.onsuccess = () => resolve(request.result || []);
    request.onerror = () => reject(new Error(`Failed to get pending tasks: ${request.error?.message}`));
  });
}

/**
 * Delete task metadata
 */
export async function deleteTask(taskId: string): Promise<void> {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(TASKS_STORE, "readwrite");
    const store = tx.objectStore(TASKS_STORE);
    const request = store.delete(taskId);

    request.onsuccess = () => resolve();
    request.onerror = () => reject(new Error(`Failed to delete task: ${request.error?.message}`));
  });
}

// ===== Chunk Operations =====

/**
 * Save a downloaded chunk
 */
export async function saveChunk(
  taskId: string,
  chunkIndex: number,
  data: Uint8Array,
): Promise<void> {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(CHUNKS_STORE, "readwrite");
    const store = tx.objectStore(CHUNKS_STORE);

    const chunk: PersistedChunk = {
      taskId,
      chunkIndex,
      data: data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) as ArrayBuffer,
      downloadedAt: Date.now(),
    };

    const request = store.put(chunk);

    request.onsuccess = () => resolve();
    request.onerror = () => reject(new Error(`Failed to save chunk: ${request.error?.message}`));
  });
}

/**
 * Get all downloaded chunks for a task
 * Returns a Map of chunkIndex -> Uint8Array
 */
export async function getChunks(taskId: string): Promise<Map<number, Uint8Array>> {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(CHUNKS_STORE, "readonly");
    const store = tx.objectStore(CHUNKS_STORE);
    const index = store.index("taskId");
    const request = index.getAll(taskId);

    request.onsuccess = () => {
      const chunks = request.result as PersistedChunk[];
      const map = new Map<number, Uint8Array>();
      for (const chunk of chunks) {
        map.set(chunk.chunkIndex, new Uint8Array(chunk.data));
      }
      resolve(map);
    };

    request.onerror = () => reject(new Error(`Failed to get chunks: ${request.error?.message}`));
  });
}

/**
 * Get count of downloaded chunks for a task
 */
export async function getChunkCount(taskId: string): Promise<number> {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(CHUNKS_STORE, "readonly");
    const store = tx.objectStore(CHUNKS_STORE);
    const index = store.index("taskId");
    const request = index.count(taskId);

    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(new Error(`Failed to count chunks: ${request.error?.message}`));
  });
}

/**
 * Delete all chunks for a task
 */
export async function deleteChunks(taskId: string): Promise<void> {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(CHUNKS_STORE, "readwrite");
    const store = tx.objectStore(CHUNKS_STORE);
    const index = store.index("taskId");
    const request = index.openCursor(taskId);

    request.onsuccess = (event) => {
      const cursor = (event.target as IDBRequest<IDBCursorWithValue>).result;
      if (cursor) {
        cursor.delete();
        cursor.continue();
      }
    };

    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(new Error(`Failed to delete chunks: ${tx.error?.message}`));
  });
}

// ===== Cleanup Operations =====

/**
 * Clear all data for a completed/cancelled task
 */
export async function clearTaskData(taskId: string): Promise<void> {
  await deleteChunks(taskId);
  await deleteTask(taskId);
}

/**
 * Clean up expired data (tasks older than EXPIRY_DAYS)
 */
export async function cleanupExpiredData(): Promise<number> {
  const db = await openDB();
  const expiryTime = Date.now() - EXPIRY_DAYS * 24 * 60 * 60 * 1000;
  let cleanedCount = 0;

  return new Promise((resolve, reject) => {
    const tx = db.transaction([TASKS_STORE, CHUNKS_STORE], "readwrite");
    const taskStore = tx.objectStore(TASKS_STORE);
    const chunkStore = tx.objectStore(CHUNKS_STORE);
    const index = taskStore.index("createdAt");

    const request = index.openCursor(IDBKeyRange.upperBound(expiryTime));

    request.onsuccess = (event) => {
      const cursor = (event.target as IDBRequest<IDBCursorWithValue>).result;
      if (cursor) {
        const task = cursor.value as PersistedDownloadTask;

        // Delete associated chunks
        const chunkIndex = chunkStore.index("taskId");
        const chunkRequest = chunkIndex.openCursor(task.id);
        chunkRequest.onsuccess = (e) => {
          const chunkCursor = (e.target as IDBRequest<IDBCursorWithValue>).result;
          if (chunkCursor) {
            chunkCursor.delete();
            chunkCursor.continue();
          }
        };

        // Delete task
        cursor.delete();
        cleanedCount++;
        cursor.continue();
      }
    };

    tx.oncomplete = () => resolve(cleanedCount);
    tx.onerror = () => reject(new Error(`Failed to cleanup expired data: ${tx.error?.message}`));
  });
}

/**
 * Check if IndexedDB is available and working
 */
export async function isStorageAvailable(): Promise<boolean> {
  if (!browser) return false;

  try {
    await openDB();
    return true;
  } catch {
    return false;
  }
}

/**
 * Get storage usage estimate
 */
export async function getStorageUsage(): Promise<{ used: number; quota: number } | null> {
  if (!browser || !navigator.storage?.estimate) {
    return null;
  }

  try {
    const estimate = await navigator.storage.estimate();
    return {
      used: estimate.usage || 0,
      quota: estimate.quota || 0,
    };
  } catch {
    return null;
  }
}
