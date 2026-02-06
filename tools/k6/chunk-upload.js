import http from 'k6/http';
import { sleep } from 'k6';
import { randomBytes } from 'k6/crypto';
import {
  createConstantVusOptions,
  ensureRequiredConfig,
  getBaseConfig,
  getGlobalThresholds,
  getUploadConfig,
  getUploadThresholds,
  mergeThresholds,
  parseBooleanEnv,
  buildRequestTags,
} from './lib/config.js';
import { loginOrFail } from './lib/auth.js';
import { checkApiSuccess, checkFieldPresent } from './lib/assertions.js';
import { cleanupRunFiles } from './lib/cleanup.js';
import { recordUploadE2e, recordUploadFile } from './lib/metrics.js';
import { buildAuthHeaders, get, post, postForm } from './lib/http.js';
import { createSummaryHandler } from './lib/summary.js';

const baseConfig = getBaseConfig();
ensureRequiredConfig(baseConfig);
const uploadConfig = getUploadConfig();
const cleanupEnabled = parseBooleanEnv('CLEANUP', true);

export const options = createConstantVusOptions(
  2,
  '30s',
  mergeThresholds(getGlobalThresholds(), getUploadThresholds()),
);

/**
 * 执行一次完整分片上传流程（start/chunk/complete/progress）。
 *
 * @param {{config:{baseUrl:string, tenantId:string, runId:string}, token:string}} context 压测上下文
 * @param {string} scenarioName 场景名
 * @returns {{ok:boolean, clientId:string|undefined, fileName:string}} 流程结果
 */
export function runChunkUploadFlow(context, scenarioName = 'chunk-upload') {
  const headers = buildAuthHeaders(context.config.tenantId, context.token);
  const fileName = `k6-${context.config.runId}-${__VU}-${__ITER}.bin`;
  const fileSize = uploadConfig.totalChunks * uploadConfig.chunkSize;
  const startedAt = Date.now();

  const startTags = buildRequestTags(scenarioName, 'upload_start', 'POST');
  const startRes = postForm(
    `${context.config.baseUrl}/files/upload/start`,
    {
      fileName,
      fileSize: String(fileSize),
      contentType: 'application/octet-stream',
      chunkSize: String(uploadConfig.chunkSize),
      totalChunks: String(uploadConfig.totalChunks),
    },
    {
      headers,
      tags: startTags,
    },
  );

  const startOk = checkApiSuccess(startRes, 'upload/start', startTags);
  const clientId = checkFieldPresent(startRes, 'data.clientId', 'upload/start clientId present', startTags);
  if (!startOk || !clientId) {
    return {
      ok: false,
      clientId,
      fileName,
    };
  }

  let chunksOk = true;
  for (let chunkIndex = 0; chunkIndex < uploadConfig.totalChunks; chunkIndex += 1) {
    const chunkTags = buildRequestTags(scenarioName, 'upload_chunk', 'POST');
    const bytes = randomBytes(uploadConfig.chunkSize);
    const payload = {
      file: http.file(bytes, `chunk-${chunkIndex}.bin`, 'application/octet-stream'),
      clientId: String(clientId),
      chunkNumber: String(chunkIndex),
    };

    const chunkRes = post(`${context.config.baseUrl}/files/upload/chunk`, payload, {
      headers,
      tags: chunkTags,
    });

    const chunkOk = checkApiSuccess(chunkRes, 'upload/chunk', chunkTags);
    chunksOk = chunksOk && chunkOk;
  }

  const completeTags = buildRequestTags(scenarioName, 'upload_complete', 'POST');
  const completeRes = postForm(
    `${context.config.baseUrl}/files/upload/complete`,
    { clientId: String(clientId) },
    {
      headers,
      tags: completeTags,
    },
  );
  const completeOk = checkApiSuccess(completeRes, 'upload/complete', completeTags);

  const progressTags = buildRequestTags(scenarioName, 'upload_progress', 'GET');
  const progressRes = get(
    `${context.config.baseUrl}/files/upload/progress?clientId=${encodeURIComponent(clientId)}`,
    {
      headers,
      tags: progressTags,
    },
  );
  const progressOk = checkApiSuccess(progressRes, 'upload/progress', progressTags);

  const flowOk = startOk && chunksOk && completeOk && progressOk;
  if (flowOk) {
    const customTags = buildRequestTags(scenarioName, 'upload_e2e', 'CUSTOM');
    recordUploadE2e(Date.now() - startedAt, customTags);
    recordUploadFile(customTags);
  }

  return {
    ok: flowOk,
    clientId: String(clientId),
    fileName,
  };
}

/**
 * 初始化压测上下文（登录并返回 token）。
 *
 * @returns {{token:string, config:{baseUrl:string, tenantId:string, username:string, password:string, runId:string, resultDir:string, profile:string, scenario:string}}} 上下文
 */
export function setup() {
  const token = loginOrFail(baseConfig, 'chunk_upload_setup', 1);
  return {
    token,
    config: baseConfig,
  };
}

/**
 * 默认执行函数（k6 入口）。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export default function (data) {
  runChunkUploadFlow(data, 'chunk-upload');
  sleep(1);
}

/**
 * 执行收尾清理，失败仅告警不抛错。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function teardown(data) {
  if (!cleanupEnabled) {
    return;
  }

  try {
    cleanupRunFiles(data);
  } catch (error) {
    console.warn(`[k6-cleanup] chunk-upload teardown 清理异常: ${error && error.message ? error.message : error}`);
  }
}

export const handleSummary = createSummaryHandler(baseConfig, 'chunk-upload');
