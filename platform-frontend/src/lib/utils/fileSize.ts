/**
 * File size utilities and browser capability detection
 * Provides limits, warnings, and feature detection for large file downloads
 */

// ===== File Size Constants =====

/** 1 GB in bytes */
export const GB = 1024 * 1024 * 1024;

/** 1 MB in bytes */
export const MB = 1024 * 1024;

/**
 * Maximum safe file size for in-memory download (Blob-based)
 * Beyond this, we need streaming download to avoid memory issues
 */
export const MAX_SAFE_INMEMORY_SIZE = 2 * GB;

/**
 * File size threshold for warning the user before download
 * Files above this size may cause issues on some devices
 */
export const LARGE_FILE_WARNING_THRESHOLD = 500 * MB;

/**
 * File size threshold where streaming is strongly recommended
 * At this size, in-memory download will likely fail on most devices
 */
export const STREAMING_RECOMMENDED_THRESHOLD = 1 * GB;

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
  if (sizeInBytes <= MAX_SAFE_INMEMORY_SIZE) {
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

  // Small files - always use in-memory
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
    // Fall back to in-memory with warning
    const memoryWarning = capabilities.deviceMemory
      ? ` Your device has ${capabilities.deviceMemory}GB of memory.`
      : "";
    return {
      strategy: "inmemory",
      requiresUserConfirmation: true,
      warningMessage: `This file (${formattedSize}) is large and may cause performance issues or fail.${memoryWarning} For better large file support, try Chrome or Edge.`,
      canProceed: true,
      reason: "Large file, falling back to in-memory (no streaming support)",
    };
  }

  // Medium files - use in-memory with optional warning
  if (category === "medium") {
    // Check if device memory is low
    const lowMemory = capabilities.deviceMemory && capabilities.deviceMemory < 4;
    if (lowMemory) {
      return {
        strategy: canStream ? "streaming" : "inmemory",
        requiresUserConfirmation: true,
        warningMessage: `This file (${formattedSize}) may be challenging for your device with ${capabilities.deviceMemory}GB memory.`,
        canProceed: true,
        reason: "Medium file on low-memory device",
      };
    }
    return {
      strategy: "inmemory",
      requiresUserConfirmation: false,
      warningMessage: null,
      canProceed: true,
      reason: "Medium file, in-memory download should work",
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
export function performPreDownloadCheck(fileSizeBytes: number): PreDownloadCheck {
  const capabilities = detectBrowserCapabilities();
  const decision = decideDownloadStrategy(fileSizeBytes, capabilities);

  return {
    canProceed: decision.canProceed,
    decision,
    capabilities,
  };
}
