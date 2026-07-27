export type DownloadMemoryFormat = "NONE" | "FRAMED_V2";

export type DownloadMemoryFailure =
  | "tamper"
  | "truncate"
  | "cancel"
  | "abort"
  | "reorder"
  | "duplicate"
  | "wrong-key"
  | "cross-file"
  | "expired-401"
  | "expired-403";

export interface DownloadMemoryRunOptions {
  format: DownloadMemoryFormat;
  sizeMiB: 64 | 256 | 512;
  failure?: DownloadMemoryFailure;
}

export interface DownloadMemoryMetrics {
  currentBufferedBytes: number;
  peakBufferedBytes: number;
  framesAuthenticated: number;
  partsCompleted: number;
  bytesWritten: number;
}

export interface DownloadMemoryResult {
  ok: boolean;
  format: DownloadMemoryFormat;
  sizeMiB: DownloadMemoryRunOptions["sizeMiB"];
  error?: string;
  metrics: DownloadMemoryMetrics;
  fetchAttempts: number;
  sinkCloseCalls: number;
  sinkAbortCalls: number;
  streamCancelled: boolean;
  sentinelPreserved: boolean;
  outputValid: boolean;
  outputSize: number;
  fileName: string;
  metadataContainedPlaintextKey: boolean;
  keyGrantConsumed: boolean;
}

export interface DownloadMemoryRunStatus {
  done: boolean;
  result?: DownloadMemoryResult;
}

export interface DownloadMemoryGateApi {
  start(options: DownloadMemoryRunOptions): string;
  status(runId: string): DownloadMemoryRunStatus;
  wait(runId: string): Promise<DownloadMemoryResult>;
}
