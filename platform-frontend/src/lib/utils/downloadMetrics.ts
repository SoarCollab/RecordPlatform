/** 有界下载期间暴露给测试与诊断的应用层指标。 */
export interface DownloadStreamMetrics {
  currentBufferedBytes: number;
  peakBufferedBytes: number;
  framesAuthenticated: number;
  partsCompleted: number;
  bytesWritten: number;
}

/** 统一记录 parser、解密器与 sink 之间的短生命周期缓冲。 */
export class DownloadMetricsTracker {
  private readonly metrics: DownloadStreamMetrics = {
    currentBufferedBytes: 0,
    peakBufferedBytes: 0,
    framesAuthenticated: 0,
    partsCompleted: 0,
    bytesWritten: 0,
  };

  /** 记录新持有的缓冲区字节数。 */
  acquire(bytes: number): void {
    if (!Number.isSafeInteger(bytes) || bytes < 0) {
      throw new Error("无效的下载缓冲区大小");
    }
    this.metrics.currentBufferedBytes += bytes;
    this.metrics.peakBufferedBytes = Math.max(
      this.metrics.peakBufferedBytes,
      this.metrics.currentBufferedBytes,
    );
  }

  /** 释放已经消费的缓冲区字节数。 */
  release(bytes: number): void {
    if (!Number.isSafeInteger(bytes) || bytes < 0) {
      throw new Error("无效的下载缓冲区大小");
    }
    this.metrics.currentBufferedBytes -= bytes;
    if (this.metrics.currentBufferedBytes < 0) {
      throw new Error("下载缓冲区计数失衡");
    }
  }

  /** 记录一个通过 AEAD tag 验证的 frame。 */
  authenticatedFrame(): void {
    this.metrics.framesAuthenticated++;
  }

  /** 记录一个完成长度与哈希校验的 part。 */
  completedPart(): void {
    this.metrics.partsCompleted++;
  }

  /** 记录已经写入临时 sink 的明文字节。 */
  wrote(bytes: number): void {
    if (!Number.isSafeInteger(bytes) || bytes < 0) {
      throw new Error("无效的下载写入大小");
    }
    this.metrics.bytesWritten += bytes;
  }

  /** 返回不可变指标快照。 */
  snapshot(): DownloadStreamMetrics {
    return { ...this.metrics };
  }
}
