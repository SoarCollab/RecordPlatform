import { describe, expect, it } from "vitest";

import { DownloadMetricsTracker } from "./downloadMetrics";
import { DownloadStreamReader } from "./downloadStreamReader";

function responseFromChunks(chunks: number[][]): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(new Uint8Array(chunk));
      controller.close();
    },
  });
  return new Response(stream);
}

describe("DownloadStreamReader", () => {
  it("should read exact bytes across arbitrary network boundaries", async () => {
    const metrics = new DownloadMetricsTracker();
    const response = responseFromChunks([[0, 1], [2], [3, 4, 5]]);
    const reader = new DownloadStreamReader(
      response.body!.getReader(),
      metrics,
    );
    const first = await reader.readExact(5, "header");
    expect(Array.from(first)).toEqual([0, 1, 2, 3, 4]);
    reader.release(first);
    await expect(reader.assertEof()).rejects.toThrow("尾部字节");
    expect(metrics.snapshot().currentBufferedBytes).toBe(0);
  });

  it("should reject oversized network chunks and release reader state", async () => {
    const metrics = new DownloadMetricsTracker();
    const response = responseFromChunks([[1, 2, 3, 4, 5]]);
    const reader = new DownloadStreamReader(
      response.body!.getReader(),
      metrics,
      undefined,
      4,
    );
    await expect(reader.readExact(1, "byte")).rejects.toThrow("有界下载上限");
    expect(metrics.snapshot().currentBufferedBytes).toBe(0);
  });

  it("should track peak buffering and cancel safely", async () => {
    const metrics = new DownloadMetricsTracker();
    const response = responseFromChunks([[1, 2]]);
    const reader = new DownloadStreamReader(
      response.body!.getReader(),
      metrics,
    );
    const data = await reader.readExact(2, "bytes");
    expect(metrics.snapshot().peakBufferedBytes).toBeGreaterThanOrEqual(2);
    reader.release(data);
    await reader.cancel("done");
    expect(metrics.snapshot().currentBufferedBytes).toBe(0);
  });
});
