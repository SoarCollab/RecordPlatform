import { env } from "$env/dynamic/public";

/**
 * 将后端返回的头像/图片路径转换为可直接访问的 URL。
 *
 * 约定：
 * - 后端通常返回类似 `/avatar/xxx` 的存储相对路径（不是可直接访问的静态资源路径）。
 * - 图片下载接口为 `/record-platform/api/v1/images/download/images/**`（生产环境前缀可配置）。
 * - 若后端返回的是 `http(s)://` 绝对 URL，则直接透传。
 *
 * @param path 后端返回的头像路径
 * @returns 可用于 `<img src>` 的 URL；若入参为空则返回 `undefined`
 */
export function getAvatarUrl(path?: string): string | undefined {
  if (!path) return undefined;
  if (path.startsWith("http://") || path.startsWith("https://")) return path;

  const apiBase = import.meta.env.DEV
    ? "/record-platform/api/v1"
    : `${env.PUBLIC_API_BASE_URL || "/record-platform"}/api/v1`;

  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${apiBase}/images/download/images${normalizedPath}`;
}
