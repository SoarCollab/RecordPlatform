import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => {
  return {
    fileApi: {
      getDownloadAddress: vi.fn(),
      getDecryptInfo: vi.fn(),
      publicDownloadFile: vi.fn(),
      shareDownloadFile: vi.fn(),
      downloadFile: vi.fn(),
    },
    chunkDownloader: {
      downloadAllChunks: vi.fn(),
    },
    downloadStorage: {
      saveTask: vi.fn(),
      saveChunk: vi.fn(),
      getChunks: vi.fn(),
      getPendingTasks: vi.fn(),
      clearTaskData: vi.fn(),
      cleanupExpiredData: vi.fn(),
    },
    crypto: {
      decryptFile: vi.fn(),
      arrayToBlob: vi.fn(),
      downloadBlob: vi.fn(),
    },
    fileSize: {
      performPreDownloadCheck: vi.fn(),
      formatFileSize: vi.fn(),
      isStreamingSupported: vi.fn(),
    },
    streaming: {
      executeBufferedStreamingDownload: vi.fn(),
    },
  };
});

vi.mock("$api/endpoints/files", () => mocks.fileApi);
vi.mock("$utils/chunkDownloader", () => mocks.chunkDownloader);
vi.mock("$utils/downloadStorage", () => mocks.downloadStorage);
vi.mock("$utils/crypto", () => mocks.crypto);
vi.mock("$utils/fileSize", () => mocks.fileSize);
vi.mock("$utils/streamingDownloader", () => mocks.streaming);

/**
 * 重新导入 download store，隔离模块状态。
 *
 * @returns download store 实例。
 */
async function loadDownloadStore() {
  vi.resetModules();
  const mod = await import("./download.svelte");
  return mod.useDownload();
}

/**
 * 轮询等待任务达到目标状态，避免异步流程引发时序抖动。
 *
 * @param getTaskStatus 读取当前状态的方法。
 * @param expected 目标状态。
 */
async function waitForStatus(
  getTaskStatus: () => string | undefined,
  expected: string,
): Promise<void> {
  for (let i = 0; i < 40; i++) {
    if (getTaskStatus() === expected) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  expect(getTaskStatus()).toBe(expected);
}

/**
 * 构造标准 Blob 结果。
 *
 * @returns 可复用 Blob。
 */
function createBlob(): Blob {
  return new Blob(["content"], { type: "application/octet-stream" });
}

/**
 * 构造可手动控制完成时机的 Promise。
 *
 *  deferred 结构体。
 */
function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe("download store", () => {
  beforeEach(() => {
    vi.clearAllMocks();

    mocks.fileApi.getDownloadAddress.mockResolvedValue(["u1"]);
    mocks.fileApi.getDecryptInfo.mockResolvedValue({
      initialKey: "k1",
      chunkCount: 1,
      contentType: "application/pdf",
      fileName: "report.pdf",
      fileSize: 1024,
    });
    mocks.fileApi.publicDownloadFile.mockResolvedValue(createBlob());
    mocks.fileApi.shareDownloadFile.mockResolvedValue(createBlob());
    mocks.fileApi.downloadFile.mockResolvedValue(createBlob());

    mocks.chunkDownloader.downloadAllChunks.mockImplementation(
      async (_urls: string[], options: { onChunkComplete?: (result: { index: number; data: Uint8Array }) => Promise<void>; onProgress?: (progress: { completed: number; total: number }) => void }) => {
        const data = new Uint8Array([1]);
        await options.onChunkComplete?.({ index: 0, data });
        options.onProgress?.({ completed: 1, total: 1 });
        return [data];
      },
    );

    mocks.downloadStorage.saveTask.mockResolvedValue(undefined);
    mocks.downloadStorage.saveChunk.mockResolvedValue(undefined);
    mocks.downloadStorage.getChunks.mockResolvedValue(new Map());
    mocks.downloadStorage.getPendingTasks.mockResolvedValue([]);
    mocks.downloadStorage.clearTaskData.mockResolvedValue(undefined);
    mocks.downloadStorage.cleanupExpiredData.mockResolvedValue(undefined);

    mocks.crypto.decryptFile.mockResolvedValue(new Uint8Array([2]));
    mocks.crypto.arrayToBlob.mockReturnValue(createBlob());
    mocks.crypto.downloadBlob.mockImplementation(() => {});

    mocks.fileSize.performPreDownloadCheck.mockReturnValue({
      canProceed: true,
      decision: { strategy: "inmemory" },
      capabilities: { memoryEstimate: 1024 },
    });
    mocks.fileSize.formatFileSize.mockReturnValue("1.00 KB");
    mocks.fileSize.isStreamingSupported.mockReturnValue(true);

    mocks.streaming.executeBufferedStreamingDownload.mockResolvedValue({
      success: true,
      bytesWritten: 100,
    });

    Object.defineProperty(window, "showSaveFilePicker", {
      value: vi.fn().mockResolvedValue({ handle: "h" }),
      configurable: true,
    });
  });

  it("checkFileSize/canUseStreaming 应返回工具函数结果", async () => {
    const download = await loadDownloadStore();

    const check = download.checkFileSize(1024);

    expect(check.formattedSize).toBe("1.00 KB");
    expect(check.decision.strategy).toBe("inmemory");
    expect(download.canUseStreaming()).toBe(true);
  });

  it("owned 文件默认应走内存下载并最终 completed", async () => {
    const download = await loadDownloadStore();

    const id = await download.startDownload("hash-1", "report.pdf", { type: "owned" }, 1024, "inmemory");

    await waitForStatus(
      () => download.tasks.find((task) => task.id === id)?.status,
      "completed",
    );

    expect(mocks.fileApi.getDownloadAddress).toHaveBeenCalledWith("hash-1");
    expect(mocks.fileApi.getDecryptInfo).toHaveBeenCalledWith("hash-1");
    expect(mocks.chunkDownloader.downloadAllChunks).toHaveBeenCalled();
    expect(mocks.crypto.decryptFile).toHaveBeenCalled();
    expect(mocks.crypto.downloadBlob).toHaveBeenCalled();
    expect(mocks.downloadStorage.clearTaskData).toHaveBeenCalledWith(id);
  });

  it("public/private share 应走 backend proxy 下载分支", async () => {
    const download = await loadDownloadStore();

    const publicId = await download.startDownload(
      "hash-public",
      "public.pdf",
      { type: "public_share", shareCode: "share-public" },
    );

    const privateId = await download.startDownload(
      "hash-private",
      "private.pdf",
      { type: "private_share", shareCode: "share-private" },
    );

    await waitForStatus(
      () => download.tasks.find((task) => task.id === publicId)?.status,
      "completed",
    );
    await waitForStatus(
      () => download.tasks.find((task) => task.id === privateId)?.status,
      "completed",
    );

    expect(mocks.fileApi.publicDownloadFile).toHaveBeenCalledWith(
      "share-public",
      "hash-public",
    );
    expect(mocks.fileApi.shareDownloadFile).toHaveBeenCalledWith(
      "share-private",
      "hash-private",
    );
  });

  it("owned 文件在 streaming 策略下应走流式下载", async () => {
    const download = await loadDownloadStore();

    const id = await download.startDownload(
      "hash-stream",
      "big.zip",
      { type: "owned" },
      undefined,
      "streaming",
    );

    await waitForStatus(
      () => download.tasks.find((task) => task.id === id)?.status,
      "completed",
    );

    expect(mocks.streaming.executeBufferedStreamingDownload).toHaveBeenCalled();
    expect(mocks.fileApi.getDownloadAddress).toHaveBeenCalledWith("hash-stream");
  });

  it("streaming 失败为用户取消时应标记 cancelled", async () => {
    mocks.streaming.executeBufferedStreamingDownload.mockResolvedValue({
      success: false,
      error: "File save cancelled by user",
    });

    const download = await loadDownloadStore();
    const id = await download.startDownload(
      "hash-cancel",
      "cancel.bin",
      { type: "owned" },
      undefined,
      "streaming",
    );

    await waitForStatus(
      () => download.tasks.find((task) => task.id === id)?.status,
      "cancelled",
    );
  });

  it("pause/resume/cancel/retry/remove/clearCompleted 应按预期工作", async () => {
    const deferred = createDeferred<Uint8Array[]>();
    mocks.chunkDownloader.downloadAllChunks
      .mockImplementationOnce(async () => deferred.promise)
      .mockImplementation(async (_urls: string[], options: { onChunkComplete?: (result: { index: number; data: Uint8Array }) => Promise<void>; onProgress?: (progress: { completed: number; total: number }) => void }) => {
        const data = new Uint8Array([1]);
        await options.onChunkComplete?.({ index: 0, data });
        options.onProgress?.({ completed: 1, total: 1 });
        return [data];
      });

    const download = await loadDownloadStore();

    const id = await download.startDownload("hash-ops", "ops.bin", { type: "owned" }, 1024, "inmemory");
    await waitForStatus(
      () => download.tasks.find((task) => task.id === id)?.status,
      "downloading",
    );

    download.pauseDownload(id);
    expect(download.tasks.find((task) => task.id === id)?.status).toBe("paused");

    deferred.resolve([new Uint8Array([1])]);

    await download.resumeDownload(id);
    await waitForStatus(
      () => download.tasks.find((task) => task.id === id)?.status,
      "completed",
    );

    await download.cancelDownload(id);
    expect(download.tasks.find((task) => task.id === id)?.status).toBe("cancelled");

    await download.retryDownload(id);
    await waitForStatus(
      () => download.tasks.find((task) => task.id === id)?.status,
      "completed",
    );

    download.clearCompleted();
    expect(download.tasks.some((task) => task.status === "completed")).toBe(false);

    await download.removeTask(id);
    expect(download.tasks.some((task) => task.id === id)).toBe(false);
  });

  it("restoreTasks 应从存储恢复任务并标记为 paused", async () => {
    mocks.downloadStorage.getPendingTasks.mockResolvedValue([
      {
        id: "restored-1",
        fileHash: "h1",
        fileName: "r1.bin",
        fileSize: 200,
        contentType: "application/octet-stream",
        totalChunks: 2,
        initialKey: "k",
        source: { type: "owned" },
        presignedUrls: ["u1", "u2"],
        urlsFetchedAt: Date.now(),
        createdAt: Date.now(),
      },
    ]);
    mocks.downloadStorage.getChunks.mockResolvedValue(new Map([[0, new Uint8Array([1])]]));

    const download = await loadDownloadStore();
    await download.restoreTasks();

    expect(download.tasks).toHaveLength(1);
    expect(download.tasks[0].status).toBe("paused");
    expect(download.tasks[0].downloadedChunks).toBe(1);
  });

  it("setConcurrency 应限制在 [1,10] 区间", async () => {
    const download = await loadDownloadStore();

    download.setConcurrency(0);
    expect(download.concurrency).toBe(1);

    download.setConcurrency(20);
    expect(download.concurrency).toBe(10);

    download.setConcurrency(4);
    expect(download.concurrency).toBe(4);
  });
});

describe("download store extra branches", () => {
  beforeEach(() => {
    vi.clearAllMocks();

    mocks.fileApi.getDownloadAddress.mockResolvedValue(["u1"]);
    mocks.fileApi.getDecryptInfo.mockResolvedValue({
      initialKey: "k1",
      chunkCount: 1,
      contentType: "application/pdf",
      fileName: "report.pdf",
      fileSize: 1024,
    });
    mocks.fileApi.publicDownloadFile.mockResolvedValue(createBlob());
    mocks.fileApi.shareDownloadFile.mockResolvedValue(createBlob());
    mocks.fileApi.downloadFile.mockResolvedValue(createBlob());

    mocks.chunkDownloader.downloadAllChunks.mockResolvedValue([new Uint8Array([1])]);
    mocks.downloadStorage.saveTask.mockResolvedValue(undefined);
    mocks.downloadStorage.saveChunk.mockResolvedValue(undefined);
    mocks.downloadStorage.getChunks.mockResolvedValue(new Map());
    mocks.downloadStorage.getPendingTasks.mockResolvedValue([]);
    mocks.downloadStorage.clearTaskData.mockResolvedValue(undefined);
    mocks.downloadStorage.cleanupExpiredData.mockResolvedValue(undefined);
    mocks.crypto.decryptFile.mockResolvedValue(new Uint8Array([2]));
    mocks.crypto.arrayToBlob.mockReturnValue(createBlob());
    mocks.crypto.downloadBlob.mockImplementation(() => {});
    mocks.fileSize.performPreDownloadCheck.mockReturnValue({
      canProceed: true,
      decision: { strategy: "inmemory" },
      capabilities: { memoryEstimate: 1024 },
    });
    mocks.fileSize.formatFileSize.mockReturnValue("1.00 KB");
    mocks.fileSize.isStreamingSupported.mockReturnValue(true);
    mocks.streaming.executeBufferedStreamingDownload.mockResolvedValue({
      success: true,
      bytesWritten: 10,
    });

    Object.defineProperty(window, "showSaveFilePicker", {
      value: vi.fn().mockResolvedValue({ handle: "h" }),
      configurable: true,
    });
  });

  it("backend proxy 在 shareCode 缺失时应回退 downloadFile", async () => {
    const download = await loadDownloadStore();
    const id = await download.startDownload(
      "hash-fallback",
      "fallback.bin",
      { type: "private_share" },
    );

    await waitForStatus(
      () => download.tasks.find((task) => task.id === id)?.status,
      "completed",
    );

    expect(mocks.fileApi.downloadFile).toHaveBeenCalledWith("hash-fallback");
  });

  it("streaming 选择文件阶段 AbortError 应标记 cancelled", async () => {
    Object.defineProperty(window, "showSaveFilePicker", {
      value: vi.fn().mockRejectedValue(Object.assign(new Error("abort"), { name: "AbortError" })),
      configurable: true,
    });

    const download = await loadDownloadStore();
    const id = await download.startDownload(
      "hash-abort",
      "abort.bin",
      { type: "owned" },
      undefined,
      "streaming",
    );

    await waitForStatus(
      () => download.tasks.find((task) => task.id === id)?.status,
      "cancelled",
    );
  });

  it("streaming 执行返回普通失败时应进入 failed", async () => {
    mocks.streaming.executeBufferedStreamingDownload.mockResolvedValue({
      success: false,
      error: "decrypt failed",
    });

    const download = await loadDownloadStore();
    const id = await download.startDownload(
      "hash-failed",
      "failed.bin",
      { type: "owned" },
      undefined,
      "streaming",
    );

    await waitForStatus(
      () => download.tasks.find((task) => task.id === id)?.status,
      "failed",
    );
  });

  it("下载中 cancel 后应命中取消分支，resume/retry 非法状态应提前返回", async () => {
    const deferred = createDeferred<Uint8Array[]>();
    mocks.chunkDownloader.downloadAllChunks.mockImplementationOnce(
      async () => deferred.promise,
    );

    const download = await loadDownloadStore();
    const id = await download.startDownload("hash-cancel2", "c.bin", { type: "owned" }, 1024, "inmemory");

    await waitForStatus(
      () => download.tasks.find((task) => task.id === id)?.status,
      "downloading",
    );

    await download.cancelDownload(id);
    expect(download.tasks.find((task) => task.id === id)?.status).toBe("cancelled");

    deferred.resolve([new Uint8Array([1])]);

    await download.resumeDownload("not-exist");
    await download.retryDownload("not-exist");

    expect(mocks.downloadStorage.clearTaskData).toHaveBeenCalledWith(id);
  });

  it("removeTask 在活动任务上应触发 abort 与清理", async () => {
    const deferred = createDeferred<Uint8Array[]>();
    mocks.chunkDownloader.downloadAllChunks.mockImplementationOnce(
      async () => deferred.promise,
    );

    const download = await loadDownloadStore();
    const id = await download.startDownload("hash-remove", "r.bin", { type: "owned" }, 1024, "inmemory");

    await waitForStatus(
      () => download.tasks.find((task) => task.id === id)?.status,
      "downloading",
    );

    await download.removeTask(id);
    expect(download.tasks.some((task) => task.id === id)).toBe(false);

    deferred.resolve([new Uint8Array([1])]);
  });

  it("restoreTasks 出错时应记录日志并完成初始化", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    mocks.downloadStorage.cleanupExpiredData.mockRejectedValue(new Error("db failed"));

    const download = await loadDownloadStore();
    await download.restoreTasks();

    expect(download.initialized).toBe(true);
    expect(errorSpy).toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it("导出 getter 应可访问", async () => {
    const download = await loadDownloadStore();

    expect(Array.isArray(download.pendingTasks)).toBe(true);
    expect(Array.isArray(download.activeTasks)).toBe(true);
    expect(Array.isArray(download.streamingTasks)).toBe(true);
    expect(Array.isArray(download.pausedTasks)).toBe(true);
    expect(Array.isArray(download.completedTasks)).toBe(true);
    expect(Array.isArray(download.failedTasks)).toBe(true);
    expect(typeof download.isDownloading).toBe("boolean");
  });
});

it("restoreTasks 在 totalChunks=0 时进度应回退为 0", async () => {
  mocks.downloadStorage.getPendingTasks.mockResolvedValue([
    {
      id: "restored-zero",
      fileHash: "hz",
      fileName: "z.bin",
      fileSize: 0,
      contentType: "application/octet-stream",
      totalChunks: 0,
      initialKey: null,
      source: { type: "owned" },
      presignedUrls: [],
      urlsFetchedAt: null,
      createdAt: Date.now(),
    },
  ]);
  mocks.downloadStorage.getChunks.mockResolvedValue(new Map());

  const download = await loadDownloadStore();
  await download.restoreTasks();

  expect(download.tasks.find((task) => task.id === "restored-zero")?.progress).toBe(0);
});
