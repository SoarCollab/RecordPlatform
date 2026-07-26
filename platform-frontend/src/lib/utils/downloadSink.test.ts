import { describe, expect, it, vi } from "vitest";

import {
  createFileSystemDownloadSink,
  FileSystemDownloadSink,
  MemoryDownloadSink,
} from "./downloadSink";

describe("download sinks", () => {
  it("should commit an empty file without synthetic writes", async () => {
    const sink = new MemoryDownloadSink(0, 64);
    await sink.close();
    expect(sink.getData()).toHaveLength(0);
  });

  it("should keep random writes bounded and reject holes on close", async () => {
    const sink = new MemoryDownloadSink(4, 64);
    await sink.writeAt(2, new Uint8Array([3, 4]));
    await expect(sink.close()).rejects.toThrow("下载长度不完整");
    await sink.writeAt(0, new Uint8Array([1, 2]));
    await sink.close();
    expect(Array.from(sink.getData())).toEqual([1, 2, 3, 4]);
  });

  it("should enforce the fallback size cap and abort output", async () => {
    expect(() => new MemoryDownloadSink(65, 64)).toThrow("64 MiB");
    const sink = new MemoryDownloadSink(2, 64);
    await sink.write(new Uint8Array([1, 2]));
    await sink.abort();
    expect(() => sink.getData()).toThrow("尚未成功提交");
  });

  it("should wrap a transactional file writable", async () => {
    const writable = {
      write: vi.fn(async () => {}),
      seek: vi.fn(async () => {}),
      close: vi.fn(async () => {}),
      abort: vi.fn(async () => {}),
    };
    const sink = await createFileSystemDownloadSink({
      createWritable: vi.fn(async () => writable),
    });
    expect(sink).toBeInstanceOf(FileSystemDownloadSink);
    await sink.write(new Uint8Array([1, 2]));
    await sink.writeAt(4, new Uint8Array([3]));
    await sink.close();
    await sink.abort("ignored after close");
    expect(writable.write).toHaveBeenCalledTimes(2);
    expect(writable.seek).toHaveBeenCalledWith(4);
    expect(writable.close).toHaveBeenCalledTimes(1);
    expect(writable.abort).toHaveBeenCalledTimes(1);
  });

  it("should reject a handle without transactional abort", async () => {
    await expect(
      createFileSystemDownloadSink({
        createWritable: vi.fn(async () => ({
          write: vi.fn(),
          seek: vi.fn(),
          close: vi.fn(),
        })),
      }),
    ).rejects.toThrow("事务性文件写入");
  });
});
