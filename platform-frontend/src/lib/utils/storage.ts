import { browser } from "$app/environment";

const PREFIX = "rp_"; // record platform prefix

/**
 * 获取存储项
 */
export function getItem<T>(key: string, defaultValue: T): T {
  if (!browser) return defaultValue;

  try {
    const item = localStorage.getItem(PREFIX + key);
    if (item === null) return defaultValue;
    return JSON.parse(item) as T;
  } catch {
    return defaultValue;
  }
}

/**
 * 设置存储项
 */
export function setItem<T>(key: string, value: T): void {
  if (!browser) return;

  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch (err) {
    console.error("Failed to save to localStorage:", err);
  }
}

/**
 * 移除存储项
 */
export function removeItem(key: string): void {
  if (!browser) return;
  localStorage.removeItem(PREFIX + key);
}

/**
 * 清除所有前缀存储项
 */
export function clearAll(): void {
  if (!browser) return;

  const keys: string[] = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (key?.startsWith(PREFIX)) {
      keys.push(key);
    }
  }

  keys.forEach((key) => localStorage.removeItem(key));
}

/**
 * 主题存储
 */
export const theme = {
  get: () => getItem<"light" | "dark" | "system">("theme", "system"),
  set: (value: "light" | "dark" | "system") => setItem("theme", value),
};

/**
 * 侧边栏状态
 */
export const sidebar = {
  get: () => getItem<boolean>("sidebar_collapsed", false),
  set: (collapsed: boolean) => setItem("sidebar_collapsed", collapsed),
};
