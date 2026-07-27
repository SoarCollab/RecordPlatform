import { expect, test, type Page, type TestInfo } from "@playwright/test";

import type {
  DownloadMemoryGateApi,
  DownloadMemoryResult,
  DownloadMemoryRunOptions,
} from "./types";

const SIZES = [64, 256, 512] as const;
const BUFFER_LIMIT = 4 * 1024 * 1024;

type GateWindow = Window & { downloadMemoryGate?: DownloadMemoryGateApi };

interface SampledRun {
  options: DownloadMemoryRunOptions;
  result: DownloadMemoryResult;
  heapStartUsedBytes: number;
  heapPeakUsedBytes: number;
  heapEndUsedBytes: number;
}

/** 打开隔离测试页并确认门禁 API 已由 Vite 模块安装。 */
async function openGate(page: Page): Promise<void> {
  await page.goto("/e2e/download-memory/");
  await expect(page.locator("h1")).toHaveText("Download memory gate");
  await page.waitForFunction(() =>
    Boolean((window as GateWindow).downloadMemoryGate),
  );
}

/** 启动浏览器内下载，同时用 Chromium CDP 采集辅助 heap 指标。 */
async function runWithHeapSampling(
  page: Page,
  options: DownloadMemoryRunOptions,
  testInfo: TestInfo,
): Promise<SampledRun> {
  const session = await page.context().newCDPSession(page);
  await session.send("HeapProfiler.enable");
  await session.send("HeapProfiler.collectGarbage");
  const runId = await page.evaluate((runOptions) => {
    const gate = (window as GateWindow).downloadMemoryGate;
    if (!gate) throw new Error("download memory gate is not installed");
    return gate.start(runOptions);
  }, options);
  const heapSamples: number[] = [];
  let done = false;
  while (!done) {
    const usage = await session.send("Runtime.getHeapUsage");
    heapSamples.push(usage.usedSize);
    const status = await page.evaluate((id) => {
      const gate = (window as GateWindow).downloadMemoryGate;
      if (!gate) throw new Error("download memory gate disappeared");
      return gate.status(id);
    }, runId);
    done = status.done;
    if (!done) {
      if (heapSamples.length % 5 === 0) {
        await session.send("HeapProfiler.collectGarbage");
      }
      await page.waitForTimeout(100);
    }
  }
  const result = await page.evaluate((id) => {
    const gate = (window as GateWindow).downloadMemoryGate;
    if (!gate) throw new Error("download memory gate disappeared");
    return gate.wait(id);
  }, runId);
  expect(result.metadataContainedPlaintextKey).toBe(false);
  expect(result.keyGrantConsumed).toBe(options.format === "FRAMED_V2");
  const heapStartUsedBytes = heapSamples[0] ?? 0;
  const heapPeakUsedBytes = Math.max(...heapSamples, heapStartUsedBytes);
  const heapEndUsedBytes = heapSamples.at(-1) ?? heapStartUsedBytes;
  await testInfo.attach(
    `download-memory-${options.format.toLowerCase()}-${options.sizeMiB}mib-${options.failure ?? "success"}`,
    {
      body: JSON.stringify(
        {
          options,
          result,
          heapStartUsedBytes,
          heapPeakUsedBytes,
          heapEndUsedBytes,
          chromiumVersion: page.context().browser()?.version(),
        },
        null,
        2,
      ),
      contentType: "application/json",
    },
  );
  await session.detach();
  return {
    options,
    result,
    heapStartUsedBytes,
    heapPeakUsedBytes,
    heapEndUsedBytes,
  };
}

/** 断言 NONE 大文件使用真实 OPFS sink 且应用层 buffer 不随文件放大。 */
test("NONE 64/256/512MiB stays within a non-growing buffer budget", async ({
  page,
}, testInfo) => {
  await openGate(page);
  const runs: SampledRun[] = [];
  for (const sizeMiB of SIZES) {
    const run = await runWithHeapSampling(
      page,
      { format: "NONE", sizeMiB },
      testInfo,
    );
    runs.push(run);
    expect(run.result.ok, run.result.error).toBe(true);
    expect(run.result.metrics.currentBufferedBytes).toBe(0);
    expect(run.result.metrics.framesAuthenticated).toBe(0);
    expect(run.result.metrics.partsCompleted).toBe(1);
    expect(run.result.metrics.bytesWritten).toBe(sizeMiB * 1024 * 1024);
    expect(run.result.metrics.peakBufferedBytes).toBeLessThanOrEqual(
      BUFFER_LIMIT,
    );
    expect(run.result.sinkCloseCalls).toBe(1);
    expect(run.result.sinkAbortCalls).toBe(0);
    expect(run.result.outputValid).toBe(true);
    expect(run.result.outputSize).toBe(sizeMiB * 1024 * 1024);
  }
  const peaks = runs.map((run) => run.result.metrics.peakBufferedBytes);
  expect(peaks[2]).toBeLessThanOrEqual(peaks[0] + 1024 * 1024);
  const heapDeltas = runs.map(
    (run) => run.heapPeakUsedBytes - run.heapStartUsedBytes,
  );
  expect(heapDeltas[2]).toBeLessThan(heapDeltas[0] + 64 * 1024 * 1024);
  expect(heapDeltas[2]).toBeLessThan(192 * 1024 * 1024);
});

/** 断言 framed v2 三档文件逐 frame 认证后才写入 OPFS，且 buffer 不增长。 */
test("framed v2 64/256/512MiB authenticates bounded frames", async ({
  page,
}, testInfo) => {
  await openGate(page);
  const runs: SampledRun[] = [];
  for (const sizeMiB of SIZES) {
    const run = await runWithHeapSampling(
      page,
      { format: "FRAMED_V2", sizeMiB },
      testInfo,
    );
    runs.push(run);
    expect(run.result.ok, run.result.error).toBe(true);
    expect(run.result.metrics.currentBufferedBytes).toBe(0);
    expect(run.result.metrics.framesAuthenticated).toBe(sizeMiB);
    expect(run.result.metrics.partsCompleted).toBe(1);
    expect(run.result.metrics.bytesWritten).toBe(sizeMiB * 1024 * 1024);
    expect(run.result.metrics.peakBufferedBytes).toBeLessThanOrEqual(
      BUFFER_LIMIT,
    );
    expect(run.result.sinkCloseCalls).toBe(1);
    expect(run.result.sinkAbortCalls).toBe(0);
    expect(run.result.outputValid).toBe(true);
    expect(run.result.outputSize).toBe(sizeMiB * 1024 * 1024);
  }
  const peaks = runs.map((run) => run.result.metrics.peakBufferedBytes);
  expect(peaks[2]).toBeLessThanOrEqual(peaks[0] + 1024 * 1024);
  const heapDeltas = runs.map(
    (run) => run.heapPeakUsedBytes - run.heapStartUsedBytes,
  );
  expect(heapDeltas[2]).toBeLessThan(heapDeltas[0] + 64 * 1024 * 1024);
  expect(heapDeltas[2]).toBeLessThan(192 * 1024 * 1024);
});

/** 断言篡改、截断、取消和 sink abort 都在 close 前失败并保留原 OPFS 文件。 */
test("tamper, truncate, cancel and sink abort fail closed", async ({
  page,
}, testInfo) => {
  await openGate(page);
  const failures: Array<DownloadMemoryRunOptions> = [
    { format: "FRAMED_V2", sizeMiB: 64, failure: "tamper" },
    { format: "FRAMED_V2", sizeMiB: 64, failure: "truncate" },
    { format: "NONE", sizeMiB: 64, failure: "cancel" },
    { format: "FRAMED_V2", sizeMiB: 64, failure: "abort" },
  ];
  for (const options of failures) {
    const run = await runWithHeapSampling(page, options, testInfo);
    expect(run.result.ok).toBe(false);
    expect(run.result.error).toBeTruthy();
    expect(run.result.sinkCloseCalls).toBe(0);
    expect(run.result.sinkAbortCalls).toBeGreaterThan(0);
    expect(run.result.sentinelPreserved).toBe(true);
    if (options.failure === "cancel") {
      expect(run.result.streamCancelled).toBe(true);
    }
  }
});

/** 断言 frame 坐标、密钥/AAD 绑定和过期 URL 都在 close 前失败。 */
test("reorder, duplicate, key substitution and expired URLs fail closed", async ({
  page,
}, testInfo) => {
  await openGate(page);
  const failures: Array<{
    options: DownloadMemoryRunOptions;
    expectedError: string;
  }> = [
    {
      options: { format: "FRAMED_V2", sizeMiB: 64, failure: "reorder" },
      expectedError: "framed AEAD frame header 与 manifest 不一致",
    },
    {
      options: { format: "FRAMED_V2", sizeMiB: 64, failure: "duplicate" },
      expectedError: "framed AEAD frame header 与 manifest 不一致",
    },
    {
      options: { format: "FRAMED_V2", sizeMiB: 64, failure: "wrong-key" },
      expectedError: "framed AEAD 分片 0 frame 0 认证失败",
    },
    {
      options: { format: "FRAMED_V2", sizeMiB: 64, failure: "cross-file" },
      expectedError: "framed AEAD 分片 0 frame 0 认证失败",
    },
    {
      options: { format: "FRAMED_V2", sizeMiB: 64, failure: "expired-401" },
      expectedError: "下载地址已过期，请重新发起下载",
    },
    {
      options: { format: "FRAMED_V2", sizeMiB: 64, failure: "expired-403" },
      expectedError: "下载地址已过期，请重新发起下载",
    },
  ];

  for (const { options, expectedError } of failures) {
    const run = await runWithHeapSampling(page, options, testInfo);
    expect(run.result.ok).toBe(false);
    expect(run.result.error).toBe(expectedError);
    expect(run.result.sinkCloseCalls).toBe(0);
    expect(run.result.sinkAbortCalls).toBeGreaterThan(0);
    expect(run.result.sentinelPreserved).toBe(true);
    expect(run.result.fetchAttempts).toBe(1);
  }
});
