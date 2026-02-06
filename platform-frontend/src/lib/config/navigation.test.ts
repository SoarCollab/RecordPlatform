import { describe, expect, it } from "vitest";
import { adminItems, menuItems } from "./navigation";

describe("navigation config", () => {
  it("menuItems 应包含核心业务入口并保持 href 唯一", () => {
    const hrefSet = new Set(menuItems.map((item) => item.href));
    expect(hrefSet.size).toBe(menuItems.length);

    expect(menuItems.some((item) => item.href === "/dashboard")).toBe(true);
    expect(menuItems.some((item) => item.href === "/files")).toBe(true);
    expect(menuItems.some((item) => item.href === "/upload")).toBe(true);
    expect(menuItems.some((item) => item.href === "/tickets")).toBe(true);
  });

  it("adminItems 应包含管理员核心模块入口", () => {
    const hrefs = adminItems.map((item) => item.href);
    expect(hrefs).toEqual([
      "/admin/files",
      "/admin/tickets",
      "/admin/monitor",
      "/admin/audit",
      "/admin/permissions",
    ]);
  });
});
