/**
 * File size utilities and browser capability detection
 * Provides limits, warnings, and feature detection for large file downloads
 */

// ===== File Size Constants =====

/** 1 GB in bytes */
export const GB = 1024 * 1024 * 1024;

/** 1 MB in bytes */
export const MB = 1024 * 1024;

/** 无文件选择器时允许的内存回退硬上限。 */
export const MAX_SAFE_INMEMORY_SIZE = 64 * MB;

/**
 * File size threshold for warning the user before download
 * Files above this size may cause issues on some devices
 */
export const LARGE_FILE_WARNING_THRESHOLD = MAX_SAFE_INMEMORY_SIZE;

/**
 * File size threshold where streaming is strongly recommended
 * At this size, in-memory download will likely fail on most devices
 */
export const STREAMING_RECOMMENDED_THRESHOLD = 500 * MB;

/** 超大文件分级阈值，仅用于提示文案，不放宽内存回退。 */
export const VERY_LARGE_FILE_THRESHOLD = 2 * GB;

/**
 * Absolute maximum file size we support for download
 * Beyond this, even streaming may have issues (browser/OS limits)
 */
export const MAX_DOWNLOADABLE_SIZE = 100 * GB;

// ===== File Size Classification =====

export type FileSizeCategory =
  | "small" // No issues expected
  | "medium" // May need warning
  | "large" // Needs streaming
  | "very_large" // Streaming required, extra warnings
  | "too_large"; // May not be downloadable

/**
 * Classify file size for download strategy decision
 */
export function classifyFileSize(sizeInBytes: number): FileSizeCategory {
  if (sizeInBytes <= LARGE_FILE_WARNING_THRESHOLD) {
    return "small";
  }
  if (sizeInBytes <= STREAMING_RECOMMENDED_THRESHOLD) {
    return "medium";
  }
  if (sizeInBytes <= VERY_LARGE_FILE_THRESHOLD) {
    return "large";
  }
  if (sizeInBytes <= MAX_DOWNLOADABLE_SIZE) {
    return "very_large";
  }
  return "too_large";
}

/**
 * Get human-readable file size
 */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < MB) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  if (bytes < GB) {
    return `${(bytes / MB).toFixed(1)} MB`;
  }
  return `${(bytes / GB).toFixed(2)} GB`;
}

// ===== Browser Capability Detection =====

export interface BrowserCapabilities {
  /** File System Access API (showSaveFilePicker) available */
  fileSystemAccess: boolean;
  /** Streams API available */
  streams: boolean;
  /** Estimated device memory in GB (or undefined if not available) */
  deviceMemory: number | undefined;
  /** IndexedDB available */
  indexedDB: boolean;
  /** Blob API available */
  blob: boolean;
}

/**
 * Detect browser capabilities for download strategy
 */
export function detectBrowserCapabilities(): BrowserCapabilities {
  const hasFileSystemAccess =
    typeof window !== "undefined" && "showSaveFilePicker" in window;

  const hasStreams =
    typeof window !== "undefined" &&
    typeof ReadableStream !== "undefined" &&
    typeof WritableStream !== "undefined";

  const deviceMemory =
    typeof navigator !== "undefined"
      ? (navigator as Navigator & { deviceMemory?: number }).deviceMemory
      : undefined;

  const hasIndexedDB =
    typeof window !== "undefined" && typeof indexedDB !== "undefined";

  const hasBlob = typeof Blob !== "undefined";

  return {
    fileSystemAccess: hasFileSystemAccess,
    streams: hasStreams,
    deviceMemory,
    indexedDB: hasIndexedDB,
    blob: hasBlob,
  };
}

/**
 * Check if streaming download is supported
 */
export function isStreamingSupported(): boolean {
  const caps = detectBrowserCapabilities();
  return caps.fileSystemAccess && caps.streams;
}

// ===== Download Strategy Decision =====

export type DownloadStrategy =
  | "inmemory" // Use current Blob-based approach
  | "streaming" // Use File System Access API streaming
  | "backend_proxy"; // Let backend handle large file streaming

export interface DownloadDecision {
  strategy: DownloadStrategy;
  requiresUserConfirmation: boolean;
  warningMessage: string | null;
  canProceed: boolean;
  reason: string;
}

/**
 * Decide the best download strategy based on file size and browser capabilities
 */
export function decideDownloadStrategy(
  fileSizeBytes: number,
  caps?: BrowserCapabilities,
): DownloadDecision {
  const capabilities = caps ?? detectBrowserCapabilities();
  const category = classifyFileSize(fileSizeBytes);
  const formattedSize = formatFileSize(fileSizeBytes);

  // File too large for any download method
  if (category === "too_large") {
    return {
      strategy: "backend_proxy",
      requiresUserConfirmation: true,
      warningMessage: `This file (${formattedSize}) exceeds the maximum downloadable size. Consider downloading in parts or using a desktop application.`,
      canProceed: false,
      reason: "File size exceeds maximum limit",
    };
  }

  // 小文件可以使用受控内存回退。
  if (category === "small") {
    return {
      strategy: "inmemory",
      requiresUserConfirmation: false,
      warningMessage: null,
      canProceed: true,
      reason: "Small file, in-memory download is safe",
    };
  }

  // Check if streaming is available
  const canStream = capabilities.fileSystemAccess && capabilities.streams;

  // Very large files - require streaming
  if (category === "very_large") {
    if (canStream) {
      return {
        strategy: "streaming",
        requiresUserConfirmation: true,
        warningMessage: `This is a large file (${formattedSize}). You will be prompted to choose a save location. The file will be downloaded directly to disk.`,
        canProceed: true,
        reason: "Large file with streaming support available",
      };
    }
    // No streaming support
    return {
      strategy: "backend_proxy",
      requiresUserConfirmation: true,
      warningMessage: `This file (${formattedSize}) is too large for your browser to download directly. Your browser does not support streaming downloads. Try using Chrome or Edge for better large file support.`,
      canProceed: false,
      reason: "Large file but browser does not support streaming",
    };
  }

  // Large files - prefer streaming if available
  if (category === "large") {
    if (canStream) {
      return {
        strategy: "streaming",
        requiresUserConfirmation: true,
        warningMessage: `This file (${formattedSize}) is large. For best results, you will be prompted to choose a save location and the file will download directly to disk.`,
        canProceed: true,
        reason: "Large file, using streaming for reliability",
      };
    }
    return {
      strategy: "backend_proxy",
      requiresUserConfirmation: true,
      warningMessage: `This file (${formattedSize}) exceeds the 64 MiB browser fallback limit. Use Chrome or Edge and choose a save location.`,
      canProceed: false,
      reason: "Large file but browser does not support bounded saving",
    };
  }

  // 超过内存回退上限时必须使用文件系统流式写入。
  if (category === "medium") {
    if (canStream) {
      return {
        strategy: "streaming",
        requiresUserConfirmation: true,
        warningMessage: `This file (${formattedSize}) will be written directly to disk.`,
        canProceed: true,
        reason: "File exceeds the in-memory fallback limit",
      };
    }
    return {
      strategy: "backend_proxy",
      requiresUserConfirmation: true,
      warningMessage: `This file (${formattedSize}) exceeds the 64 MiB browser fallback limit. Use Chrome or Edge and choose a save location.`,
      canProceed: false,
      reason: "Browser does not support bounded large-file saving",
    };
  }

  // Default fallback
  return {
    strategy: "inmemory",
    requiresUserConfirmation: false,
    warningMessage: null,
    canProceed: true,
    reason: "Default strategy",
  };
}

// ===== Utility for pre-download check =====

export interface PreDownloadCheck {
  canProceed: boolean;
  decision: DownloadDecision;
  capabilities: BrowserCapabilities;
}

/**
 * Perform pre-download check and return all relevant information
 */
export function performPreDownloadCheck(
  fileSizeBytes: number,
): PreDownloadCheck {
  const capabilities = detectBrowserCapabilities();
  const decision = decideDownloadStrategy(fileSizeBytes, capabilities);

  return {
    canProceed: decision.canProceed,
    decision,
    capabilities,
  };
}
