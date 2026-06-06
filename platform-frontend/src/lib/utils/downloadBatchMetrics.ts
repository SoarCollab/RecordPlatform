import type { BatchDownloadMetricsReportRequest } from "$api/types";

export interface DownloadBatchFailureSummary {
  reason?: string | null;
}

export interface DownloadBatchMetricsSnapshot {
  id: string;
  total: number;
  successCount: number;
  failedCount: number;
  failures: DownloadBatchFailureSummary[];
  startedAt: number;
  completedAt: number | null;
}

/**
 * Calculate retry count from a task attempt count.
 */
export function calculateRetryCount(attempts: number): number {
  return Math.max(0, attempts - 1);
}

/**
 * Build a failure reason distribution from batch download failures.
 */
export function buildFailureReasonsDistribution(
  failures: readonly DownloadBatchFailureSummary[],
): Record<string, number> {
  const distribution: Record<string, number> = {};
  for (const failure of failures) {
    const reason = (failure.reason ?? "unknown").trim() || "unknown";
    distribution[reason] = (distribution[reason] ?? 0) + 1;
  }
  return distribution;
}

/**
 * Build the API payload for reporting completed batch download metrics.
 */
export function buildBatchMetricsPayload(
  snapshot: DownloadBatchMetricsSnapshot,
  retryCount: number,
): BatchDownloadMetricsReportRequest {
  const completedAt = snapshot.completedAt ?? Date.now();
  return {
    batchId: snapshot.id,
    total: snapshot.total,
    successCount: snapshot.successCount,
    failedCount: snapshot.failedCount,
    retryCount,
    durationMs: Math.max(0, completedAt - snapshot.startedAt),
    failureReasons: buildFailureReasonsDistribution(snapshot.failures),
  };
}
