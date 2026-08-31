import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { spawnSync } from 'node:child_process';
import vm from 'node:vm';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');

/** Load the real k6 consumer graph against an isolated in-memory HTTP boundary. */
async function loadFlow(entry, calls) {
  const context = vm.createContext({
    __ENV: { USERNAME: 'fixture', PASSWORD: 'fixture', RUN_ID: 'fixture-run',
      CHUNK_SIZE: '65536', TOTAL_CHUNKS: '2', DIRECT_CHUNK_SIZE: '65536', DIRECT_TOTAL_CHUNKS: '2' },
    __VU: 2, __ITER: 3, console, ArrayBuffer, Uint8Array,
  });
  const digest = (input) => createHash('sha256').update(typeof input === 'string' ? input : Buffer.from(input)).digest('hex');
  const response = { status: 200, json: (path) => ({ code: 200, 'data.clientId': 'fixture-client' })[path] };
  /** Capture calls without connecting to any endpoint. */
  function request(method, ...args) {
    calls.push({ method, args });
    return response;
  }
  const http = {
    file: (data, filename, contentType) => ({ data, filename, contentType }),
    get: (...args) => request('GET', ...args), post: (...args) => request('POST', ...args),
    put: (...args) => request('PUT', ...args), del: (...args) => request('DELETE', ...args),
  };
  class Metric { add() {} }
  const builtins = {
    'k6/http': { default: http }, 'k6/crypto': { sha256: digest },
    k6: { sleep: () => {}, check: (value, checks) => Object.values(checks).every((fn) => fn(value)), fail: (message) => { throw new Error(message); } },
    'k6/metrics': { Counter: Metric, Rate: Metric, Trend: Metric },
  };
  const modules = new Map();
  /** Cache modules before linking to support shared transitive dependencies. */
  function moduleFor(name) {
    if (modules.has(name)) return modules.get(name);
    let module;
    if (builtins[name]) {
      module = new vm.SyntheticModule(Object.keys(builtins[name]), function () {
        for (const [key, value] of Object.entries(builtins[name])) this.setExport(key, value);
      }, { context, identifier: name });
    } else {
      module = new vm.SourceTextModule(readFileSync(name, 'utf8'), { context, identifier: name });
    }
    modules.set(name, module);
    return module;
  }
  const module = moduleFor(resolve(root, entry));
  await module.link((name, referrer) => moduleFor(name.startsWith('.') ? resolve(dirname(referrer.identifier), name) : name));
  await module.evaluate();
  return module.namespace;
}

test('traditional consumer sends genuine exact-size text with correct extension and MIME', async () => {
  const calls = [];
  const module = await loadFlow('tools/k6/chunk-upload.js', calls);
  module.runChunkUploadFlow({ config: { runId: 'run-A', tenantId: '1', baseUrl: 'http://fixture.invalid' }, token: 'synthetic' });
  const start = calls.find((call) => call.method === 'POST').args[1];
  assert.equal(start.fileName, 'k6-run-A-2-3.txt');
  assert.equal(start.contentType, 'text/plain');
  const chunks = calls.filter((call) => call.method === 'PUT');
  assert.equal(chunks.length, 2);
  const hashes = chunks.map(({ args }) => {
    const file = args[1].file;
    assert.match(file.filename, /\.txt$/);
    assert.equal(file.contentType, 'text/plain');
    assert.equal(file.data.byteLength, 65536);
    assert.match(Buffer.from(file.data).toString('ascii'), /^[\x20-\x7e\n]+$/);
    return createHash('sha256').update(Buffer.from(file.data)).digest('hex');
  });
  assert.notEqual(hashes[0], hashes[1]);
});

test('direct create uses the exact text size and independently reconstructed SHA-256 plan', async () => {
  const calls = [];
  const module = await loadFlow('tools/k6/direct-path.js', calls);
  module.runDirectPathFlow({ config: { runId: 'run-A', tenantId: '1', baseUrl: 'http://fixture.invalid' }, token: 'synthetic' });
  const create = JSON.parse(calls.find((call) => call.method === 'POST').args[1]);
  assert.equal(create.fileName, 'k6-run-A-2-3-direct.txt');
  assert.equal(create.contentType, 'text/plain');
  assert.equal(create.fileSize, 131072);
  for (const part of create.parts) {
    const seed = createHash('sha256').update(`${create.fileName}:${part.index}`).digest('hex');
    const line = `${seed} RecordPlatform load-test text fixture.\n`;
    const expected = Buffer.from(line.repeat(Math.ceil(part.size / line.length)).slice(0, part.size));
    assert.equal(part.size, 65536);
    assert.equal(part.plainHash, `sha256:${createHash('sha256').update(expected).digest('hex')}`);
    assert.equal(part.cipherHash, part.plainHash);
  }
});

test('canonical runtime, workflow and bilingual docs share one immutable pin', () => {
  const pin = readFileSync(resolve(root, 'tools/k6/runtime.env'), 'utf8').match(/^K6_TESTED_IMAGE=(.+)$/m)[1];
  assert.match(pin, /^grafana\/k6@sha256:[a-f0-9]{64}$/);
  for (const path of ['.github/workflows/perf-smoke.yml', 'tools/k6/README.md', 'docs/en/perf/k6-loadtest.md', 'docs/zh/perf/k6-loadtest.md']) {
    assert.ok(readFileSync(resolve(root, path), 'utf8').includes(pin), path);
  }
  const workflow = readFileSync(resolve(root, '.github/workflows/test.yml'), 'utf8').split('  required-ci:')[1];
  assert.ok(workflow.includes('if: always()'));
  assert.ok(workflow.includes('bash tools/k6/check-runtime.sh docker'));
  const gate = readFileSync(resolve(root, 'tools/k6/check-runtime.sh'), 'utf8');
  assert.ok(gate.includes('--network none'));
  assert.ok(gate.includes('inspect --execution-requirements'));
  assert.ok(gate.includes('tests/fixture-runtime.js'));
});

test('both Docker wrappers mount an explicit public trust file read-only without disabling TLS', () => {
  for (const wrapper of ['run-local.sh', 'run-ci.sh']) {
    const source = readFileSync(resolve(root, `tools/k6/${wrapper}`), 'utf8').replace(/main "\$@"\s*$/, '');
    // This is an argv-only fake Docker test, not a certificate/TLS acceptance test.
    const probe = `${source}\ndocker() { printf '%s\\n' "$@"; }\nrun_with_docker_k6 tools/k6/direct-path.js\n`;
    const publicFixture = resolve(root, 'tools/k6/tests/fixture-runtime.js');
    const env = { PATH: process.env.PATH, K6_DOCKER_IMAGE: 'grafana/k6@sha256:synthetic', K6_CA_CERT_FILE: publicFixture };
    const result = spawnSync('bash', { input: probe, cwd: root, env, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.ok(result.stdout.includes(`${publicFixture}:/etc/ssl/certs/record-platform-ca.pem:ro`));
    assert.ok(result.stdout.includes('SSL_CERT_FILE=/etc/ssl/certs/record-platform-ca.pem'));
    assert.doesNotMatch(result.stdout, /insecure|skip.*[Tt][Ll][Ss]/);
    const rejected = spawnSync('bash', { input: probe, cwd: root,
      env: { ...env, K6_CA_CERT_FILE: `${publicFixture}.missing` }, encoding: 'utf8' });
    assert.notEqual(rejected.status, 0);
    assert.equal(rejected.stdout, '');
    assert.ok(rejected.stderr.includes('must be a readable public PEM certificate'));
    const defaults = spawnSync('bash', { input: probe, cwd: root,
      env: { PATH: process.env.PATH, K6_DOCKER_IMAGE: env.K6_DOCKER_IMAGE }, encoding: 'utf8' });
    assert.equal(defaults.status, 0, defaults.stderr);
    assert.ok(!defaults.stdout.includes('SSL_CERT_FILE'));
  }
});
