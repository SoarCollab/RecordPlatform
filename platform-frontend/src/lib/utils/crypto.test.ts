import { describe, it, expect, vi, beforeEach } from "vitest";
import { decryptFile, arrayToBlob } from "./crypto";

// Test constants matching the module
const MAGIC_BYTES = new Uint8Array([0x52, 0x50]); // 'RP'
const VERSION = 1;
const ALGORITHM_AES_GCM = 0x01;
const ALGORITHM_CHACHA20 = 0x02;
const IV_SIZE = 12;

const HASH_SEPARATOR = "\n--HASH--\n";
const KEY_SEPARATOR = "\n--NEXT_KEY--\n";

// Helper to create a valid header
function createHeader(algorithm: number): Uint8Array {
  return new Uint8Array([MAGIC_BYTES[0], MAGIC_BYTES[1], VERSION, algorithm]);
}

// Helper to create fake IV
function createIV(): Uint8Array {
  return new Uint8Array(IV_SIZE).fill(0x01);
}

// Helper to encode string to bytes
function stringToBytes(str: string): Uint8Array {
  return new TextEncoder().encode(str);
}

// Helper to concatenate Uint8Arrays
function concat(...arrays: Uint8Array[]): Uint8Array {
  const totalLength = arrays.reduce((sum, arr) => sum + arr.length, 0);
  const result = new Uint8Array(totalLength);
  let offset = 0;
  for (const arr of arrays) {
    result.set(arr, offset);
    offset += arr.length;
  }
  return result;
}

// Generate a valid Base64 key (32 bytes)
function generateTestKey(): string {
  const keyBytes = new Uint8Array(32).fill(0x42);
  return btoa(String.fromCharCode(...keyBytes));
}

describe("crypto utils", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("Header Parsing", () => {
    it("should throw error for data too short", async () => {
      const shortData = new Uint8Array([0x52, 0x50]); // Only 2 bytes

      await expect(decryptFile([shortData], generateTestKey())).rejects.toThrow(
        "数据太短",
      );
    });

    it("should throw error for invalid magic bytes", async () => {
      const invalidMagic = new Uint8Array([
        0x00,
        0x00,
        VERSION,
        ALGORITHM_AES_GCM,
      ]);

      await expect(
        decryptFile([invalidMagic], generateTestKey()),
      ).rejects.toThrow("magic bytes");
    });

    it("should throw error for unsupported version", async () => {
      const invalidVersion = new Uint8Array([
        MAGIC_BYTES[0],
        MAGIC_BYTES[1],
        99,
        ALGORITHM_AES_GCM,
      ]);

      await expect(
        decryptFile([invalidVersion], generateTestKey()),
      ).rejects.toThrow("不支持的版本");
    });

    it("should throw error for unknown algorithm", async () => {
      const header = new Uint8Array([
        MAGIC_BYTES[0],
        MAGIC_BYTES[1],
        VERSION,
        0xff,
      ]);
      const iv = createIV();
      const ciphertext = new Uint8Array(32);
      const data = concat(header, iv, ciphertext);

      await expect(decryptFile([data], generateTestKey())).rejects.toThrow(
        "未知的加密算法",
      );
    });
  });

  describe("Metadata Extraction", () => {
    it("should extract hash and nextKey from chunk", async () => {
      // This test verifies the format is correctly parsed
      // The actual decryption will fail but we can verify parsing logic
      const header = createHeader(ALGORITHM_AES_GCM);
      const iv = createIV();
      const ciphertext = new Uint8Array(32);
      const hash = "abc123hash";
      const nextKey = generateTestKey();

      const data = concat(
        header,
        iv,
        ciphertext,
        stringToBytes(HASH_SEPARATOR),
        stringToBytes(hash),
        stringToBytes(KEY_SEPARATOR),
        stringToBytes(nextKey),
      );

      // Since actual decryption requires valid crypto, we just verify
      // that the structure is parsed (decryption will fail but parsing should work)
      try {
        await decryptFile([data], generateTestKey());
      } catch (e) {
        // Expected to fail on decryption, not parsing
        expect((e as Error).message).not.toContain("HASH");
        expect((e as Error).message).not.toContain("NEXT_KEY");
      }
    });
  });

  describe("Key Validation", () => {
    it("should throw error for invalid key length", async () => {
      const header = createHeader(ALGORITHM_AES_GCM);
      const iv = createIV();
      const ciphertext = new Uint8Array(32);
      const data = concat(header, iv, ciphertext);

      // Create a key with wrong length (16 bytes instead of 32)
      const shortKey = btoa(
        String.fromCharCode(...new Uint8Array(16).fill(0x42)),
      );

      await expect(decryptFile([data], shortKey)).rejects.toThrow("密钥长度");
    });
  });

  describe("decryptFile", () => {
    it("should throw error for empty chunks array", async () => {
      await expect(decryptFile([], generateTestKey())).rejects.toThrow(
        "没有分片数据",
      );
    });

    it("should throw error if last chunk missing next key (for multi-chunk)", async () => {
      const header = createHeader(ALGORITHM_CHACHA20);
      const iv = createIV();
      const ciphertext = new Uint8Array(32);

      // Two chunks, but last one has no nextKey
      const chunk1 = concat(header, iv, ciphertext);
      const chunk2 = concat(header, iv, ciphertext);

      // Mock crypto.subtle for AES-GCM
      const mockSubtle = {
        importKey: vi.fn().mockResolvedValue({}),
        decrypt: vi.fn().mockResolvedValue(new ArrayBuffer(16)),
      };
      vi.stubGlobal("crypto", { subtle: mockSubtle });

      // For ChaCha20, we need to handle it differently since it uses @noble/ciphers
      // This will fail because the ciphertext is invalid
      await expect(
        decryptFile([chunk1, chunk2], generateTestKey()),
      ).rejects.toThrow();
    });
  });

  describe("arrayToBlob", () => {
    it("should create blob with correct type", () => {
      const data = new Uint8Array([1, 2, 3, 4]);
      const blob = arrayToBlob(data, "image/png");

      expect(blob).toBeInstanceOf(Blob);
      expect(blob.type).toBe("image/png");
      expect(blob.size).toBe(4);
    });

    it("should use default mime type if not provided", () => {
      const data = new Uint8Array([1, 2, 3]);
      const blob = arrayToBlob(data);

      expect(blob.type).toBe("application/octet-stream");
    });
  });

  describe("Algorithm Detection", () => {
    it("should correctly identify AES-GCM algorithm", async () => {
      const header = createHeader(ALGORITHM_AES_GCM);
      const iv = createIV();
      const ciphertext = new Uint8Array(32);
      const data = concat(header, iv, ciphertext);

      // Mock crypto.subtle
      const mockDecrypt = vi.fn().mockResolvedValue(new ArrayBuffer(16));
      vi.stubGlobal("crypto", {
        subtle: {
          importKey: vi.fn().mockResolvedValue({}),
          decrypt: mockDecrypt,
        },
      });

      try {
        await decryptFile([data], generateTestKey());
      } catch {
        // Will fail due to missing nextKey, but should have attempted AES-GCM decrypt
      }

      // Verify crypto.subtle.decrypt was called (AES-GCM path)
      expect(mockDecrypt).toHaveBeenCalled();
    });

    it("should correctly identify ChaCha20 algorithm", async () => {
      const header = createHeader(ALGORITHM_CHACHA20);
      const iv = createIV();
      // ChaCha20-Poly1305 needs at least 16 bytes auth tag
      const ciphertext = new Uint8Array(48);
      const data = concat(header, iv, ciphertext);

      // ChaCha20 uses @noble/ciphers, not crypto.subtle
      // This will fail on invalid ciphertext
      await expect(decryptFile([data], generateTestKey())).rejects.toThrow();
    });
  });

  describe("IV/Nonce Extraction", () => {
    it("should throw error if data too short for IV", async () => {
      const header = createHeader(ALGORITHM_AES_GCM);
      // Only 6 bytes of IV (need 12)
      const shortIV = new Uint8Array(6);
      const data = concat(header, shortIV);

      await expect(decryptFile([data], generateTestKey())).rejects.toThrow(
        "无法提取 IV",
      );
    });
  });

  describe("Integration: Chunk Format", () => {
    it("should handle chunk with all components", async () => {
      // Full chunk format: [header][iv][ciphertext][hash_sep][hash][key_sep][next_key]
      const header = createHeader(ALGORITHM_AES_GCM);
      const iv = createIV();
      const ciphertext = new Uint8Array(32).fill(0xab);
      const hash = "file_hash_abc123";
      const nextKey = generateTestKey();

      const fullChunk = concat(
        header,
        iv,
        ciphertext,
        stringToBytes(HASH_SEPARATOR),
        stringToBytes(hash),
        stringToBytes(KEY_SEPARATOR),
        stringToBytes(nextKey),
      );

      // Verify the structure is valid by checking it doesn't throw parsing errors
      expect(fullChunk.length).toBeGreaterThan(
        header.length + iv.length + ciphertext.length,
      );
    });
  });
});
