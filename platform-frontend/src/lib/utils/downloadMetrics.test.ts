import { describe, expect, it } from "vitest";

import { DownloadMetricsTracker } from "./downloadMetrics";

describe("download metrics tracker", () => {
  it("should track peak buffers, authenticated frames, parts, and writes", () => {
    const tracker = new DownloadMetricsTracker();

    tracker.acquire(4);
    tracker.acquire(3);
    tracker.release(5);
    tracker.authenticatedFrame();
    tracker.completedPart();
    tracker.wrote(6);

    const snapshot = tracker.snapshot();
    expect(snapshot).toEqual({
      currentBufferedBytes: 2,
      peakBufferedBytes: 7,
      framesAuthenticated: 1,
      partsCompleted: 1,
      bytesWritten: 6,
    });
    snapshot.currentBufferedBytes = 99;
    expect(tracker.snapshot().currentBufferedBytes).toBe(2);
  });

  it.each([[-1], [1.5], [Number.MAX_SAFE_INTEGER + 1]])(
    "should reject invalid buffer byte counts: %s",
    (bytes) => {
      expect(() => new DownloadMetricsTracker().acquire(bytes)).toThrow(
        "无效的下载缓冲区大小",
      );
      expect(() => new DownloadMetricsTracker().release(bytes)).toThrow(
        "无效的下载缓冲区大小",
      );
    },
  );

  it("should reject release imbalance and invalid write byte counts", () => {
    expect(() => new DownloadMetricsTracker().release(1)).toThrow(
      "下载缓冲区计数失衡",
    );
    expect(() => new DownloadMetricsTracker().wrote(-1)).toThrow(
      "无效的下载写入大小",
    );
    expect(() => new DownloadMetricsTracker().wrote(0.5)).toThrow(
      "无效的下载写入大小",
    );
  });
});
