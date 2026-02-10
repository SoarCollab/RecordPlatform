import { describe, expect, it } from "vitest";
import { cn } from "./utils";

describe("lib utils", () => {
  it("cn 应合并 class 并处理 Tailwind 冲突", () => {
    const hiddenClass: string | undefined = undefined;
    const result = cn("px-2", "px-4", "text-sm", hiddenClass);
    expect(result).toContain("px-4");
    expect(result).not.toContain("px-2");
    expect(result).toContain("text-sm");
  });
});
