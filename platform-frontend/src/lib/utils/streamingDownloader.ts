/**
 * Streaming File Downloader
 *
 * Uses File System Access API to download large files directly to disk
 * without holding the entire file in memory.
 *
 * Browser Support:
 * - Chrome 86+
 * - Edge 86+
 * - Opera 72+
 * - Safari: Not supported (falls back to in-memory download)
 * - Firefox: Not supported (falls back to in-memory download)
 */

import { downloadChunkWithRetry } from "./chunkDownloader";
import { executeStreamingDecrypt } from "./crypto";
import { isStreamingSupported } from "./fileSize";

// ===== Types =====

/**
 * File System Access API types
 * Using minimal declarations to avoid conflicts with lib.dom.d.ts
 */
interface FileSystemFileHandleLocal {
  createWritable(): Promise<FileSystemWritableFileStreamLocal>;
  getFile(): Promise<File>;
  name: string;
}

interface FileSystemWritableFileStreamLocal {
  write(data: BufferSource | Blob | string): Promise<void>;
  seek(position: number): Promise<void>;
  truncate(size: number): Promise<void>;
  close(): Promise<void>;
}

interface SaveFilePickerOptionsLocal {
  suggestedName?: string;
  types?: Array<{
    description?: string;
    accept: Record<string, string[]>;
  }>;
  excludeAcceptAllOption?: boolean;
}

export interface StreamingDownloadOptions {
  /** File name suggestion for save dialog */
  fileName: string;
  /** MIME type for file picker filter */
  contentType: string;
  /** Total number of chunks */
  totalChunks: number;
  /** Initial decryption key (for last chunk) */
  initialKey: string;
  /** Presigned URLs for each chunk (ordered) */
  presignedUrls: string[];
  /** AbortSignal for cancellation */
  signal?: AbortSignal;
  /** Progress callback */
  onProgress?: (phase: StreamingPhase, current: number, total: number) => void;
}

export type StreamingPhase =
  | "preparing"
  | "downloading"
  | "decrypting"
  | "writing"
  | "completed";

export interface StreamingDownloadResult {
  success: boolean;
  error?: string;
  bytesWritten: number;
}

// ===== Helper Functions =====

/**
 * Get file extension from content type
 */
function getExtensionFromMimeType(mimeType: string): string {
  const mimeToExt: Record<string, string> = {
    "application/pdf": ".pdf",
    "application/zip": ".zip",
    "application/x-zip-compressed": ".zip",
    "application/msword": ".doc",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": ".docx",
    "application/vnd.ms-excel": ".xls",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": ".xlsx",
    "application/vnd.ms-powerpoint": ".ppt",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation": ".pptx",
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/gif": ".gif",
    "image/webp": ".webp",
    "video/mp4": ".mp4",
    "video/webm": ".webm",
    "audio/mpeg": ".mp3",
    "audio/wav": ".wav",
    "text/plain": ".txt",
    "text/html": ".html",
    "text/css": ".css",
    "text/javascript": ".js",
    "application/json": ".json",
    "application/xml": ".xml",
  };

  return mimeToExt[mimeType] || "";
}

/**
 * Build file type filters for save picker
 */
function buildFileTypeFilters(
  contentType: string,
  fileName: string,
): Array<{ description?: string; accept: Record<string, string[]> }> {
  const ext = fileName.includes(".")
    ? `.${fileName.split(".").pop()}`
    : getExtensionFromMimeType(contentType);

  if (!ext) {
    return [];
  }

  return [
    {
      description: "File",
      accept: {
        [contentType]: [ext],
      },
    },
  ];
}

/**
 * Convert Uint8Array to ArrayBuffer for write compatibility
 */
function toArrayBuffer(data: Uint8Array): ArrayBuffer {
  // Create a new ArrayBuffer and copy the data to avoid SharedArrayBuffer issues
  const buffer = new ArrayBuffer(data.byteLength);
  new Uint8Array(buffer).set(data);
  return buffer;
}

// ===== Main Streaming Download Function =====

/**
 * Execute streaming download using File System Access API
 *
 * Flow:
 * 1. Show save file picker to get write access
 * 2. Download and decrypt chunks in streaming fashion
 * 3. Write decrypted data directly to file as each chunk is processed
 */
export async function executeStreamingDownload(
  options: StreamingDownloadOptions,
): Promise<StreamingDownloadResult> {
  const {
    fileName,
    contentType,
    totalChunks,
    initialKey,
    presignedUrls,
    signal,
    onProgress,
  } = options;

  // Check if streaming is supported
  if (!isStreamingSupported()) {
    return {
      success: false,
      error: "Streaming download not supported in this browser",
      bytesWritten: 0,
    };
  }

  let writable: FileSystemWritableFileStreamLocal | null = null;
  let bytesWritten = 0;

  try {
    onProgress?.("preparing", 0, totalChunks);

    // Step 1: Show save file picker
    const showSaveFilePicker = (
      window as unknown as {
        showSaveFilePicker: (
          options?: SaveFilePickerOptionsLocal,
        ) => Promise<FileSystemFileHandleLocal>;
      }
    ).showSaveFilePicker;

    const fileHandle = await showSaveFilePicker({
      suggestedName: fileName,
      types: buildFileTypeFilters(contentType, fileName),
    });

    // Create writable stream
    writable = await fileHandle.createWritable();

    // Track downloaded chunks for monotonic progress reporting
    const downloadedChunkIndices = new Set<number>();

    // Step 2: Execute streaming decrypt and download
    await executeStreamingDecrypt(totalChunks, initialKey, {
      downloadChunk: async (index: number) => {
        // Check cancellation
        if (signal?.aborted) {
          throw new Error("Download cancelled");
        }

        // Download with retry
        const data = await downloadChunkWithRetry(presignedUrls[index], index, {
          signal,
        });

        downloadedChunkIndices.add(index);
        onProgress?.("downloading", downloadedChunkIndices.size, totalChunks);

        return data;
      },

      writeDecrypted: async (index: number, data: Uint8Array) => {
        // Check cancellation
        if (signal?.aborted) {
          throw new Error("Download cancelled");
        }

        onProgress?.("writing", index + 1, totalChunks);

        // Write to file - convert to ArrayBuffer for compatibility
        await writable!.write(toArrayBuffer(data));
        bytesWritten += data.length;
      },

      onProgress: (decrypted: number, total: number) => {
        onProgress?.("decrypting", decrypted, total);
      },

      isCancelled: () => signal?.aborted ?? false,
    });

    // Step 3: Close the file
    await writable.close();
    writable = null;

    onProgress?.("completed", totalChunks, totalChunks);

    return {
      success: true,
      bytesWritten,
    };
  } catch (error) {
    const err = error as Error;

    // Clean up
    if (writable) {
      try {
        await writable.close();
      } catch {
        // Ignore close errors
      }
    }

    // User cancelled the save picker
    if (err.name === "AbortError") {
      return {
        success: false,
        error: "File save cancelled by user",
        bytesWritten,
      };
    }

    // Download was cancelled
    if (signal?.aborted || err.message === "Download cancelled") {
      return {
        success: false,
        error: "Download cancelled",
        bytesWritten,
      };
    }

    return {
      success: false,
      error: err.message,
      bytesWritten,
    };
  }
}

// ===== Alternative: Buffered Streaming for Out-of-Order Writes =====

/**
 * Buffered streaming download that handles out-of-order chunk writes
 * This version buffers decrypted chunks and writes them in correct order
 *
 * Memory usage: O(1 chunk) most of the time, O(2 chunks) briefly during last chunk processing
 */
export async function executeBufferedStreamingDownload(
  options: StreamingDownloadOptions,
): Promise<StreamingDownloadResult> {
  const {
    fileName,
    contentType,
    totalChunks,
    initialKey,
    presignedUrls,
    signal,
    onProgress,
  } = options;

  if (!isStreamingSupported()) {
    return {
      success: false,
      error: "Streaming download not supported in this browser",
      bytesWritten: 0,
    };
  }

  let writable: FileSystemWritableFileStreamLocal | null = null;
  let bytesWritten = 0;

  // Buffer for holding decrypted chunks until they can be written in order
  const decryptedBuffer: Map<number, Uint8Array> = new Map();
  let nextWriteIndex = 0;
  const downloadedChunkIndices = new Set<number>();

  try {
    onProgress?.("preparing", 0, totalChunks);

    // Show save file picker
    const showSaveFilePicker = (
      window as unknown as {
        showSaveFilePicker: (
          options?: SaveFilePickerOptionsLocal,
        ) => Promise<FileSystemFileHandleLocal>;
      }
    ).showSaveFilePicker;

    const fileHandle = await showSaveFilePicker({
      suggestedName: fileName,
      types: buildFileTypeFilters(contentType, fileName),
    });

    writable = await fileHandle.createWritable();

    // Helper to flush buffered chunks in order
    const flushBuffer = async () => {
      while (decryptedBuffer.has(nextWriteIndex)) {
        const data = decryptedBuffer.get(nextWriteIndex)!;
        decryptedBuffer.delete(nextWriteIndex);

        await writable!.write(toArrayBuffer(data));
        bytesWritten += data.length;
        nextWriteIndex++;
      }
    };

    // Execute streaming decrypt
    await executeStreamingDecrypt(totalChunks, initialKey, {
      downloadChunk: async (index: number) => {
        if (signal?.aborted) {
          throw new Error("Download cancelled");
        }

        const data = await downloadChunkWithRetry(presignedUrls[index], index, {
          signal,
        });

        downloadedChunkIndices.add(index);
        onProgress?.("downloading", downloadedChunkIndices.size, totalChunks);

        return data;
      },

      writeDecrypted: async (index: number, data: Uint8Array) => {
        if (signal?.aborted) {
          throw new Error("Download cancelled");
        }

        onProgress?.("writing", index + 1, totalChunks);

        // If this is the next chunk to write, write it directly
        if (index === nextWriteIndex) {
          await writable!.write(toArrayBuffer(data));
          bytesWritten += data.length;
          nextWriteIndex++;

          // Try to flush any buffered chunks
          await flushBuffer();
        } else {
          // Buffer for later
          decryptedBuffer.set(index, data);
        }
      },

      onProgress: (decrypted: number, total: number) => {
        onProgress?.("decrypting", decrypted, total);
      },

      isCancelled: () => signal?.aborted ?? false,
    });

    // Flush any remaining buffered data
    await flushBuffer();

    // Verify all chunks were written
    if (nextWriteIndex !== totalChunks) {
      throw new Error(
        `Incomplete write: expected ${totalChunks} chunks, wrote ${nextWriteIndex}`,
      );
    }

    await writable.close();
    writable = null;

    onProgress?.("completed", totalChunks, totalChunks);

    return {
      success: true,
      bytesWritten,
    };
  } catch (error) {
    const err = error as Error;

    if (writable) {
      try {
        await writable.close();
      } catch {
        // Ignore
      }
    }

    if (err.name === "AbortError") {
      return {
        success: false,
        error: "File save cancelled by user",
        bytesWritten,
      };
    }

    if (signal?.aborted || err.message === "Download cancelled") {
      return {
        success: false,
        error: "Download cancelled",
        bytesWritten,
      };
    }

    return {
      success: false,
      error: err.message,
      bytesWritten,
    };
  }
}
