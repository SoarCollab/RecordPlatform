import { sha256 } from 'k6/crypto';

export const FIXTURE_CONTENT_TYPE = 'text/plain';

/** Build exact-size UTF-8 text with deterministic, per-run/VU/iteration/part identity. */
export function buildTextFixture(size, identity) {
  if (!Number.isSafeInteger(size) || size < 1) {
    throw new Error('Fixture size must be a positive safe integer');
  }
  if (typeof identity !== 'string' || identity.length === 0) {
    throw new Error('Fixture identity is required');
  }
  // Start with identity entropy so even small parts do not share a fixed prefix.
  const line = `${sha256(identity, 'hex')} RecordPlatform load-test text fixture.\n`;
  const bytes = new Uint8Array(size);
  for (let index = 0; index < size; index += 1) {
    bytes[index] = line.charCodeAt(index % line.length);
  }
  return bytes.buffer;
}
