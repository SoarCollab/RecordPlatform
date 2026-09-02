import { describe, expect, it } from "vitest";
import { FileStatus } from "$api/types";
import {
  FILE_STATUS_FILTER_OPTIONS,
  toFileStatusQuery,
} from "./file-status-filter";

describe("file status filter", () => {
  it("exposes only user-queryable persisted statuses", () => {
    expect(FILE_STATUS_FILTER_OPTIONS).toEqual([
      { value: FileStatus.PROCESSING, label: "处理中" },
      { value: FileStatus.COMPLETED, label: "已完成" },
      { value: FileStatus.DELETED, label: "已删除" },
      { value: FileStatus.FAILED, label: "失败" },
    ]);
    expect(FILE_STATUS_FILTER_OPTIONS.map(({ label }) => label)).not.toContain(
      "-",
    );
  });

  it("maps only the empty UI sentinel to an omitted API parameter", () => {
    expect(toFileStatusQuery("")).toBeUndefined();
    expect(toFileStatusQuery(FileStatus.PROCESSING)).toBe(
      FileStatus.PROCESSING,
    );
    expect(toFileStatusQuery(FileStatus.FAILED)).toBe(FileStatus.FAILED);
  });
});
