import { describe, it, expect, vi, afterEach } from "vitest";
import {
  GB,
  MB,
  LARGE_FILE_WARNING_THRESHOLD,
  STREAMING_RECOMMENDED_THRESHOLD,
  MAX_SAFE_INMEMORY_SIZE,
  MAX_DOWNLOADABLE_SIZE,
  classifyFileSize,
  formatFileSize,
  detectBrowserCapabilities,
  isStreamingSupported,
  decideDownloadStrategy,
  performPreDownloadCheck,
} from "./fileSize";

describe("fileSize utilities", () => {
  const originalShowSaveFilePicker = (
    window as unknown as Record<string, unknown>
  ).showSaveFilePicker;

  afterEach(() => {
    vi.unstubAllGlobals();

    if (originalShowSaveFilePicker) {
      Object.defineProperty(window, "showSaveFilePicker", {
        value: originalShowSaveFilePicker,
        configurable: true,
      });
    } else {
      delete (window as unknown as Record<string, unknown>).showSaveFilePicker;
    }

    delete (navigator as unknown as Record<string, unknown>).deviceMemory;
  });

  it("should classify file sizes by thresholds", () => {
    expect(classifyFileSize(LARGE_FILE_WARNING_THRESHOLD)).toBe("small");
    expect(classifyFileSize(LARGE_FILE_WARNING_THRESHOLD + 1)).toBe("medium");

    expect(classifyFileSize(STREAMING_RECOMMENDED_THRESHOLD)).toBe("medium");
    expect(classifyFileSize(STREAMING_RECOMMENDED_THRESHOLD + 1)).toBe("large");

    expect(classifyFileSize(MAX_SAFE_INMEMORY_SIZE)).toBe("large");
    expect(classifyFileSize(MAX_SAFE_INMEMORY_SIZE + 1)).toBe("very_large");

    expect(classifyFileSize(MAX_DOWNLOADABLE_SIZE)).toBe("very_large");
    expect(classifyFileSize(MAX_DOWNLOADABLE_SIZE + 1)).toBe("too_large");
  });

  it("should format file sizes", () => {
    expect(formatFileSize(0)).toBe("0 B");
    expect(formatFileSize(1023)).toBe("1023 B");
    expect(formatFileSize(1024)).toBe("1.0 KB");
    expect(formatFileSize(MB)).toBe("1.0 MB");
    expect(formatFileSize(GB)).toBe("1.00 GB");
  });

  it("should detect browser capabilities", () => {
    Object.defineProperty(window, "showSaveFilePicker", {
      value: vi.fn(),
      configurable: true,
    });
    Object.defineProperty(navigator, "deviceMemory", {
      value: 8,
      configurable: true,
    });

    const caps = detectBrowserCapabilities();
    expect(caps.fileSystemAccess).toBe(true);
    expect(caps.streams).toBe(true);
    expect(caps.deviceMemory).toBe(8);
    expect(caps.indexedDB).toBe(true);
    expect(caps.blob).toBe(true);
  });

  it("should report streaming support based on file system access + streams", () => {
    // Default: no showSaveFilePicker in jsdom, so no streaming
    delete (window as unknown as Record<string, unknown>).showSaveFilePicker;
    expect(isStreamingSupported()).toBe(false);

    Object.defineProperty(window, "showSaveFilePicker", {
      value: vi.fn(),
      configurable: true,
    });
    expect(isStreamingSupported()).toBe(true);
  });

  it("should decide download strategy for small and too-large files", () => {
    const small = decideDownloadStrategy(1024, {
      fileSystemAccess: false,
      streams: false,
      deviceMemory: undefined,
      indexedDB: true,
      blob: true,
    });
    expect(small.strategy).toBe("inmemory");
    expect(small.canProceed).toBe(true);
    expect(small.warningMessage).toBeNull();
    expect(small.requiresUserConfirmation).toBe(false);

    const tooLarge = decideDownloadStrategy(MAX_DOWNLOADABLE_SIZE + 1, {
      fileSystemAccess: true,
      streams: true,
      deviceMemory: 16,
      indexedDB: true,
      blob: true,
    });
    expect(tooLarge.strategy).toBe("backend_proxy");
    expect(tooLarge.canProceed).toBe(false);
    expect(tooLarge.warningMessage).not.toBeNull();
    expect(tooLarge.requiresUserConfirmation).toBe(true);
  });

  it("should prefer streaming for large files when available", () => {
    const large = decideDownloadStrategy(STREAMING_RECOMMENDED_THRESHOLD + 1, {
      fileSystemAccess: true,
      streams: true,
      deviceMemory: 16,
      indexedDB: true,
      blob: true,
    });
    expect(large.strategy).toBe("streaming");
    expect(large.canProceed).toBe(true);
    expect(large.requiresUserConfirmation).toBe(true);
    expect(large.warningMessage).not.toBeNull();
  });

  it("should fall back to in-memory for large files when streaming is unavailable", () => {
    const large = decideDownloadStrategy(STREAMING_RECOMMENDED_THRESHOLD + 1, {
      fileSystemAccess: false,
      streams: false,
      deviceMemory: 2,
      indexedDB: true,
      blob: true,
    });
    expect(large.strategy).toBe("inmemory");
    expect(large.canProceed).toBe(true);
    expect(large.requiresUserConfirmation).toBe(true);
    expect(large.warningMessage).toContain("2GB");
  });

  it("should warn on medium files for low-memory devices", () => {
    const medium = decideDownloadStrategy(LARGE_FILE_WARNING_THRESHOLD + 1, {
      fileSystemAccess: true,
      streams: true,
      deviceMemory: 2,
      indexedDB: true,
      blob: true,
    });
    expect(medium.strategy).toBe("streaming");
    expect(medium.canProceed).toBe(true);
    expect(medium.requiresUserConfirmation).toBe(true);
    expect(medium.warningMessage).toContain("2GB");
  });

  it("should allow medium files without confirmation for normal devices", () => {
    const medium = decideDownloadStrategy(LARGE_FILE_WARNING_THRESHOLD + 1, {
      fileSystemAccess: false,
      streams: false,
      deviceMemory: 8,
      indexedDB: true,
      blob: true,
    });
    expect(medium.strategy).toBe("inmemory");
    expect(medium.canProceed).toBe(true);
    expect(medium.requiresUserConfirmation).toBe(false);
    expect(medium.warningMessage).toBeNull();
  });

  it("should run a pre-download check with detected capabilities", () => {
    const check = performPreDownloadCheck(1024);
    expect(check.canProceed).toBe(true);
    expect(check.decision.strategy).toBe("inmemory");
  });
});
