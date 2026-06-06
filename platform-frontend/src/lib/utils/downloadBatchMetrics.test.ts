import { afterEach, describe, expect, it, vi } from "vitest";
import {
  buildBatchMetricsPayload,
  buildFailureReasonsDistribution,
  calculateRetryCount,
} from "./downloadBatchMetrics";

describe("download batch metrics utilities", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("should calculate retry count from attempts", () => {
    expect(calculateRetryCount(0)).toBe(0);
    expect(calculateRetryCount(1)).toBe(0);
    expect(calculateRetryCount(3)).toBe(2);
  });

  it("should group blank and missing failure reasons as unknown", () => {
    expect(
      buildFailureReasonsDistribution([
        { reason: "network_error" },
        { reason: " network_error " },
        { reason: "" },
        { reason: "   " },
        { reason: null },
        {},
      ]),
    ).toEqual({
      network_error: 2,
      unknown: 4,
    });
  });

  it("should build a metrics payload from a completed snapshot", () => {
    expect(
      buildBatchMetricsPayload(
        {
          id: "batch-1",
          total: 3,
          successCount: 2,
          failedCount: 1,
          failures: [{ reason: "network_error" }],
          startedAt: 1000,
          completedAt: 2500,
        },
        2,
      ),
    ).toEqual({
      batchId: "batch-1",
      total: 3,
      successCount: 2,
      failedCount: 1,
      retryCount: 2,
      durationMs: 1500,
      failureReasons: { network_error: 1 },
    });
  });

  it("should use current time when completedAt is missing", () => {
    vi.useFakeTimers();
    vi.setSystemTime(5000);

    expect(
      buildBatchMetricsPayload(
        {
          id: "batch-open",
          total: 1,
          successCount: 1,
          failedCount: 0,
          failures: [],
          startedAt: 3000,
          completedAt: null,
        },
        0,
      ).durationMs,
    ).toBe(2000);
  });

  it("should never report a negative duration", () => {
    expect(
      buildBatchMetricsPayload(
        {
          id: "batch-clock-skew",
          total: 1,
          successCount: 0,
          failedCount: 1,
          failures: [],
          startedAt: 3000,
          completedAt: 1000,
        },
        0,
      ).durationMs,
    ).toBe(0);
  });
});
