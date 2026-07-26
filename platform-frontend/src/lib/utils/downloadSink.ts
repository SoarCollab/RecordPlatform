/** 浏览器下载写入目标的最小事务接口。 */
export interface DownloadSink {
  readonly supportsRandomAccess: boolean;
  write(data: Uint8Array): Promise<void>;
  writeAt(position: number, data: Uint8Array): Promise<void>;
  close(): Promise<void>;
  abort(reason?: unknown): Promise<void>;
}

interface WritableFileStreamLike {
  write(data: BufferSource | Blob | string): Promise<void>;
  seek(position: number): Promise<void>;
  close(): Promise<void>;
  abort(reason?: unknown): Promise<void>;
}

interface WritableFileHandleLike {
  createWritable(): Promise<WritableFileStreamLike>;
}

/** 将字节复制到独立 ArrayBuffer，避免 SharedArrayBuffer 类型与生命周期问题。 */
function toArrayBuffer(data: Uint8Array): ArrayBuffer {
  const buffer = new ArrayBuffer(data.byteLength);
  new Uint8Array(buffer).set(data);
  return buffer;
}

/** 包装 File System Access writable，直到 close 才提交临时文件。 */
export class FileSystemDownloadSink implements DownloadSink {
  readonly supportsRandomAccess = true;

  constructor(private readonly writable: WritableFileStreamLike) {}

  /** 顺序写入已经校验或认证的数据。 */
  async write(data: Uint8Array): Promise<void> {
    await this.writable.write(toArrayBuffer(data));
  }

  /** 定位到指定明文偏移后写入历史 v1 分片。 */
  async writeAt(position: number, data: Uint8Array): Promise<void> {
    if (!Number.isSafeInteger(position) || position < 0) {
      throw new Error("无效的下载写入偏移");
    }
    await this.writable.seek(position);
    await this.writable.write(toArrayBuffer(data));
  }

  /** 提交已完整校验的下载文件。 */
  async close(): Promise<void> {
    await this.writable.close();
  }

  /** 放弃未完成输出，避免部分文件被当成成功结果。 */
  async abort(reason?: unknown): Promise<void> {
    await this.writable.abort(reason);
  }
}

/** 创建并校验 File System Access 下载目标。 */
export async function createFileSystemDownloadSink(
  fileHandle: unknown,
): Promise<FileSystemDownloadSink> {
  const candidate = fileHandle as Partial<WritableFileHandleLike> | null;
  if (!candidate || typeof candidate.createWritable !== "function") {
    throw new Error("下载文件句柄无效");
  }

  const writable = await candidate.createWritable();
  if (
    typeof writable.write !== "function" ||
    typeof writable.seek !== "function" ||
    typeof writable.close !== "function" ||
    typeof writable.abort !== "function"
  ) {
    throw new Error("浏览器不支持事务性文件写入");
  }
  return new FileSystemDownloadSink(writable);
}

/** 小文件内存回退 sink；创建时即按声明大小限制总内存。 */
export class MemoryDownloadSink implements DownloadSink {
  readonly supportsRandomAccess = true;
  private readonly data: Uint8Array;
  private position = 0;
  private writtenRanges: Array<{ start: number; end: number }> = [];
  private closed = false;
  private aborted = false;

  constructor(
    private readonly expectedSize: number,
    maxBytes: number,
  ) {
    if (
      !Number.isSafeInteger(expectedSize) ||
      expectedSize < 0 ||
      expectedSize > maxBytes
    ) {
      throw new Error("文件超过浏览器 64 MiB 内存回退上限");
    }
    this.data = new Uint8Array(expectedSize);
  }

  /** 在当前顺序偏移写入数据。 */
  async write(data: Uint8Array): Promise<void> {
    await this.writeAt(this.position, data);
    this.position += data.byteLength;
  }

  /** 在固定范围内写入数据，越界时立即失败。 */
  async writeAt(position: number, data: Uint8Array): Promise<void> {
    this.ensureWritable();
    const end = position + data.byteLength;
    if (
      !Number.isSafeInteger(position) ||
      position < 0 ||
      !Number.isSafeInteger(end) ||
      end > this.expectedSize
    ) {
      throw new Error("下载数据超出声明文件大小");
    }
    this.data.set(data, position);
    this.recordWrittenRange(position, end);
  }

  /** 只有完整写满声明大小时才提交内存结果。 */
  async close(): Promise<void> {
    this.ensureWritable();
    const complete =
      (this.expectedSize === 0 && this.writtenRanges.length === 0) ||
      (this.writtenRanges.length === 1 &&
        this.writtenRanges[0]?.start === 0 &&
        this.writtenRanges[0]?.end === this.expectedSize);
    if (!complete) {
      const writtenBytes = this.writtenRanges.reduce(
        (sum, range) => sum + range.end - range.start,
        0,
      );
      throw new Error(
        `下载长度不完整: 期望 ${this.expectedSize}，实际 ${writtenBytes}`,
      );
    }
    this.closed = true;
  }

  /** 标记内存输出已放弃并清零。 */
  async abort(): Promise<void> {
    if (this.aborted) return;
    this.aborted = true;
    this.data.fill(0);
  }

  /** 返回已提交的内存文件数据。 */
  getData(): Uint8Array {
    if (!this.closed || this.aborted) {
      throw new Error("下载尚未成功提交");
    }
    return this.data;
  }

  /** 校验 sink 仍可写入。 */
  private ensureWritable(): void {
    if (this.closed || this.aborted) {
      throw new Error("下载写入目标已关闭");
    }
  }

  /** 合并已写区间，用于识别随机写入留下的空洞。 */
  private recordWrittenRange(start: number, end: number): void {
    if (start === end) return;
    const ranges = [...this.writtenRanges, { start, end }].sort(
      (left, right) => left.start - right.start,
    );
    const merged: Array<{ start: number; end: number }> = [];
    for (const range of ranges) {
      const last = merged.at(-1);
      if (!last || range.start > last.end) {
        merged.push({ ...range });
      } else {
        last.end = Math.max(last.end, range.end);
      }
    }
    this.writtenRanges = merged;
  }
}
