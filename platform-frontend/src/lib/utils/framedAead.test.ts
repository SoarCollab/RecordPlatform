import { sha256 } from "@noble/hashes/sha2.js";
import { describe, expect, it } from "vitest";

import {
  buildFramedAad,
  deriveFramedKeyMaterial,
  downloadFramedPart,
  FRAMED_AEAD_AAD_SCHEMA,
  FRAMED_AEAD_FORMAT_VERSION,
  FRAMED_AEAD_SUITE,
  FRAMED_AEAD_TAG_BYTES,
} from "./framedAead";
import { bytesToHex } from "./downloadIntegrity";
import { DownloadMetricsTracker } from "./downloadMetrics";
import { MemoryDownloadSink } from "./downloadSink";

const FRAME_SIZE = 64 * 1024;
const fileNonce = Uint8Array.from({ length: 16 }, (_, index) => index + 1);
const fileDek = Uint8Array.from({ length: 32 }, (_, index) => 0xa0 + index);

function writeUint32(bytes: Uint8Array, offset: number, value: number): void {
  new DataView(bytes.buffer).setUint32(offset, value, false);
}

async function makeFixture(): Promise<{
  encoded: Uint8Array;
  plaintext: Uint8Array;
  part: {
    index: number;
    size: number;
    downloadUrl: string;
    expiresAtEpochSeconds: number;
    storagePath: string;
    plainHash: string;
    cipherHash: string;
    checksumAlgorithm: string;
    plainSize: number;
    frameCount: number;
  };
  encryption: {
    formatVersion: number;
    algorithmSuite: string;
    fileNonce: string;
    framePlainSize: number;
    keyDerivation: string;
    nonceDerivation: string;
    aadSchema: string;
    tagSize: number;
  };
}> {
  const plaintext = new Uint8Array(FRAME_SIZE + 3);
  for (let index = 0; index < plaintext.length; index++) {
    plaintext[index] = index % 251;
  }
  const frameCount = 2;
  const encodedParts: Uint8Array[] = [];
  const header = new Uint8Array(44);
  header.set(new TextEncoder().encode("RPF2"), 0);
  header[4] = 2;
  header[5] = 1;
  writeUint32(header, 8, 0);
  writeUint32(header, 12, 1);
  writeUint32(header, 16, FRAME_SIZE);
  writeUint32(header, 20, frameCount);
  writeUint32(header, 24, plaintext.length);
  header.set(fileNonce, 28);
  encodedParts.push(header);

  for (let frameIndex = 0; frameIndex < frameCount; frameIndex++) {
    const plainLength = Math.min(
      FRAME_SIZE,
      plaintext.length - frameIndex * FRAME_SIZE,
    );
    const framePlaintext = plaintext.slice(
      frameIndex * FRAME_SIZE,
      frameIndex * FRAME_SIZE + plainLength,
    );
    const material = deriveFramedKeyMaterial({
      fileDek,
      fileNonce,
      chunkIndex: 0,
      frameIndex,
    });
    const aad = buildFramedAad({
      fileNonce,
      chunkIndex: 0,
      chunkCount: 1,
      frameIndex,
      frameCount,
      plainLength,
      chunkPlainSize: plaintext.length,
    });
    const key = await globalThis.crypto.subtle.importKey(
      "raw",
      material.key as unknown as BufferSource,
      { name: "AES-GCM" },
      false,
      ["encrypt"],
    );
    const ciphertext = new Uint8Array(
      await globalThis.crypto.subtle.encrypt(
        {
          name: "AES-GCM",
          iv: material.nonce as unknown as BufferSource,
          additionalData: aad as unknown as BufferSource,
          tagLength: 128,
        },
        key,
        framePlaintext as unknown as BufferSource,
      ),
    );
    const frameHeader = new Uint8Array(12);
    writeUint32(frameHeader, 0, frameIndex);
    writeUint32(frameHeader, 4, plainLength);
    writeUint32(frameHeader, 8, ciphertext.length);
    encodedParts.push(frameHeader, ciphertext);
  }

  const encoded = new Uint8Array(
    encodedParts.reduce((sum, value) => sum + value.length, 0),
  );
  let offset = 0;
  for (const value of encodedParts) {
    encoded.set(value, offset);
    offset += value.length;
  }
  const encryption = {
    formatVersion: FRAMED_AEAD_FORMAT_VERSION,
    algorithmSuite: FRAMED_AEAD_SUITE,
    fileNonce: btoa(String.fromCharCode(...fileNonce)),
    framePlainSize: FRAME_SIZE,
    keyDerivation: "HKDF-SHA256",
    nonceDerivation: "HKDF-SHA256",
    aadSchema: FRAMED_AEAD_AAD_SCHEMA,
    tagSize: FRAMED_AEAD_TAG_BYTES,
  };
  const part = {
    index: 0,
    size: encoded.length,
    downloadUrl: "https://objects.test/part-0",
    expiresAtEpochSeconds: 2_000_000_000,
    storagePath: "tenant/part-0",
    plainHash: `sha256:${bytesToHex(sha256(plaintext))}`,
    cipherHash: `sha256:${bytesToHex(sha256(encoded))}`,
    checksumAlgorithm: "SHA-256",
    plainSize: plaintext.length,
    frameCount,
  };
  return { encoded, plaintext, part, encryption };
}

function responseFromArbitraryChunks(bytes: Uint8Array): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      let offset = 0;
      const sizes = [1, 7, 31, 1024, 3, 4096];
      let index = 0;
      while (offset < bytes.length) {
        const size = Math.min(
          sizes[index++ % sizes.length],
          bytes.length - offset,
        );
        controller.enqueue(bytes.slice(offset, offset + size));
        offset += size;
      }
      controller.close();
    },
  });
  return new Response(stream, {
    headers: { "content-length": String(bytes.length) },
  });
}

describe("framedAead", () => {
  it("should decrypt arbitrary network boundaries and write only authenticated frames", async () => {
    const fixture = await makeFixture();
    const sink = new MemoryDownloadSink(fixture.plaintext.length, 1 << 20);
    const metrics = new DownloadMetricsTracker();
    await downloadFramedPart({
      response: responseFromArbitraryChunks(fixture.encoded),
      part: fixture.part,
      partCount: 1,
      encryption: fixture.encryption,
      fileDekBase64: btoa(String.fromCharCode(...fileDek)),
      sink,
      metrics,
    });
    expect(metrics.snapshot()).toMatchObject({
      currentBufferedBytes: 0,
      framesAuthenticated: 2,
      partsCompleted: 1,
      bytesWritten: fixture.plaintext.length,
    });
    await sink.close();
    expect(Array.from(sink.getData())).toEqual(Array.from(fixture.plaintext));
  });

  it("should fail closed before writing a tampered frame", async () => {
    const fixture = await makeFixture();
    const tampered = fixture.encoded.slice();
    tampered[tampered.length - 1] ^= 0xff;
    const sink = new MemoryDownloadSink(fixture.plaintext.length, 1 << 20);
    const metrics = new DownloadMetricsTracker();
    await expect(
      downloadFramedPart({
        response: responseFromArbitraryChunks(tampered),
        part: fixture.part,
        partCount: 1,
        encryption: fixture.encryption,
        fileDekBase64: btoa(String.fromCharCode(...fileDek)),
        sink,
        metrics,
      }),
    ).rejects.toThrow("认证失败");
    expect(metrics.snapshot()).toMatchObject({
      currentBufferedBytes: 0,
      framesAuthenticated: 1,
    });
  });

  it("should cancel the response reader when the download signal is aborted", async () => {
    const fixture = await makeFixture();
    const controller = new AbortController();
    let cancelled = false;
    const response = new Response(
      new ReadableStream<Uint8Array>({
        start(streamController) {
          streamController.enqueue(fixture.encoded.subarray(0, 44));
        },
        cancel() {
          cancelled = true;
        },
      }),
    );
    controller.abort();

    await expect(
      downloadFramedPart({
        response,
        part: fixture.part,
        partCount: 1,
        encryption: fixture.encryption,
        fileDekBase64: btoa(String.fromCharCode(...fileDek)),
        sink: new MemoryDownloadSink(fixture.plaintext.length, 1 << 20),
        metrics: new DownloadMetricsTracker(),
        signal: controller.signal,
      }),
    ).rejects.toThrow("Download cancelled");
    expect(cancelled).toBe(true);
  });
});
