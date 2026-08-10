import type { FileSystemDownloadSink } from "../../src/lib/utils/downloadSink";
import {
  createFileSystemDownloadSink,
  type DownloadSink,
} from "../../src/lib/utils/downloadSink";
import { executeBoundedDownload } from "../../src/lib/utils/boundedDownloader";
import { DownloadMetricsTracker } from "../../src/lib/utils/downloadMetrics";
import {
  consumeSyntheticDownloadKeyGrant,
  createDownloadMetadata,
  createPlainBytes,
  createSyntheticResponse,
} from "./fixtures";
import type {
  DownloadMemoryGateApi,
  DownloadMemoryResult,
  DownloadMemoryRunOptions,
  DownloadMemoryRunStatus,
} from "./types";

const SENTINEL = new TextEncoder().encode("pre-existing-opfs-content");
const CANCEL_AFTER_BYTES = 8 * 1024 * 1024;
const SAMPLE_BYTES = 64 * 1024;

interface RunEntry {
  done: boolean;
  result?: DownloadMemoryResult;
  promise?: Promise<DownloadMemoryResult>;
}

/** 包装真实 OPFS sink，记录 close/abort 并注入取消或写入失败。 */
class ObservedSink implements DownloadSink {
  readonly supportsRandomAccess: boolean;
  closeCalls = 0;
  abortCalls = 0;
  private writtenBytes = 0;
  private aborted = false;

  constructor(
    private readonly delegate: FileSystemDownloadSink,
    private readonly options: DownloadMemoryRunOptions,
    private readonly controller: AbortController,
  ) {
    this.supportsRandomAccess = delegate.supportsRandomAccess;
  }

  /** 顺序写入并在指定失败场景触发可控状态变化。 */
  async write(data: Uint8Array): Promise<void> {
    if (
      this.options.failure === "abort" &&
      this.writtenBytes >= CANCEL_AFTER_BYTES
    ) {
      throw new Error("synthetic OPFS sink failure");
    }
    await this.delegate.write(data);
    this.writtenBytes += data.byteLength;
    if (
      this.options.failure === "cancel" &&
      this.writtenBytes >= CANCEL_AFTER_BYTES
    ) {
      this.controller.abort("synthetic cancellation");
    }
  }

  /** 支持历史路径需要的随机写入，并复用同一故障策略。 */
  async writeAt(position: number, data: Uint8Array): Promise<void> {
    if (
      this.options.failure === "abort" &&
      this.writtenBytes >= CANCEL_AFTER_BYTES
    ) {
      throw new Error("synthetic OPFS sink failure");
    }
    await this.delegate.writeAt(position, data);
    this.writtenBytes += data.byteLength;
  }

  /** 记录事务提交次数。 */
  async close(): Promise<void> {
    this.closeCalls += 1;
    await this.delegate.close();
  }

  /** 只向底层 OPFS 发起一次 abort，保持失败路径幂等。 */
  async abort(reason?: unknown): Promise<void> {
    this.abortCalls += 1;
    if (this.aborted) return;
    this.aborted = true;
    await this.delegate.abort(reason);
  }
}

/** 将短哨兵内容写入 OPFS，随后用 abort 证明原文件不会被半成品替换。 */
async function seedOpfsFile(handle: FileSystemFileHandle): Promise<void> {
  const writable = await handle.createWritable();
  await writable.write(SENTINEL.buffer);
  await writable.close();
}

/** 比较 OPFS 文件中的短样本，避免为校验再次把大文件装入内存。 */
async function validateOutputSamples(
  file: File,
  expectedSize: number,
): Promise<boolean> {
  if (file.size !== expectedSize) return false;
  const offsets = [
    0,
    Math.max(0, Math.floor(expectedSize / 2) - Math.floor(SAMPLE_BYTES / 2)),
    Math.max(0, expectedSize - SAMPLE_BYTES),
  ];
  for (const offset of offsets) {
    const length = Math.min(SAMPLE_BYTES, expectedSize - offset);
    const actual = new Uint8Array(
      await file.slice(offset, offset + length).arrayBuffer(),
    );
    const expected = createPlainBytes(offset, length);
    if (actual.length !== expected.length) return false;
    for (let index = 0; index < expected.length; index++) {
      if (actual[index] !== expected[index]) return false;
    }
  }
  return true;
}

/** 判断 abort 后 OPFS 是否仍保留原有哨兵内容。 */
async function isSentinelPreserved(file: File): Promise<boolean> {
  if (file.size !== SENTINEL.byteLength) return false;
  const actual = new Uint8Array(await file.arrayBuffer());
  return actual.every((value, index) => value === SENTINEL[index]);
}

/** 在独立 OPFS 文件上执行一次下载场景并返回可序列化证据。 */
async function runScenario(
  options: DownloadMemoryRunOptions,
): Promise<DownloadMemoryResult> {
  const metadata = createDownloadMetadata(options);
  const metadataContainedPlaintextKey = Boolean(metadata.initialKey);
  const executionMetadata = { ...metadata };
  let keyGrantConsumed = false;
  if (options.format === "FRAMED_V2") {
    executionMetadata.initialKey = consumeSyntheticDownloadKeyGrant(options);
    keyGrantConsumed = true;
  }
  const root = await navigator.storage.getDirectory();
  const fileName = `pw-${crypto.randomUUID()}.bin`;
  const handle = await root.getFileHandle(fileName, { create: true });
  await seedOpfsFile(handle);

  const controller = new AbortController();
  const synthetic = createSyntheticResponse(options);
  const delegate = await createFileSystemDownloadSink(handle);
  const sink = new ObservedSink(delegate, options, controller);
  let responseUsed = false;
  let fetchAttempts = 0;
  const fetchImpl: typeof fetch = async (_input) => {
    fetchAttempts += 1;
    if (options.failure === "refresh-stable" && fetchAttempts === 1) {
      return new Response(null, { status: 401 });
    }
    if (options.failure === "expired-401") {
      return new Response(null, { status: 401 });
    }
    if (options.failure === "expired-403") {
      return new Response(null, { status: 403 });
    }
    if (responseUsed) throw new Error("synthetic response reused");
    responseUsed = true;
    return synthetic.response;
  };
  const metrics = new DownloadMetricsTracker();
  let ok = false;
  let errorMessage: string | undefined;
  try {
    await executeBoundedDownload({
      metadata: executionMetadata,
      expectedFileHash: metadata.fileHash,
      sink,
      signal: controller.signal,
      fetchImpl,
      metrics,
      refreshMetadata:
        options.failure === "refresh-stable"
          ? async () => ({
              ...executionMetadata,
              parts: executionMetadata.parts.map((part) => ({
                ...part,
                downloadUrl: `${part.downloadUrl}?refreshed=1`,
                expiresAtEpochSeconds: part.expiresAtEpochSeconds + 300,
              })),
            })
          : undefined,
    });
    ok = true;
  } catch (error) {
    errorMessage = error instanceof Error ? error.message : String(error);
  } finally {
    executionMetadata.initialKey = undefined;
  }

  const file = await handle.getFile();
  const outputValid = ok
    ? await validateOutputSamples(file, metadata.fileSize)
    : false;
  const sentinelPreserved = ok ? false : await isSentinelPreserved(file);
  const result: DownloadMemoryResult = {
    ok,
    format: options.format,
    sizeMiB: options.sizeMiB,
    error: errorMessage,
    metrics: metrics.snapshot(),
    fetchAttempts,
    sinkCloseCalls: sink.closeCalls,
    sinkAbortCalls: sink.abortCalls,
    streamCancelled: synthetic.state.cancelled,
    sentinelPreserved,
    outputValid,
    outputSize: file.size,
    fileName,
    metadataContainedPlaintextKey,
    keyGrantConsumed,
  };
  await root.removeEntry(fileName);
  return result;
}

const runs = new Map<string, RunEntry>();

/** 启动异步下载场景，供 Playwright 在 Chromium 外部采样 heap。 */
function start(options: DownloadMemoryRunOptions): string {
  const runId = crypto.randomUUID();
  const entry: RunEntry = {
    done: false,
  };
  entry.promise = runScenario(options).then(
    (result) => {
      entry.done = true;
      entry.result = result;
      return result;
    },
    (error: unknown) => {
      const result: DownloadMemoryResult = {
        ok: false,
        format: options.format,
        sizeMiB: options.sizeMiB,
        error: error instanceof Error ? error.message : String(error),
        metrics: {
          currentBufferedBytes: 0,
          peakBufferedBytes: 0,
          framesAuthenticated: 0,
          partsCompleted: 0,
          bytesWritten: 0,
        },
        fetchAttempts: 0,
        sinkCloseCalls: 0,
        sinkAbortCalls: 0,
        streamCancelled: false,
        sentinelPreserved: false,
        outputValid: false,
        outputSize: 0,
        fileName: "",
        metadataContainedPlaintextKey: false,
        keyGrantConsumed: options.format === "FRAMED_V2",
      };
      entry.done = true;
      entry.result = result;
      return result;
    },
  );
  runs.set(runId, entry);
  return runId;
}

/** 返回当前场景是否完成以及已完成结果。 */
function status(runId: string): DownloadMemoryRunStatus {
  const entry = runs.get(runId);
  if (!entry) throw new Error(`unknown download run: ${runId}`);
  return { done: entry.done, result: entry.result };
}

/** 等待场景结束并返回完整指标。 */
async function wait(runId: string): Promise<DownloadMemoryResult> {
  const entry = runs.get(runId);
  if (!entry?.promise) throw new Error(`unknown download run: ${runId}`);
  return entry.promise;
}

declare global {
  interface Window {
    downloadMemoryGate: DownloadMemoryGateApi;
  }
}

/** 将门禁 API 暴露到测试页，保持生产路由与后端完全隔离。 */
function installDownloadMemoryGate(): void {
  window.downloadMemoryGate = { start, status, wait };
}

installDownloadMemoryGate();
