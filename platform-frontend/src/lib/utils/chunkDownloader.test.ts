import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  downloadChunk,
  downloadChunkWithRetry,
  downloadAllChunks,
  createTimeoutController,
  mergeAbortSignals,
  type DownloadProgress,
  type ChunkDownloadResult,
} from "./chunkDownloader";

describe("chunkDownloader", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("downloadChunk", () => {
    it("should download and return Uint8Array on success", async () => {
      const mockData = new Uint8Array([1, 2, 3, 4]);
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        arrayBuffer: () => Promise.resolve(mockData.buffer),
      });

      const result = await downloadChunk("https://example.com/chunk");

      expect(fetch).toHaveBeenCalledWith("https://example.com/chunk", {
        method: "GET",
        signal: undefined,
      });
      expect(result).toBeInstanceOf(Uint8Array);
      expect(result).toEqual(mockData);
    });

    it("should throw error on non-ok response", async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        statusText: "Not Found",
      });

      await expect(downloadChunk("https://example.com/chunk")).rejects.toThrow(
        "Download failed: 404 Not Found"
      );
    });

    it("should pass abort signal to fetch", async () => {
      const controller = new AbortController();
      const mockData = new Uint8Array([1, 2, 3]);
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        arrayBuffer: () => Promise.resolve(mockData.buffer),
      });

      await downloadChunk("https://example.com/chunk", controller.signal);

      expect(fetch).toHaveBeenCalledWith("https://example.com/chunk", {
        method: "GET",
        signal: controller.signal,
      });
    });
  });

  describe("downloadChunkWithRetry", () => {
    it("should succeed on first attempt", async () => {
      const mockData = new Uint8Array([1, 2, 3]);
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        arrayBuffer: () => Promise.resolve(mockData.buffer),
      });

      const result = await downloadChunkWithRetry(
        "https://example.com/chunk",
        0,
        { maxRetries: 3, retryDelayMs: 10 }
      );

      expect(fetch).toHaveBeenCalledTimes(1);
      expect(result).toEqual(mockData);
    });

    it("should retry on failure and eventually succeed", async () => {
      const mockData = new Uint8Array([1, 2, 3]);
      global.fetch = vi
        .fn()
        .mockRejectedValueOnce(new Error("Network error"))
        .mockRejectedValueOnce(new Error("Network error"))
        .mockResolvedValue({
          ok: true,
          arrayBuffer: () => Promise.resolve(mockData.buffer),
        });

      const result = await downloadChunkWithRetry(
        "https://example.com/chunk",
        0,
        { maxRetries: 3, retryDelayMs: 10 }
      );

      expect(fetch).toHaveBeenCalledTimes(3);
      expect(result).toEqual(mockData);
    });

    it("should throw after max retries exceeded", async () => {
      global.fetch = vi.fn().mockRejectedValue(new Error("Network error"));

      await expect(
        downloadChunkWithRetry("https://example.com/chunk", 5, {
          maxRetries: 2,
          retryDelayMs: 10,
        })
      ).rejects.toThrow("Chunk 5 download failed after 3 attempts");

      expect(fetch).toHaveBeenCalledTimes(3);
    });

    it("should not retry when cancelled", async () => {
      const controller = new AbortController();
      controller.abort();

      global.fetch = vi.fn().mockRejectedValue(new Error("Aborted"));

      await expect(
        downloadChunkWithRetry("https://example.com/chunk", 0, {
          maxRetries: 3,
          retryDelayMs: 10,
          signal: controller.signal,
        })
      ).rejects.toThrow("Download cancelled");

      expect(fetch).toHaveBeenCalledTimes(0);
    });

    it("should use default retry options", async () => {
      global.fetch = vi.fn().mockRejectedValue(new Error("Network error"));

      await expect(
        downloadChunkWithRetry("https://example.com/chunk", 0)
      ).rejects.toThrow();

      expect(fetch).toHaveBeenCalledTimes(4);
    });
  });

  describe("downloadAllChunks", () => {
    it("should download all chunks in parallel", async () => {
      const chunks = [
        new Uint8Array([1]),
        new Uint8Array([2]),
        new Uint8Array([3]),
      ];

      global.fetch = vi
        .fn()
        .mockImplementation((url: string) => {
          const index = parseInt(url.split("/").pop()!);
          return Promise.resolve({
            ok: true,
            arrayBuffer: () => Promise.resolve(chunks[index].buffer),
          });
        });

      const urls = ["https://example.com/0", "https://example.com/1", "https://example.com/2"];
      const results = await downloadAllChunks(urls, {
        concurrency: 3,
        maxRetries: 0,
        retryDelayMs: 10,
      });

      expect(results).toHaveLength(3);
      expect(results[0]).toEqual(chunks[0]);
      expect(results[1]).toEqual(chunks[1]);
      expect(results[2]).toEqual(chunks[2]);
    });

    it("should respect concurrency limit", async () => {
      let concurrentCalls = 0;
      let maxConcurrent = 0;

      global.fetch = vi.fn().mockImplementation(async () => {
        concurrentCalls++;
        maxConcurrent = Math.max(maxConcurrent, concurrentCalls);
        await new Promise((r) => setTimeout(r, 50));
        concurrentCalls--;
        return {
          ok: true,
          arrayBuffer: () => Promise.resolve(new ArrayBuffer(1)),
        };
      });

      const urls = Array(6).fill("https://example.com/chunk");
      await downloadAllChunks(urls, {
        concurrency: 2,
        maxRetries: 0,
        retryDelayMs: 10,
      });

      expect(maxConcurrent).toBeLessThanOrEqual(2);
    });

    it("should call onProgress callback", async () => {
      const mockData = new Uint8Array([1, 2, 3]);
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        arrayBuffer: () => Promise.resolve(mockData.buffer),
      });

      const progressUpdates: DownloadProgress[] = [];
      const urls = ["https://example.com/0", "https://example.com/1"];

      await downloadAllChunks(urls, {
        concurrency: 1,
        maxRetries: 0,
        retryDelayMs: 10,
        onProgress: (progress) => progressUpdates.push({ ...progress }),
      });

      expect(progressUpdates.length).toBeGreaterThan(0);
      const lastProgress = progressUpdates[progressUpdates.length - 1];
      expect(lastProgress.completed).toBe(2);
      expect(lastProgress.total).toBe(2);
    });

    it("should call onChunkComplete callback", async () => {
      const mockData = new Uint8Array([1, 2, 3]);
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        arrayBuffer: () => Promise.resolve(mockData.buffer),
      });

      const completedChunks: ChunkDownloadResult[] = [];
      const urls = ["https://example.com/0", "https://example.com/1"];

      await downloadAllChunks(urls, {
        concurrency: 1,
        maxRetries: 0,
        retryDelayMs: 10,
        onChunkComplete: (result) => completedChunks.push(result),
      });

      expect(completedChunks).toHaveLength(2);
      expect(completedChunks.map((c) => c.index).sort()).toEqual([0, 1]);
    });

    it("should use existing chunks and skip download", async () => {
      const existingChunk = new Uint8Array([1, 2, 3]);
      const newChunk = new Uint8Array([4, 5, 6]);

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        arrayBuffer: () => Promise.resolve(newChunk.buffer),
      });

      const existingChunks = new Map<number, Uint8Array>();
      existingChunks.set(0, existingChunk);

      const urls = ["https://example.com/0", "https://example.com/1"];
      const results = await downloadAllChunks(urls, {
        concurrency: 1,
        maxRetries: 0,
        retryDelayMs: 10,
        existingChunks,
      });

      expect(fetch).toHaveBeenCalledTimes(1);
      expect(results[0]).toEqual(existingChunk);
      expect(results[1]).toEqual(newChunk);
    });

    it("should return immediately if all chunks exist", async () => {
      global.fetch = vi.fn();

      const existingChunks = new Map<number, Uint8Array>();
      existingChunks.set(0, new Uint8Array([1]));
      existingChunks.set(1, new Uint8Array([2]));

      const urls = ["https://example.com/0", "https://example.com/1"];
      const results = await downloadAllChunks(urls, { existingChunks });

      expect(fetch).not.toHaveBeenCalled();
      expect(results).toHaveLength(2);
    });

    it("should throw on cancellation", async () => {
      const controller = new AbortController();

      global.fetch = vi.fn().mockImplementation(async () => {
        await new Promise((r) => setTimeout(r, 100));
        return {
          ok: true,
          arrayBuffer: () => Promise.resolve(new ArrayBuffer(1)),
        };
      });

      const promise = downloadAllChunks(
        ["https://example.com/0", "https://example.com/1"],
        { signal: controller.signal, concurrency: 1 }
      );

      setTimeout(() => controller.abort(), 10);

      await expect(promise).rejects.toThrow("Download cancelled");
    });

    it("should report failed chunks in error message", async () => {
      global.fetch = vi
        .fn()
        .mockResolvedValueOnce({
          ok: true,
          arrayBuffer: () => Promise.resolve(new ArrayBuffer(1)),
        })
        .mockRejectedValue(new Error("Failed"));

      const urls = ["https://example.com/0", "https://example.com/1"];

      await expect(
        downloadAllChunks(urls, { concurrency: 1, maxRetries: 0, retryDelayMs: 10 })
      ).rejects.toThrow();
    });
  });

  describe("createTimeoutController", () => {
    it("should create controller that aborts after timeout", async () => {
      vi.useFakeTimers();

      const controller = createTimeoutController(1000);
      expect(controller.signal.aborted).toBe(false);

      vi.advanceTimersByTime(1000);
      expect(controller.signal.aborted).toBe(true);

      vi.useRealTimers();
    });
  });

  describe("mergeAbortSignals", () => {
    it("should create controller that aborts when any signal aborts", () => {
      const controller1 = new AbortController();
      const controller2 = new AbortController();

      const merged = mergeAbortSignals(controller1.signal, controller2.signal);
      expect(merged.signal.aborted).toBe(false);

      controller1.abort();
      expect(merged.signal.aborted).toBe(true);
    });

    it("should be aborted immediately if any input is already aborted", () => {
      const controller1 = new AbortController();
      controller1.abort();

      const controller2 = new AbortController();
      const merged = mergeAbortSignals(controller1.signal, controller2.signal);

      expect(merged.signal.aborted).toBe(true);
    });

    it("should handle undefined signals", () => {
      const controller = new AbortController();
      const merged = mergeAbortSignals(undefined, controller.signal, undefined);

      expect(merged.signal.aborted).toBe(false);
      controller.abort();
      expect(merged.signal.aborted).toBe(true);
    });

    it("should work with no signals", () => {
      const merged = mergeAbortSignals();
      expect(merged.signal.aborted).toBe(false);
    });
  });
});
