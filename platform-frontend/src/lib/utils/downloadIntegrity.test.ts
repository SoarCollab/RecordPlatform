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
    expect(() => assertSha256(new Uint8Array(32), "sha256:00", "测试")).toThrow(
      "Base64 数据格式无效",
    );
  });
});
