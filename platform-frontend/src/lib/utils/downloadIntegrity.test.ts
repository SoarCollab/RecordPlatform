import { describe, expect, it } from "vitest";

import { sha256 } from "@noble/hashes/sha2.js";
import {
  assertSha256,
  bytesToHex,
  createSha256,
  decodeBase64,
  equalBytes,
  parseExpectedSha256,
} from "./downloadIntegrity";

describe("download integrity helpers", () => {
  it("should hash incrementally and accept canonical sha256 forms", () => {
    const digest = sha256(new TextEncoder().encode("abc"));
    const incremental = createSha256();
    incremental.update(new Uint8Array([97, 98]));
    incremental.update(new Uint8Array([99]));
    expect(bytesToHex(incremental.digest())).toBe(bytesToHex(digest));
    expect(parseExpectedSha256(`sha256:${bytesToHex(digest)}`)).toEqual(digest);
    expect(parseExpectedSha256(btoa(String.fromCharCode(...digest)))).toEqual(
      digest,
    );
    assertSha256(digest, `sha256:${bytesToHex(digest)}`, "测试");
  });

  it("should decode base64url and reject mismatched digests", () => {
    expect(Array.from(decodeBase64("AQI-_w"))).toEqual([1, 2, 62, 255]);
    expect(equalBytes(new Uint8Array([1]), new Uint8Array([1]))).toBe(true);
    expect(equalBytes(new Uint8Array([1]), new Uint8Array([2]))).toBe(false);
    expect(equalBytes(new Uint8Array([1]), new Uint8Array([1, 2]))).toBe(false);
    expect(() => assertSha256(new Uint8Array(32), "sha256:00", "测试")).toThrow(
      "Base64 数据格式无效",
    );
    expect(() =>
      assertSha256(new Uint8Array(32), `sha256:${"01".repeat(32)}`, "测试"),
    ).toThrow("测试 SHA-256 校验失败");
  });

  it("should reject non-canonical base64 and invalid digest lengths", () => {
    const invalidBase64 = [
      " AQI=",
      "AQI===",
      "A",
      "AQI==",
      "AQI-_w==",
      "AQI+/w",
      "!!!!",
    ];

    for (const value of invalidBase64) {
      expect(() => decodeBase64(value)).toThrow("Base64 数据格式无效");
    }

    expect(() => parseExpectedSha256("AQI=")).toThrow(
      "manifest SHA-256 摘要长度无效",
    );
    expect(parseExpectedSha256("AA".repeat(32))).toEqual(
      new Uint8Array(32).fill(0xaa),
    );
  });
});
