import { sha256 } from 'k6/crypto';
import { check } from 'k6';
import { buildTextFixture, FIXTURE_CONTENT_TYPE } from '../lib/fixture.js';

export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };

/** Fail the actual k6 process on any fixture contract violation. */
function assert(condition, description) {
  if (!check(condition, { [description]: (value) => value })) throw new Error(description);
}

/** Exercise real ArrayBuffer/hash behavior without touching any application endpoint. */
export default function () {
  assert(FIXTURE_CONTENT_TYPE === 'text/plain', 'Fixture MIME must match txt');
  for (const size of [1, 65, 65536, 1048576]) {
    const buffer = buildTextFixture(size, 'run-one:vu-1:iter-1:part-0');
    const bytes = new Uint8Array(buffer);
    assert(buffer instanceof ArrayBuffer && buffer.byteLength === size, 'Exact byte length');
    assert(bytes.every((byte) => byte === 10 || (byte >= 32 && byte <= 126)), 'Genuine ASCII text');
    assert(sha256(buffer, 'hex') === sha256(buildTextFixture(size, 'run-one:vu-1:iter-1:part-0'), 'hex'),
      'Repeatable hash');
  }
  const identities = ['run-one:vu-1:iter-1:part-0', 'run-two:vu-1:iter-1:part-0',
    'run-one:vu-2:iter-1:part-0', 'run-one:vu-1:iter-2:part-0', 'run-one:vu-1:iter-1:part-1'];
  const hashes = identities.map((identity) => sha256(buildTextFixture(65536, identity), 'hex'));
  assert(new Set(hashes).size === identities.length, 'Run/VU/iteration/part isolation');
  for (const size of [0, -1, 1.5, NaN, Infinity]) {
    let rejected = false;
    try { buildTextFixture(size, 'fixture'); } catch (_) { rejected = true; }
    assert(rejected, 'Invalid sizes rejected');
  }
}
