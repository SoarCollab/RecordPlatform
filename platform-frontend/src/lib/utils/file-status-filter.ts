import { FileStatus } from "$api/types";

export type FileStatusFilter = FileStatus | "";

export const FILE_STATUS_FILTER_OPTIONS = [
  { value: FileStatus.PROCESSING, label: "处理中" },
  { value: FileStatus.COMPLETED, label: "已完成" },
  { value: FileStatus.DELETED, label: "已删除" },
  { value: FileStatus.FAILED, label: "失败" },
] as const;

/**
 * Converts the UI's empty all-status sentinel into an omitted API query value.
 */
export function toFileStatusQuery(
  status: FileStatusFilter,
): FileStatus | undefined {
  return status === "" ? undefined : status;
}
