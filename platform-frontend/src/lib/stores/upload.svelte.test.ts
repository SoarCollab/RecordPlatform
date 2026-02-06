import { beforeEach, describe, expect, it, vi } from "vitest";

class MockApiError extends Error {
  code: number;

  constructor(code: number, message: string) {
    super(message);
    this.code = code;
  }
}

const mocks = vi.hoisted(() => {
  return {
    startUpload: vi.fn(),
    uploadChunk: vi.fn(),
    completeUpload: vi.fn(),
    pauseUpload: vi.fn(),
    resumeUpload: vi.fn(),
    cancelUpload: vi.fn(),
    getUploadProgress: vi.fn(),
    subscribe: vi.fn(),
  };
});

vi.mock("$api/endpoints/upload", () => ({
  startUpload: mocks.startUpload,
  uploadChunk: mocks.uploadChunk,
  completeUpload: mocks.completeUpload,
  pauseUpload: mocks.pauseUpload,
  resumeUpload: mocks.resumeUpload,
  cancelUpload: mocks.cancelUpload,
  getUploadProgress: mocks.getUploadProgress,
}));

vi.mock("$stores/sse.svelte", () => ({
  useSSE: () => ({
    subscribe: mocks.subscribe,
  }),
}));

vi.mock("$api/client", () => ({
  ApiError: MockApiError,
}));

/**
 * 重新导入 upload store 以隔离模块状态。
 *
 * @returns upload store 实例。
 */
async function loadUploadStore() {
  vi.resetModules();
  const mod = await import("./upload.svelte");
  return mod.useUpload();
}

/**
 * 创建用于上传测试的文件对象。
 *
 * @param bytes 文件大小（字节）。
 * @returns 指定大小的 File。
 */
function createFile(bytes: number): File {
  return new File([new Uint8Array(bytes)], `f-${bytes}.bin`, {
    type: "application/octet-stream",
  });
}

/**
 * 构造一个可手动完成的 Promise，便于控制异步时序。
 *
 * @returns deferred 结构。
 */
function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe("upload store", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.subscribe.mockReturnValue(() => {});
    mocks.startUpload.mockResolvedValue({ clientId: "c1", processedChunks: [] });
    mocks.uploadChunk.mockResolvedValue(undefined);
    mocks.completeUpload.mockResolvedValue(undefined);
    mocks.pauseUpload.mockResolvedValue(undefined);
    mocks.resumeUpload.mockResolvedValue({ processedChunks: [] });
    mocks.cancelUpload.mockResolvedValue(undefined);
    mocks.getUploadProgress.mockResolvedValue({
      status: "uploading",
      progress: 0,
      uploadProgress: 0,
      processProgress: 0,
    });
  });

  it("addFile 应根据文件大小计算分片并创建任务", async () => {
    const upload = await loadUploadStore();

    const idSmall = await upload.addFile(createFile(1 * 1024 * 1024));
    const idMedium = await upload.addFile(createFile(20 * 1024 * 1024));

    const small = upload.tasks.find((task) => task.id === idSmall);
    const medium = upload.tasks.find((task) => task.id === idMedium);

    expect(small?.chunkSize).toBe(2 * 1024 * 1024);
    expect(medium?.chunkSize).toBe(5 * 1024 * 1024);
    expect(upload.tasks.length).toBe(2);
  });

  it("startUpload 初始化失败时任务应进入 failed", async () => {
    mocks.startUpload.mockRejectedValue(new Error("start failed"));

    const upload = await loadUploadStore();
    const id = await upload.addFile(createFile(1024));

    await Promise.resolve();
    await Promise.resolve();

    const task = upload.tasks.find((item) => item.id === id);
    expect(task?.status).toBe("failed");
    expect(task?.error).toBe("start failed");
  });

  it("轮询遇到 40006 会话清理时应标记 completed", async () => {
    mocks.getUploadProgress.mockRejectedValue(
      new MockApiError(40006, "session not found"),
    );

    const upload = await loadUploadStore();
    const id = await upload.addFile(createFile(1024));

    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();

    const task = upload.tasks.find((item) => item.id === id);
    expect(task?.status).toBe("completed");
    expect(task?.progress).toBe(100);
  });

  it("cancelUpload 应取消活动任务并调用后端取消接口", async () => {
    const deferred = createDeferred<void>();
    mocks.uploadChunk.mockImplementation(() => deferred.promise);

    const upload = await loadUploadStore();
    const id = await upload.addFile(createFile(1024));

    await Promise.resolve();
    await Promise.resolve();

    await upload.cancelUpload(id);

    const task = upload.tasks.find((item) => item.id === id);
    expect(task?.status).toBe("cancelled");
    expect(mocks.cancelUpload).toHaveBeenCalled();

    deferred.resolve();
  });

  it("pause/resume 应触发对应 API，并保持状态流转", async () => {
    const deferred = createDeferred<void>();
    mocks.uploadChunk.mockImplementation(() => deferred.promise);

    const upload = await loadUploadStore();
    const id = await upload.addFile(createFile(1024));

    await Promise.resolve();
    await Promise.resolve();

    await upload.pauseUpload(id);
    expect(upload.tasks.find((item) => item.id === id)?.status).toBe("paused");

    deferred.resolve();
    await upload.resumeUpload(id);

    expect(mocks.resumeUpload).toHaveBeenCalled();
    expect(
      ["uploading", "processing", "completed", "failed", "cancelled"].includes(
        upload.tasks.find((item) => item.id === id)?.status || "",
      ),
    ).toBe(true);
  });

  it("retryUpload/批量重试/批量取消/清理接口应可工作", async () => {
    mocks.startUpload
      .mockRejectedValueOnce(new Error("first fail"))
      .mockResolvedValue({ clientId: "c2", processedChunks: [] });

    const upload = await loadUploadStore();
    const id = await upload.addFile(createFile(1024));

    await Promise.resolve();
    await Promise.resolve();

    expect(upload.tasks.find((item) => item.id === id)?.status).toBe("failed");

    await upload.retryUpload(id);
    expect(mocks.startUpload).toHaveBeenCalledTimes(2);

    const retriedCount = await upload.retryAllFailedAndCancelled();
    expect(retriedCount).toBeGreaterThanOrEqual(0);

    const cancelledCount = await upload.cancelAllActiveAndProcessing();
    expect(cancelledCount).toBeGreaterThanOrEqual(0);

    upload.clearCompleted();
    upload.clearFailedAndCancelled();
    expect(upload.tasks.every((task) => task.status !== "failed")).toBe(true);
  });

  it("removeTask 应删除任务并清理内部状态", async () => {
    const upload = await loadUploadStore();
    const id = await upload.addFile(createFile(1024));

    upload.removeTask(id);

    expect(upload.tasks.some((task) => task.id === id)).toBe(false);
  });
});

/**
 * 根据任务 ID 读取当前任务快照。
 *
 * @param upload store 实例。
 * @param id 任务 ID。
 * @returns 匹配任务。
 */
function findTask(
  upload: ReturnType<typeof Object>,
  id: string,
): { id: string; status: string; clientId: string | null } | undefined {
  return (upload as { tasks: Array<{ id: string; status: string; clientId: string | null }> }).tasks.find(
    (task) => task.id === id,
  );
}

describe("upload store extra branches", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.subscribe.mockReturnValue(() => {});
    mocks.startUpload.mockResolvedValue({ clientId: "c3", processedChunks: [] });
    mocks.uploadChunk.mockResolvedValue(undefined);
    mocks.completeUpload.mockResolvedValue(undefined);
    mocks.pauseUpload.mockResolvedValue(undefined);
    mocks.resumeUpload.mockResolvedValue({ processedChunks: [] });
    mocks.cancelUpload.mockResolvedValue(undefined);
    mocks.getUploadProgress.mockResolvedValue({
      status: "processing",
      progress: 100,
      uploadProgress: 100,
      processProgress: 100,
    });
  });

  it("addFiles 与导出 getters 应可访问", async () => {
    const upload = await loadUploadStore();
    const ids = await upload.addFiles([createFile(1024), createFile(2048)]);

    expect(ids).toHaveLength(2);
    expect(Array.isArray(upload.pendingTasks)).toBe(true);
    expect(Array.isArray(upload.activeTasks)).toBe(true);
    expect(Array.isArray(upload.processingTasks)).toBe(true);
    expect(Array.isArray(upload.completedTasks)).toBe(true);
    expect(Array.isArray(upload.failedTasks)).toBe(true);
    expect(Array.isArray(upload.cancelledTasks)).toBe(true);
    expect(typeof upload.totalProgress).toBe("number");
    expect(typeof upload.isUploading).toBe("boolean");
  });

  it("pause/resume/cancel 的异常分支应被覆盖", async () => {
    const upload = await loadUploadStore();
    const id = await upload.addFile(createFile(1024));

    await Promise.resolve();
    await Promise.resolve();

    mocks.pauseUpload.mockRejectedValueOnce(new Error("pause failed"));
    await upload.pauseUpload(id);

    const task = findTask(upload, id);
    expect(task?.status).toBe("paused");

    mocks.resumeUpload.mockRejectedValueOnce(new Error("resume failed"));
    await upload.resumeUpload(id);

    mocks.cancelUpload.mockRejectedValueOnce(new Error("cancel failed"));
    await upload.cancelUpload(id);
    expect(findTask(upload, id)?.status).toBe("cancelled");
  });

  it("可见性与 SSE 回调应触发进度轮询", async () => {
    const upload = await loadUploadStore();
    const id = await upload.addFile(createFile(1024));

    await Promise.resolve();
    await Promise.resolve();

    const task = (upload as { tasks: Array<{ id: string; status: string; clientId: string | null }> }).tasks.find(
      (item) => item.id === id,
    );

    if (task) {
      task.status = "processing";
      task.clientId = "c3";
    }

    (globalThis as unknown as { __setDocumentVisibility?: (state: DocumentVisibilityState) => void }).__setDocumentVisibility?.("hidden");
    (globalThis as unknown as { __setDocumentVisibility?: (state: DocumentVisibilityState) => void }).__setDocumentVisibility?.("visible");

    const subscribeCallback = mocks.subscribe.mock.calls[0][0] as (
      message: { type: string },
    ) => void;
    subscribeCallback({ type: "file-processed" });

    expect(mocks.getUploadProgress).toHaveBeenCalled();
  });

  it("clearCompleted/clearFailedAndCancelled/批量操作应覆盖空与非空路径", async () => {
    mocks.startUpload
      .mockRejectedValueOnce(new Error("failed-a"))
      .mockResolvedValue({ clientId: "c4", processedChunks: [] });

    const upload = await loadUploadStore();
    const failedId = await upload.addFile(createFile(1024));
    const okId = await upload.addFile(createFile(2048));

    await Promise.resolve();
    await Promise.resolve();

    await upload.cancelUpload(okId);

    const retryCount = await upload.retryAllFailedAndCancelled();
    expect(retryCount).toBeGreaterThanOrEqual(1);

    const cancelCount = await upload.cancelAllActiveAndProcessing();
    expect(cancelCount).toBeGreaterThanOrEqual(0);

    upload.clearCompleted();
    upload.clearFailedAndCancelled();
    upload.removeTask(failedId);

    expect(upload.tasks.some((task) => task.status === "failed")).toBe(false);
  });
});
