import type { UploadPolicyVO } from "$api/types";
import { getFileExtension } from "$utils/file-preview";

const GENERIC_BINARY_MIME = "application/octet-stream";

/**
 * Normalizes browser MIME values for comparison with the server policy.
 */
export function normalizeUploadMime(contentType: string): string {
  return (contentType.split(";", 1)[0] ?? "").trim().toLowerCase();
}

/**
 * Builds the file input accept contract from server-authorized extensions.
 */
export function buildUploadAccept(policy: UploadPolicyVO): string {
  return policy.fileTypes.map((fileType) => `.${fileType.extension}`).join(",");
}

/**
 * Produces a stable human-readable category summary from the server policy.
 */
export function describeUploadPolicy(policy: UploadPolicyVO): string {
  return [
    ...new Set(policy.fileTypes.map((fileType) => fileType.categoryLabel)),
  ].join("、");
}

/**
 * Performs a fast client-side mirror of the authoritative extension and MIME checks.
 */
export function validateFileAgainstUploadPolicy(
  file: Pick<File, "name" | "size" | "type">,
  policy: UploadPolicyVO,
): string | null {
  if (file.size > policy.maxFileSizeBytes) {
    return `文件超过 ${policy.maxFileSizeBytes} 字节限制`;
  }
  const extension = getFileExtension(file.name);
  const categorySummary =
    describeUploadPolicy(policy) || "服务器允许的文件类型";
  if (!extension) {
    return `文件缺少有效扩展名；可上传类型：${categorySummary}`;
  }
  const fileType = policy.fileTypes.find(
    (candidate) => candidate.extension === extension,
  );
  if (!fileType) {
    return `不支持的文件扩展名 .${extension}；可上传类型：${categorySummary}`;
  }
  const normalizedMime = normalizeUploadMime(file.type);
  if (!normalizedMime || normalizedMime === GENERIC_BINARY_MIME) {
    return null;
  }
  if (!fileType.mimeTypes.includes(normalizedMime)) {
    return `文件扩展名 .${extension} 与内容类型 ${normalizedMime} 不匹配；可上传类型：${categorySummary}`;
  }
  return null;
}
