import { sha256 } from "@noble/hashes/sha2.js";

/** 将摘要字节编码为小写十六进制。 */
export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
}

/** 解码标准或 URL-safe Base64，拒绝非规范输入。 */
export function decodeBase64(value: string): Uint8Array {
  if (value !== value.trim()) {
    throw new Error("Base64 数据格式无效");
  }
  const paddingIndex = value.indexOf("=");
  const unpadded = paddingIndex === -1 ? value : value.slice(0, paddingIndex);
  const padding = paddingIndex === -1 ? "" : value.slice(paddingIndex);
  if (
    !/^[A-Za-z0-9+/_-]*$/.test(unpadded) ||
    !/^={0,2}$/.test(padding) ||
    unpadded.length % 4 === 1 ||
    (padding.length > 0 && (unpadded.length + padding.length) % 4 !== 0) ||
    (padding.length > 0 && value.includes("-")) ||
    (padding.length > 0 && value.includes("_"))
  ) {
    throw new Error("Base64 数据格式无效");
  }
  const normalized = unpadded.replace(/-/g, "+").replace(/_/g, "/");
  const requiredPadding = (4 - (normalized.length % 4)) % 4;
  if (padding.length !== 0 && padding.length !== requiredPadding) {
    throw new Error("Base64 数据格式无效");
  }
  const padded = normalized + "=".repeat(requiredPadding);
  try {
    const decoded = Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
    const binary = String.fromCharCode(...decoded);
    const canonicalStandard = btoa(binary);
    const canonicalUrl = canonicalStandard
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/g, "");
    if (value !== canonicalStandard && value !== canonicalUrl) {
      throw new Error("non-canonical Base64");
    }
    return decoded;
  } catch {
    throw new Error("Base64 数据格式无效");
  }
}

/** 解析 manifest 中允许的 SHA-256 十六进制或 Base64 表示。 */
export function parseExpectedSha256(value: string): Uint8Array {
  const trimmed = value.trim();
  const hex = trimmed.startsWith("sha256:")
    ? trimmed.slice("sha256:".length)
    : trimmed;
  if (/^[0-9a-fA-F]{64}$/.test(hex)) {
    return Uint8Array.from(hex.match(/.{2}/g) ?? [], (pair) =>
      Number.parseInt(pair, 16),
    );
  }
  const decoded = decodeBase64(trimmed);
  if (decoded.byteLength !== 32) {
    throw new Error("manifest SHA-256 摘要长度无效");
  }
  return decoded;
}

/** 使用固定循环比较摘要，避免依赖字符串编码差异。 */
export function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  if (left.byteLength !== right.byteLength) return false;
  let difference = 0;
  for (let index = 0; index < left.byteLength; index++) {
    difference |= left[index] ^ right[index];
  }
  return difference === 0;
}

/** 校验实际 SHA-256 是否匹配 manifest 声明。 */
export function assertSha256(
  actual: Uint8Array,
  expected: string,
  label: string,
): void {
  if (!equalBytes(actual, parseExpectedSha256(expected))) {
    throw new Error(`${label} SHA-256 校验失败`);
  }
}

/** 创建支持增量 update/digest 的 SHA-256 实例。 */
export function createSha256() {
  return sha256.create();
}
