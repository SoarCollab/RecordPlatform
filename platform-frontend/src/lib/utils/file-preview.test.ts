import { describe, expect, it, vi } from "vitest";
import { loadPreviewText } from "./file-preview";

describe("loadPreviewText", () => {
  it.each(["blob:https://example.test/file", "data:text/plain,hello"])(
    "loads same-document URL %s with native fetch",
    async (url) => {
      const platformFetchText = vi.fn();
      const nativeFetch = vi.fn().mockResolvedValue(
        new Response("decrypted plaintext", {
          status: 200,
          headers: { "Content-Type": "text/plain" },
        }),
      );

      await expect(
        loadPreviewText(url, platformFetchText, nativeFetch),
      ).resolves.toBe("decrypted plaintext");
      expect(nativeFetch).toHaveBeenCalledWith(url);
      expect(platformFetchText).not.toHaveBeenCalled();
    },
  );

  it("keeps platform API fetching for API paths", async () => {
    const platformFetchText = vi.fn().mockResolvedValue("api plaintext");
    const nativeFetch = vi.fn();

    await expect(
      loadPreviewText(
        "/api/v1/files/example/content",
        platformFetchText,
        nativeFetch,
      ),
    ).resolves.toBe("api plaintext");
    expect(platformFetchText).toHaveBeenCalledWith(
      "/api/v1/files/example/content",
    );
    expect(nativeFetch).not.toHaveBeenCalled();
  });

  it("reports a failed native response", async () => {
    const nativeFetch = vi.fn().mockResolvedValue(
      new Response("", {
        status: 400,
        statusText: "Bad Request",
      }),
    );

    await expect(
      loadPreviewText("blob:https://example.test/file", vi.fn(), nativeFetch),
    ).rejects.toThrow("请求失败 (400)");
  });
});
