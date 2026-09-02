export type PlatformTextFetcher = (url: string) => Promise<string>;

export type NativePreviewFetcher = (
  url: string,
) => Promise<Pick<Response, "ok" | "status" | "blob">>;

export const MAX_TEXT_PREVIEW_BYTES = 100_000;
export const TEXT_PREVIEW_TRUNCATION_NOTICE =
  "\n\n... (内容过长，仅显示前 100000 字节)";

export type FilePreviewType =
  | "image"
  | "video"
  | "audio"
  | "pdf"
  | "text"
  | "unsupported";

const NATIVE_IMAGE_EXTENSIONS = new Set([
  "jpg",
  "jpeg",
  "png",
  "gif",
  "webp",
  "avif",
  "bmp",
]);
const NATIVE_AUDIO_EXTENSIONS = new Set([
  "mp3",
  "wav",
  "ogg",
  "oga",
  "m4a",
  "aac",
  "flac",
  "opus",
]);
const NATIVE_VIDEO_EXTENSIONS = new Set([
  "mp4",
  "m4v",
  "webm",
  "mov",
  "avi",
  "mkv",
]);
const TEXT_EXTENSIONS = new Set([
  "txt",
  "md",
  "csv",
  "tsv",
  "json",
  "jsonl",
  "xml",
  "yaml",
  "yml",
  "log",
  "ini",
  "conf",
  "properties",
  "sql",
  "html",
  "htm",
  "svg",
  "css",
  "js",
  "mjs",
  "cjs",
  "ts",
  "tsx",
  "jsx",
  "java",
  "py",
  "go",
  "rs",
  "c",
  "cc",
  "cpp",
  "h",
  "hpp",
  "sh",
  "bash",
  "zsh",
  "ps1",
]);

/**
 * Extracts a lowercase extension without the leading dot.
 */
export function getFileExtension(fileName: string): string | null {
  const dotIndex = fileName.lastIndexOf(".");
  if (dotIndex <= 0 || dotIndex === fileName.length - 1) return null;
  return fileName.slice(dotIndex + 1).toLowerCase();
}

/**
 * Classifies previews by the filename policy so active SVG/HTML content is always escaped text.
 */
export function classifyFilePreview(
  fileName: string,
  contentType: string,
): FilePreviewType {
  const extension = getFileExtension(fileName);
  if (!extension) return "unsupported";
  if (TEXT_EXTENSIONS.has(extension)) return "text";
  if (extension === "pdf") return "pdf";
  if (NATIVE_IMAGE_EXTENSIONS.has(extension)) return "image";
  if (NATIVE_AUDIO_EXTENSIONS.has(extension)) return "audio";
  if (NATIVE_VIDEO_EXTENSIONS.has(extension)) return "video";

  const normalizedType = contentType.split(";", 1)[0]?.trim().toLowerCase();
  if (normalizedType === "application/pdf" && extension === "pdf") return "pdf";
  return "unsupported";
}

/**
 * Reports whether the shared preview component can safely render a file inline.
 */
export function isFilePreviewable(
  fileName: string,
  contentType: string,
): boolean {
  return classifyFilePreview(fileName, contentType) !== "unsupported";
}

/**
 * Loads preview text without routing already-decrypted same-document URLs through the API client.
 */
export async function loadPreviewText(
  url: string,
  platformFetchText: PlatformTextFetcher,
  nativeFetch: NativePreviewFetcher = globalThis.fetch,
  maxBytes = MAX_TEXT_PREVIEW_BYTES,
): Promise<string> {
  if (!url.startsWith("blob:") && !url.startsWith("data:")) {
    return platformFetchText(url);
  }

  const response = await nativeFetch(url);
  if (!response.ok) {
    throw new Error(`请求失败 (${response.status})`);
  }
  const source = await response.blob();
  const boundedBytes = Math.max(0, maxBytes);
  const text = await source.slice(0, boundedBytes).text();
  return source.size > boundedBytes
    ? text + TEXT_PREVIEW_TRUNCATION_NOTICE
    : text;
}
