import { describe, it, expect } from "vitest";
import { env } from "$env/dynamic/public";
import { getAvatarUrl } from "./avatar";

describe("getAvatarUrl", () => {
  it("should return undefined for empty path", () => {
    expect(getAvatarUrl()).toBeUndefined();
    expect(getAvatarUrl("")).toBeUndefined();
  });

  it("should passthrough absolute URLs", () => {
    expect(getAvatarUrl("https://example.com/a.png")).toBe(
      "https://example.com/a.png",
    );
    expect(getAvatarUrl("http://example.com/a.png")).toBe(
      "http://example.com/a.png",
    );
  });

  it("should build download URL for relative paths", () => {
    const apiBase = import.meta.env.DEV
      ? "/record-platform/api/v1"
      : `${env.PUBLIC_API_BASE_URL || "/record-platform"}/api/v1`;

    expect(getAvatarUrl("avatar/abc.png")).toBe(
      `${apiBase}/images/download/images/avatar/abc.png`,
    );
    expect(getAvatarUrl("/avatar/abc.png")).toBe(
      `${apiBase}/images/download/images/avatar/abc.png`,
    );
  });
});
