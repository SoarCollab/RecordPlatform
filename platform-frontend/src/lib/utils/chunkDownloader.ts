/**
 * Concurrent chunk downloader with retry support
 * Downloads file chunks from presigned S3 URLs with controlled concurrency
 */

// ===== Types =====

export interface ChunkDownloadResult {
  index: number;
  data: Uint8Array;
}

export interface DownloadProgress {
  completed: number;
  total: number;
  currentChunk: number;
  bytesDownloaded: number;
}

export interface DownloadAllChunksOptions {
  concurrency?: number;
  maxRetries?: number;
  retryDelayMs?: number;
  onChunkComplete?: (result: ChunkDownloadResult) => void;
  onProgress?: (progress: DownloadProgress) => void;
  signal?: AbortSignal;
  existingChunks?: Map<number, Uint8Array>;
}

// ===== Constants =====

const DEFAULT_CONCURRENCY = 3;
const DEFAULT_MAX_RETRIES = 3;
const DEFAULT_RETRY_DELAY_MS = 1000;

// ===== Helper Functions =====

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function calculateRetryDelay(attempt: number, baseDelay: number): number {
  // Exponential backoff: 1s, 2s, 4s...
  return baseDelay * Math.pow(2, attempt);
}

// ===== Single Chunk Download =====

/**
 * Download a single chunk from a presigned URL
 * @param url Presigned S3 URL
 * @param signal AbortSignal for cancellation
 * @returns Chunk data as Uint8Array
 */
export async function downloadChunk(
  url: string,
  signal?: AbortSignal,
): Promise<Uint8Array> {
  const response = await fetch(url, {
    method: "GET",
    signal,
    // Presigned URL already contains authentication
  });

  if (!response.ok) {
    throw new Error(`Download failed: ${response.status} ${response.statusText}`);
  }

  const arrayBuffer = await response.arrayBuffer();
  return new Uint8Array(arrayBuffer);
}

/**
 * Download a chunk with retry support
 * @param url Presigned S3 URL
 * @param chunkIndex Chunk index for error reporting
 * @param options Retry options
 * @returns Chunk data as Uint8Array
 */
export async function downloadChunkWithRetry(
  url: string,
  chunkIndex: number,
  options: {
    maxRetries?: number;
    retryDelayMs?: number;
    signal?: AbortSignal;
  } = {},
): Promise<Uint8Array> {
  const maxRetries = options.maxRetries ?? DEFAULT_MAX_RETRIES;
  const retryDelayMs = options.retryDelayMs ?? DEFAULT_RETRY_DELAY_MS;
  const signal = options.signal;

  let lastError: Error | null = null;

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    // Check if cancelled
    if (signal?.aborted) {
      throw new Error("Download cancelled");
    }

    try {
      return await downloadChunk(url, signal);
    } catch (error) {
      lastError = error as Error;

      // Don't retry if cancelled
      if (signal?.aborted || lastError.message === "Download cancelled") {
        throw lastError;
      }

      // Don't retry on last attempt
      if (attempt < maxRetries) {
        const delay = calculateRetryDelay(attempt, retryDelayMs);
        await sleep(delay);
      }
    }
  }

  throw new Error(`Chunk ${chunkIndex} download failed after ${maxRetries + 1} attempts: ${lastError?.message}`);
}

// ===== Concurrent Download =====

/**
 * Download all chunks concurrently with controlled parallelism
 * @param urls Array of presigned S3 URLs (ordered by chunk index)
 * @param options Download options
 * @returns Array of chunk data (ordered by chunk index)
 */
export async function downloadAllChunks(
  urls: string[],
  options: DownloadAllChunksOptions = {},
): Promise<Uint8Array[]> {
  const {
    concurrency = DEFAULT_CONCURRENCY,
    maxRetries = DEFAULT_MAX_RETRIES,
    retryDelayMs = DEFAULT_RETRY_DELAY_MS,
    onChunkComplete,
    onProgress,
    signal,
    existingChunks = new Map(),
  } = options;

  const totalChunks = urls.length;
  const results: Uint8Array[] = new Array(totalChunks);
  let completedCount = existingChunks.size;
  let bytesDownloaded = 0;

  // Initialize with existing chunks
  for (const [index, data] of existingChunks) {
    results[index] = data;
    bytesDownloaded += data.length;
  }

  // Build queue of chunks to download
  const queue: number[] = [];
  for (let i = 0; i < totalChunks; i++) {
    if (!existingChunks.has(i)) {
      queue.push(i);
    }
  }

  // Report initial progress
  if (onProgress && completedCount > 0) {
    onProgress({
      completed: completedCount,
      total: totalChunks,
      currentChunk: -1,
      bytesDownloaded,
    });
  }

  // If all chunks already downloaded, return immediately
  if (queue.length === 0) {
    return results;
  }

  // Track errors for reporting
  const errors: Map<number, Error> = new Map();

  // Worker function to process queue
  const processQueue = async (): Promise<void> => {
    while (queue.length > 0) {
      // Check if cancelled
      if (signal?.aborted) {
        throw new Error("Download cancelled");
      }

      const chunkIndex = queue.shift()!;
      const url = urls[chunkIndex];

      try {
        // Report current chunk
        if (onProgress) {
          onProgress({
            completed: completedCount,
            total: totalChunks,
            currentChunk: chunkIndex,
            bytesDownloaded,
          });
        }

        // Download chunk
        const data = await downloadChunkWithRetry(url, chunkIndex, {
          maxRetries,
          retryDelayMs,
          signal,
        });

        // Store result
        results[chunkIndex] = data;
        completedCount++;
        bytesDownloaded += data.length;

        // Report completion
        if (onChunkComplete) {
          onChunkComplete({ index: chunkIndex, data });
        }

        if (onProgress) {
          onProgress({
            completed: completedCount,
            total: totalChunks,
            currentChunk: chunkIndex,
            bytesDownloaded,
          });
        }
      } catch (error) {
        errors.set(chunkIndex, error as Error);
        // Re-throw to stop this worker
        throw error;
      }
    }
  };

  // Start concurrent workers
  const workers: Promise<void>[] = [];
  const workerCount = Math.min(concurrency, queue.length);

  for (let i = 0; i < workerCount; i++) {
    workers.push(processQueue());
  }

  // Wait for all workers
  try {
    await Promise.all(workers);
  } catch (error) {
    // If any worker fails, check if it's cancellation
    if (signal?.aborted) {
      throw new Error("Download cancelled");
    }

    // Report which chunks failed
    if (errors.size > 0) {
      const failedChunks = Array.from(errors.keys()).join(", ");
      throw new Error(`Failed to download chunks: ${failedChunks}`);
    }

    throw error;
  }

  // Verify all chunks downloaded
  for (let i = 0; i < totalChunks; i++) {
    if (!results[i]) {
      throw new Error(`Missing chunk at index ${i}`);
    }
  }

  return results;
}

/**
 * Create an AbortController with timeout
 * @param timeoutMs Timeout in milliseconds
 * @returns AbortController
 */
export function createTimeoutController(timeoutMs: number): AbortController {
  const controller = new AbortController();
  setTimeout(() => controller.abort(), timeoutMs);
  return controller;
}

/**
 * Merge multiple AbortSignals into one
 * @param signals Array of AbortSignals
 * @returns Combined AbortController
 */
export function mergeAbortSignals(...signals: (AbortSignal | undefined)[]): AbortController {
  const controller = new AbortController();

  for (const signal of signals) {
    if (signal?.aborted) {
      controller.abort();
      return controller;
    }

    signal?.addEventListener("abort", () => controller.abort());
  }

  return controller;
}
