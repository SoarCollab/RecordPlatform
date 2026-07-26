import type { DownloadMetricsTracker } from "./downloadMetrics";

interface IncrementalHash {
  update(data: Uint8Array): IncrementalHash;
}

// 网络块上限保持在 1MiB，避免与 framed ciphertext/明文缓冲叠加后失控。
const DEFAULT_MAX_NETWORK_CHUNK_BYTES = 1 * 1024 * 1024;

/** 在任意网络分块边界上提供有界 readExact 与 EOF 校验。 */
export class DownloadStreamReader {
  private current: Uint8Array | null = null;
  private currentOffset = 0;
  private ended = false;

  constructor(
    private readonly reader: ReadableStreamDefaultReader<Uint8Array>,
    private readonly metrics: DownloadMetricsTracker,
    private readonly hash?: IncrementalHash,
    private readonly maxNetworkChunkBytes = DEFAULT_MAX_NETWORK_CHUNK_BYTES,
  ) {}

  /** 精确读取指定字节数，并把消费字节计入可选摘要。 */
  async readExact(length: number, label: string): Promise<Uint8Array> {
    if (!Number.isSafeInteger(length) || length < 0) {
      throw new Error(`${label} 长度无效`);
    }

    const output = new Uint8Array(length);
    this.metrics.acquire(length);
    let written = 0;
    try {
      while (written < length) {
        await this.ensureCurrentChunk();
        if (!this.current) {
          throw new Error(`${label} 被截断`);
        }

        const available = this.current.byteLength - this.currentOffset;
        const take = Math.min(available, length - written);
        const slice = this.current.subarray(
          this.currentOffset,
          this.currentOffset + take,
        );
        output.set(slice, written);
        this.hash?.update(slice);
        written += take;
        this.currentOffset += take;
        this.releaseCurrentIfConsumed();
      }
      return output;
    } catch (error) {
      this.metrics.release(length);
      throw error;
    }
  }

  /** 释放 readExact 返回值对应的缓冲计数。 */
  release(data: Uint8Array): void {
    this.metrics.release(data.byteLength);
  }

  /** 确认协议声明结束后没有任何尾部字节。 */
  async assertEof(): Promise<void> {
    await this.ensureCurrentChunk();
    if (this.current) {
      const trailing = this.current.subarray(this.currentOffset);
      this.hash?.update(trailing);
      this.releaseCurrent();
      throw new Error("加密分片包含未声明的尾部字节");
    }
  }

  /** 取消底层 reader，并释放尚未消费的网络缓冲。 */
  async cancel(reason?: unknown): Promise<void> {
    this.releaseCurrent();
    try {
      await this.reader.cancel(reason);
    } catch {
      // reader 可能已被 fetch 关闭，取消失败不覆盖原始错误。
    }
  }

  /** 获取下一段非空网络数据。 */
  private async ensureCurrentChunk(): Promise<void> {
    while (!this.current && !this.ended) {
      const { done, value } = await this.reader.read();
      if (done) {
        this.ended = true;
        return;
      }
      if (!value || value.byteLength === 0) continue;
      if (value.byteLength > this.maxNetworkChunkBytes) {
        await this.reader.cancel("network chunk exceeds bounded limit");
        throw new Error("网络读取块超过有界下载上限");
      }
      this.current = value;
      this.currentOffset = 0;
      this.metrics.acquire(value.byteLength);
    }
  }

  /** 当前网络块消费完毕后立即释放引用与指标。 */
  private releaseCurrentIfConsumed(): void {
    if (this.current && this.currentOffset === this.current.byteLength) {
      this.releaseCurrent();
    }
  }

  /** 释放当前网络读取块。 */
  private releaseCurrent(): void {
    if (!this.current) return;
    this.metrics.release(this.current.byteLength);
    this.current = null;
    this.currentOffset = 0;
  }
}

/** 获取可流式读取的响应体，缺失时失败关闭。 */
export function requireResponseReader(
  response: Response,
): ReadableStreamDefaultReader<Uint8Array> {
  if (!response.body) {
    throw new Error("浏览器未提供流式响应体");
  }
  return response.body.getReader();
}

/** 校验 HTTP Content-Length 与 manifest 声明一致。 */
export function assertContentLength(
  response: Response,
  expected: number,
): void {
  const raw = response.headers.get("content-length");
  if (!raw) return;
  const actual = Number(raw);
  if (!Number.isSafeInteger(actual) || actual < 0 || actual !== expected) {
    throw new Error(`下载响应长度不匹配: 期望 ${expected}，实际 ${raw}`);
  }
}
