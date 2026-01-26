import { describe, it, expect, vi, afterEach } from "vitest";

vi.mock("./chunkDownloader", () => ({
  downloadChunkWithRetry: vi.fn(),
}));

vi.mock("./crypto", () => ({
  executeStreamingDecrypt: vi.fn(),
}));

import { downloadChunkWithRetry } from "./chunkDownloader";
import { executeStreamingDecrypt, type StreamDecryptCallbacks } from "./crypto";

describe("streamingDownloader", () => {
  const originalShowSaveFilePicker = (
    window as unknown as Record<string, unknown>
  ).showSaveFilePicker;

  afterEach(() => {
    vi.clearAllMocks();

    if (originalShowSaveFilePicker) {
      Object.defineProperty(window, "showSaveFilePicker", {
        value: originalShowSaveFilePicker,
        configurable: true,
      });
    } else {
      delete (window as unknown as Record<string, unknown>).showSaveFilePicker;
    }
  });

  it("should return a clear error when streaming is not supported", async () => {
    delete (window as unknown as Record<string, unknown>).showSaveFilePicker;

    const { executeStreamingDownload } = await import("./streamingDownloader");

    const result = await executeStreamingDownload({
      fileName: "report.pdf",
      contentType: "application/pdf",
      totalChunks: 1,
      initialKey: "k",
      presignedUrls: ["u0"],
    });

    expect(result.success).toBe(false);
    expect(result.error).toBe(
      "Streaming download not supported in this browser",
    );
  });

  it("should stream chunks to disk via File System Access API", async () => {
    const writes: Array<ArrayBuffer> = [];

    const writable = {
      write: vi.fn(async (data: BufferSource | Blob | string) => {
        writes.push(data as ArrayBuffer);
      }),
      seek: vi.fn(async () => {}),
      truncate: vi.fn(async () => {}),
      close: vi.fn(async () => {}),
    };

    const fileHandle = {
      name: "report",
      getFile: vi.fn(async () => new File([], "report")),
      createWritable: vi.fn(async () => writable),
    };

    const showSaveFilePicker = vi.fn(async () => fileHandle);
    Object.defineProperty(window, "showSaveFilePicker", {
      value: showSaveFilePicker,
      configurable: true,
    });

    vi.mocked(downloadChunkWithRetry).mockImplementation(
      async (_url: string, index: number) => new Uint8Array([index, index + 1]),
    );

    vi.mocked(executeStreamingDecrypt).mockImplementation(
      async (
        totalChunks: number,
        _initialKey: string,
        callbacks: StreamDecryptCallbacks,
      ) => {
        for (let i = 0; i < totalChunks; i++) {
          const data = await callbacks.downloadChunk(i);
          await callbacks.writeDecrypted(i, data);
          callbacks.onProgress?.(i + 1, totalChunks);
        }
      },
    );

    const progressPhases: string[] = [];

    const { executeStreamingDownload } = await import("./streamingDownloader");

    const result = await executeStreamingDownload({
      fileName: "report",
      contentType: "application/pdf",
      totalChunks: 2,
      initialKey: "k",
      presignedUrls: ["u0", "u1"],
      onProgress: (phase) => progressPhases.push(phase),
    });

    expect(showSaveFilePicker).toHaveBeenCalledWith({
      suggestedName: "report",
      types: [
        {
          description: "File",
          accept: { "application/pdf": [".pdf"] },
        },
      ],
    });

    expect(result).toEqual({ success: true, bytesWritten: 4 });
    expect(writable.write).toHaveBeenCalledTimes(2);
    expect(writable.close).toHaveBeenCalledTimes(1);

    expect(writes[0]).toBeInstanceOf(ArrayBuffer);
    expect(Array.from(new Uint8Array(writes[0]))).toEqual([0, 1]);
    expect(Array.from(new Uint8Array(writes[1]))).toEqual([1, 2]);

    expect(progressPhases[0]).toBe("preparing");
    expect(progressPhases).toContain("downloading");
    expect(progressPhases).toContain("decrypting");
    expect(progressPhases).toContain("writing");
    expect(progressPhases[progressPhases.length - 1]).toBe("completed");
  });

  it("should buffer out-of-order decrypted chunks and write in order", async () => {
    const writes: Array<Uint8Array> = [];

    const writable = {
      write: vi.fn(async (data: BufferSource | Blob | string) => {
        writes.push(new Uint8Array(data as ArrayBuffer));
      }),
      seek: vi.fn(async () => {}),
      truncate: vi.fn(async () => {}),
      close: vi.fn(async () => {}),
    };

    const fileHandle = {
      name: "notes.txt",
      getFile: vi.fn(async () => new File([], "notes.txt")),
      createWritable: vi.fn(async () => writable),
    };

    Object.defineProperty(window, "showSaveFilePicker", {
      value: vi.fn(async () => fileHandle),
      configurable: true,
    });

    vi.mocked(downloadChunkWithRetry).mockImplementation(
      async (_url: string, index: number) => new Uint8Array([index]),
    );

    vi.mocked(executeStreamingDecrypt).mockImplementationOnce(
      async (
        _totalChunks: number,
        _initialKey: string,
        callbacks: StreamDecryptCallbacks,
      ) => {
        const data0 = await callbacks.downloadChunk(0);
        const data1 = await callbacks.downloadChunk(1);
        const data2 = await callbacks.downloadChunk(2);

        // Write out of order: 1, 0, 2
        await callbacks.writeDecrypted(1, data1);
        callbacks.onProgress?.(1, 3);

        await callbacks.writeDecrypted(0, data0);
        callbacks.onProgress?.(2, 3);

        await callbacks.writeDecrypted(2, data2);
        callbacks.onProgress?.(3, 3);
      },
    );

    const { executeBufferedStreamingDownload } =
      await import("./streamingDownloader");

    const result = await executeBufferedStreamingDownload({
      fileName: "notes.txt",
      contentType: "text/plain",
      totalChunks: 3,
      initialKey: "k",
      presignedUrls: ["u0", "u1", "u2"],
    });

    expect(result).toEqual({ success: true, bytesWritten: 3 });
    expect(writable.write).toHaveBeenCalledTimes(3);
    expect(writes.map((b) => b[0])).toEqual([0, 1, 2]);
  });
});
