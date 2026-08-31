import http from 'k6/http';
import { check, sleep } from 'k6';
import { sha256 } from 'k6/crypto';
import { buildTextFixture, FIXTURE_CONTENT_TYPE } from './lib/fixture.js';
import {
  buildRequestTags,
  createConstantVusOptions,
  ensureRequiredConfig,
  getBaseConfig,
  getDirectPathConfig,
  getDirectPathThresholds,
  getGlobalThresholds,
  mergeThresholds,
  parseBooleanEnv,
  parseIntEnv,
} from './lib/config.js';
import { loginOrFail } from './lib/auth.js';
import { checkApiSuccess, checkFieldPresent } from './lib/assertions.js';
import { cleanupRunFiles } from './lib/cleanup.js';
import {
  directCleanupFailureRate,
  directFlowFailureRate,
  recordDirectPathResult,
  recordDirectSnapshotAvailability,
  recordEndpointResult,
  recordHttpStatus,
} from './lib/metrics.js';
import { buildAuthHeaders, del, get, post, safeJsonPath } from './lib/http.js';
import { createSummaryHandler } from './lib/summary.js';

const baseConfig = getBaseConfig();
ensureRequiredConfig(baseConfig);
const directConfig = getDirectPathConfig();
const cleanupEnabled = parseBooleanEnv('CLEANUP', true);
const visibleEtagPattern = /^[\x21-\x7e]{1,255}$/;
const standaloneSummaryConfig = {
  ...baseConfig,
  directExecution: {
    executor: 'constant-vus',
    concurrency: parseIntEnv('VUS', 1, 1),
    duration: __ENV.DURATION || '30s',
    preAllocatedVUs: null,
    maxVUs: null,
  },
};

export const options = createConstantVusOptions(
  1,
  '30s',
  mergeThresholds(getGlobalThresholds(), getDirectPathThresholds()),
);

/**
 * 生成符合后端格式约束且长度有界的 clientId。
 *
 * @param {string} runId 运行 ID
 * @returns {string} 客户端会话 ID
 */
function buildClientId(runId) {
  const safeRunId = String(runId).replace(/[^A-Za-z0-9-]/g, '-').slice(0, 40);
  return `direct-${safeRunId}-${__VU}-${__ITER}`.slice(0, 64);
}

/**
 * 为一次迭代生成直传分片及可信哈希计划。
 *
 * @returns {{index:number, bytes:ArrayBuffer, size:number, hash:string}[]} 分片计划
 */
function buildPartPlan(fileName) {
  const parts = [];
  for (let index = 0; index < directConfig.totalChunks; index += 1) {
    const bytes = buildTextFixture(directConfig.chunkSize, `${fileName}:${index}`);
    parts.push({
      index,
      bytes,
      size: bytes.byteLength,
      hash: `sha256:${sha256(bytes, 'hex')}`,
    });
  }
  return parts;
}

/**
 * 记录对象存储原始请求结果；预签名请求不得携带平台鉴权头。
 *
 * @param {import('k6/http').RefinedResponse<'text'|'binary'>} response HTTP 响应
 * @param {Record<string,string>} tags 指标标签
 * @param {boolean} success 是否成功
 */
function recordRawStorageResult(response, tags, success) {
  recordEndpointResult(success, tags);
  recordHttpStatus(response?.status, tags);
  directFlowFailureRate.add(!success, Object.assign({}, tags, { phase: tags.endpoint }));
}

/**
 * 中止失败会话并验证后端已接受清理请求。
 *
 * @param {{config:{baseUrl:string,tenantId:string},token:string}} context 压测上下文
 * @param {string|undefined} clientId 客户端会话 ID
 * @param {string} scenarioName 场景名
 * @returns {boolean} 中止是否成功
 */
function abortDirectSession(context, clientId, scenarioName) {
  if (!clientId) {
    return true;
  }
  const tags = buildRequestTags(scenarioName, 'direct_abort', 'DELETE');
  const response = del(`${context.config.baseUrl}/upload-sessions/${clientId}/direct`, {
    headers: buildAuthHeaders(context.config.tenantId, context.token),
    tags,
  });
  return checkApiSuccess(response, 'direct/abort', tags);
}

/**
 * 记录失败迭代的有界指标并执行会话回收。
 *
 * @param {{config:{baseUrl:string,tenantId:string},token:string}} context 压测上下文
 * @param {string} scenarioName 场景名
 * @param {number} startedAt 开始时间
 * @param {number} uploadStartedAt 上传开始时间
 * @param {number} uploadedBytes 已上传字节数
 * @param {number} downloadedBytes 已下载字节数
 * @param {string|undefined} clientId 客户端会话 ID
 * @returns {{ok:false,clientId:string|undefined}} 失败结果
 */
function failIteration(
  context,
  scenarioName,
  startedAt,
  uploadStartedAt,
  uploadedBytes,
  downloadedBytes,
  clientId,
) {
  if (clientId) {
    const abortOk = abortDirectSession(context, clientId, scenarioName);
    directCleanupFailureRate.add(!abortOk, { reason: abortOk ? 'none' : 'abort_failed' });
  }
  const now = Date.now();
  recordDirectPathResult(
    {
      uploadMs: Math.max(0, now - uploadStartedAt),
      downloadMs: 0,
      totalMs: Math.max(0, now - startedAt),
      uploadedBytes,
      downloadedBytes,
      ok: false,
    },
    buildRequestTags(scenarioName, 'direct_path', 'CUSTOM'),
  );
  return { ok: false, clientId };
}

/**
 * 调用可选的同源观测快照端点，只记录可用性而不落盘敏感响应。
 *
 * @param {{config:{baseUrl:string,tenantId:string},token:string}} context 压测上下文
 * @param {string} relativePath 同源相对路径
 * @param {string} endpoint 指标端点名
 * @param {string} scenarioName 场景名
 * @returns {boolean} 快照是否可用
 */
function probeOptionalSnapshot(context, relativePath, endpoint, scenarioName) {
  if (!relativePath || !relativePath.startsWith('/') || relativePath.startsWith('//')) {
    return false;
  }
  const tags = buildRequestTags(scenarioName, endpoint, 'GET');
  const response = get(`${context.config.baseUrl}${relativePath}`, {
    headers: buildAuthHeaders(context.config.tenantId, context.token),
    tags,
  });
  return response.status === 200 && safeJsonPath(response, 'code') === 200;
}

/**
 * 在运行起点或清理后终点采集可选观测端点的可用性证据。
 *
 * @param {{config:{baseUrl:string,tenantId:string,resourceSnapshotPath:string,lifecycleSnapshotPath:string},token:string}} context 压测上下文
 * @param {string} scenarioName 场景名
 * @param {'start'|'end'} stage 快照阶段
 * @returns {{resourceAvailable:boolean,lifecycleAvailable:boolean}} 快照可用性
 */
export function captureDirectSnapshotAvailability(context, scenarioName, stage) {
  const resourceAvailable = probeOptionalSnapshot(
    context,
    context.config.resourceSnapshotPath,
    `direct_resource_snapshot_${stage}`,
    scenarioName,
  );
  const lifecycleAvailable = probeOptionalSnapshot(
    context,
    context.config.lifecycleSnapshotPath,
    `direct_lifecycle_snapshot_${stage}`,
    scenarioName,
  );
  recordDirectSnapshotAvailability(
    resourceAvailable,
    lifecycleAvailable,
    stage,
    buildRequestTags(scenarioName, `direct_snapshot_${stage}`, 'CUSTOM'),
  );
  return { resourceAvailable, lifecycleAvailable };
}

/**
 * 执行 create -> raw PUT -> complete -> metadata -> raw GET 的直传闭环。
 *
 * @param {{config:{baseUrl:string,tenantId:string,runId:string,resourceSnapshotPath:string,lifecycleSnapshotPath:string},token:string}} context 压测上下文
 * @param {string} scenarioName 场景名
 * @returns {{ok:boolean,clientId:string|undefined,fileHash?:string}} 流程结果
 */
export function runDirectPathFlow(context, scenarioName = 'direct-path') {
  const startedAt = Date.now();
  const uploadStartedAt = Date.now();
  const headers = buildAuthHeaders(context.config.tenantId, context.token, {
    'Content-Type': 'application/json',
  });
  const clientId = buildClientId(context.config.runId);
  const fileName = `k6-${context.config.runId}-${__VU}-${__ITER}-direct.txt`;
  const partPlan = buildPartPlan(fileName);
  const fileSize = partPlan.reduce((total, part) => total + part.size, 0);
  let uploadedBytes = 0;
  let downloadedBytes = 0;

  const createTags = buildRequestTags(scenarioName, 'direct_create', 'POST');
  const createResponse = post(
    `${context.config.baseUrl}/upload-sessions/direct`,
    JSON.stringify({
      clientId,
      fileName,
      fileSize,
      contentType: FIXTURE_CONTENT_TYPE,
      chunkSize: directConfig.chunkSize,
      totalChunks: partPlan.length,
      parts: partPlan.map((part) => ({
        index: part.index,
        size: part.size,
        plainHash: part.hash,
        cipherHash: part.hash,
        checksumAlgorithm: 'SHA-256',
      })),
    }),
    { headers, tags: createTags },
  );
  const createOk = checkApiSuccess(createResponse, 'direct/create', createTags);
  const issuedClientId = checkFieldPresent(
    createResponse,
    'data.clientId',
    'direct/create clientId present',
    createTags,
  );
  const issuedParts = safeJsonPath(createResponse, 'data.parts');
  const issuedPartsOk = check(createResponse, {
    'direct/create preserves clientId and trusted part plan': () =>
      String(issuedClientId || '') === clientId &&
      Array.isArray(issuedParts) &&
      issuedParts.length === partPlan.length &&
      issuedParts.every((part, index) =>
        Number(part.index) === index &&
        Number(part.size) === partPlan[index].size &&
        String(part.plainHash) === partPlan[index].hash &&
        String(part.cipherHash) === partPlan[index].hash &&
        typeof part.uploadUrl === 'string' &&
        part.uploadUrl.length > 0),
  });
  if (!createOk || !issuedClientId || !issuedPartsOk) {
    return failIteration(
      context,
      scenarioName,
      startedAt,
      uploadStartedAt,
      uploadedBytes,
      downloadedBytes,
      issuedClientId || undefined,
    );
  }

  const completedParts = [];
  for (const plannedPart of partPlan) {
    const issuedPart = issuedParts.find((candidate) => Number(candidate.index) === plannedPart.index);
    if (!issuedPart || !issuedPart.uploadUrl) {
      return failIteration(
        context,
        scenarioName,
        startedAt,
        uploadStartedAt,
        uploadedBytes,
        downloadedBytes,
        String(issuedClientId),
      );
    }

    const putTags = buildRequestTags(scenarioName, 'direct_presigned_put', 'PUT');
    const putResponse = http.put(issuedPart.uploadUrl, plannedPart.bytes, { tags: putTags });
    const eTag = putResponse.headers.ETag || putResponse.headers.Etag || putResponse.headers.etag;
    const putOk = check(putResponse, {
      'direct/presigned PUT http 2xx': (response) => response.status >= 200 && response.status < 300,
      'direct/presigned PUT exposes valid ETag': () => visibleEtagPattern.test(String(eTag || '')),
    });
    recordRawStorageResult(putResponse, putTags, putOk);
    if (!putOk) {
      return failIteration(
        context,
        scenarioName,
        startedAt,
        uploadStartedAt,
        uploadedBytes,
        downloadedBytes,
        String(issuedClientId),
      );
    }
    uploadedBytes += plannedPart.size;
    completedParts.push({ index: plannedPart.index, eTag: String(eTag) });
  }

  const completeTags = buildRequestTags(scenarioName, 'direct_complete', 'POST');
  const completeResponse = post(
    `${context.config.baseUrl}/upload-sessions/${issuedClientId}/direct/complete`,
    JSON.stringify({ parts: completedParts }),
    { headers, tags: completeTags },
  );
  const completeOk = checkApiSuccess(completeResponse, 'direct/complete', completeTags);
  const fileHash = checkFieldPresent(
    completeResponse,
    'data.fileHash',
    'direct/complete fileHash present',
    completeTags,
  );
  const completedManifestHash = safeJsonPath(completeResponse, 'data.manifestHash');
  const completeEvidenceOk = check(completeResponse, {
    'direct/complete returns file and manifest evidence': () =>
      Boolean(safeJsonPath(completeResponse, 'data.fileId')) &&
      typeof completedManifestHash === 'string' &&
      completedManifestHash.startsWith('sha256:'),
  });
  if (!completeOk || !fileHash || !completeEvidenceOk) {
    return failIteration(
      context,
      scenarioName,
      startedAt,
      uploadStartedAt,
      uploadedBytes,
      downloadedBytes,
      String(issuedClientId),
    );
  }
  const uploadCompletedAt = Date.now();

  const metadataTags = buildRequestTags(scenarioName, 'direct_download_metadata', 'GET');
  const metadataResponse = get(
    `${context.config.baseUrl}/files/hash/${encodeURIComponent(String(fileHash))}/download-metadata`,
    { headers: buildAuthHeaders(context.config.tenantId, context.token), tags: metadataTags },
  );
  const metadataOk = checkApiSuccess(metadataResponse, 'direct/download-metadata', metadataTags);
  const downloadParts = safeJsonPath(metadataResponse, 'data.parts');
  const metadataPartsOk = check(metadataResponse, {
    'direct/download-metadata binds manifest and ordered parts': () =>
      safeJsonPath(metadataResponse, 'data.manifestHash') === completedManifestHash &&
      Number(safeJsonPath(metadataResponse, 'data.fileSize')) === fileSize &&
      Number(safeJsonPath(metadataResponse, 'data.totalChunks')) === partPlan.length &&
      Array.isArray(downloadParts) &&
      downloadParts.length === partPlan.length &&
      downloadParts.every((part, index) =>
        Number(part.index) === index &&
        Number(part.size) === partPlan[index].size &&
        typeof part.downloadUrl === 'string' &&
        part.downloadUrl.length > 0 &&
        String(part.cipherHash) === partPlan[index].hash),
  });
  if (!metadataOk || !metadataPartsOk) {
    return failIteration(
      context,
      scenarioName,
      startedAt,
      uploadStartedAt,
      uploadedBytes,
      downloadedBytes,
      undefined,
    );
  }

  const downloadStartedAt = Date.now();
  for (const downloadPart of downloadParts) {
    const plannedPart = partPlan[Number(downloadPart.index)];
    const getTags = buildRequestTags(scenarioName, 'direct_presigned_get', 'GET');
    const getResponse = http.get(downloadPart.downloadUrl, {
      tags: getTags,
      responseType: 'binary',
    });
    const bodyLength = getResponse.body && getResponse.body.byteLength !== undefined
      ? getResponse.body.byteLength
      : -1;
    const actualHash = getResponse.status === 200 && bodyLength >= 0
      ? `sha256:${sha256(getResponse.body, 'hex')}`
      : '';
    const getOk = check(getResponse, {
      'direct/presigned GET http 200': (response) => response.status === 200,
      'direct/presigned GET size matches manifest': () =>
        bodyLength === Number(downloadPart.size) && bodyLength === plannedPart.size,
      'direct/presigned GET hash matches manifest and source': () =>
        actualHash === String(downloadPart.cipherHash) && actualHash === plannedPart.hash,
    });
    recordRawStorageResult(getResponse, getTags, getOk);
    if (!getOk) {
      return failIteration(
        context,
        scenarioName,
        startedAt,
        uploadStartedAt,
        uploadedBytes,
        downloadedBytes,
        undefined,
      );
    }
    downloadedBytes += bodyLength;
  }

  const completedAt = Date.now();
  recordDirectPathResult(
    {
      uploadMs: uploadCompletedAt - uploadStartedAt,
      downloadMs: completedAt - downloadStartedAt,
      totalMs: completedAt - startedAt,
      uploadedBytes,
      downloadedBytes,
      ok: true,
    },
    buildRequestTags(scenarioName, 'direct_path', 'CUSTOM'),
  );
  return { ok: true, clientId: String(issuedClientId), fileHash: String(fileHash) };
}

/**
 * 初始化直传压测上下文。
 *
 * @returns {{token:string,config:Record<string,string>}} 压测上下文
 */
export function setup() {
  const token = loginOrFail(baseConfig, 'direct_path_setup', 1);
  const context = { token, config: baseConfig };
  captureDirectSnapshotAvailability(context, 'direct-path', 'start');
  return context;
}

/**
 * 运行一次直传上传/下载闭环。
 *
 * @param {{token:string,config:Record<string,string>}} data setup 返回上下文
 */
export default function (data) {
  runDirectPathFlow(data, 'direct-path');
  sleep(1);
}

/**
 * 强制清理本 run 文件；失败会计入阈值并终止运行。
 *
 * @param {{token:string,config:Record<string,string>}} data setup 返回上下文
 */
export function teardown(data) {
  try {
    if (!cleanupEnabled) {
      directCleanupFailureRate.add(true, { reason: 'disabled' });
      throw new Error('direct-path 要求启用 CLEANUP 以证明无残留');
    }

    const cleanupResult = cleanupRunFiles(data);
    directCleanupFailureRate.add(!cleanupResult.ok, {
      reason: cleanupResult.reason || 'none',
    });
    if (!cleanupResult.ok) {
      throw new Error(`direct-path 清理失败: ${cleanupResult.reason}`);
    }
  } finally {
    captureDirectSnapshotAvailability(data, 'direct-path', 'end');
  }
}

export const handleSummary = createSummaryHandler(standaloneSummaryConfig, 'direct-path');
