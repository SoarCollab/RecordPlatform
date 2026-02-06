import { sleep } from 'k6';
import {
  ensureRequiredConfig,
  getBaseConfig,
  getGlobalThresholds,
  getQueryThresholds,
  getUploadThresholds,
  mergeThresholds,
  parseBooleanEnv,
  parseIntEnv,
} from '../lib/config.js';
import { loginOrFail } from '../lib/auth.js';
import { cleanupRunFiles } from '../lib/cleanup.js';
import { createSummaryHandler } from '../lib/summary.js';
import { runChunkUploadFlow } from '../chunk-upload.js';
import { runFileQueryFlow } from '../file-query.js';
import { runCoreMixedIteration } from '../scenarios/core-mixed.js';

const baseConfig = getBaseConfig();
ensureRequiredConfig(baseConfig);
const cleanupEnabled = parseBooleanEnv('CLEANUP', true);

/**
 * 判断是否启用某个场景。
 *
 * @param {string} scenarioName 场景名
 * @returns {boolean} 是否启用
 */
function shouldEnableScenario(scenarioName) {
  return baseConfig.scenario === 'all' || baseConfig.scenario === scenarioName;
}

/**
 * 构建本地 smoke 套件计划（默认 file-query + core-mixed）。
 *
 * @returns {{scenarios:Record<string, any>, includeQueryThreshold:boolean, includeUploadThreshold:boolean}} 套件计划
 */
function buildSmokePlan() {
  const scenarios = {};

  const enableFileQuery = shouldEnableScenario('file-query');
  const enableCoreMixed = shouldEnableScenario('core-mixed');
  const enableChunkUpload = baseConfig.scenario === 'chunk-upload';

  if (enableFileQuery) {
    scenarios.fileQuerySmoke = {
      executor: 'constant-vus',
      exec: 'runFileQuerySmoke',
      vus: parseIntEnv('SMOKE_FILE_QUERY_VUS', 2, 1),
      duration: __ENV.SMOKE_FILE_QUERY_DURATION || '90s',
      startTime: '0s',
    };
  }

  if (enableCoreMixed) {
    scenarios.coreMixedSmoke = {
      executor: 'constant-vus',
      exec: 'runCoreMixedSmoke',
      vus: parseIntEnv('SMOKE_CORE_MIXED_VUS', 2, 1),
      duration: __ENV.SMOKE_CORE_MIXED_DURATION || '120s',
      startTime: enableFileQuery ? __ENV.SMOKE_CORE_MIXED_START_TIME || '95s' : '0s',
    };
  }

  if (enableChunkUpload) {
    scenarios.chunkUploadSmoke = {
      executor: 'constant-vus',
      exec: 'runChunkUploadSmoke',
      vus: parseIntEnv('SMOKE_CHUNK_UPLOAD_VUS', 1, 1),
      duration: __ENV.SMOKE_CHUNK_UPLOAD_DURATION || '90s',
      startTime: enableFileQuery || enableCoreMixed ? __ENV.SMOKE_CHUNK_START_TIME || '220s' : '0s',
    };
  }

  if (Object.keys(scenarios).length === 0) {
    throw new Error(`K6_SCENARIO=${baseConfig.scenario} 无有效场景，可选值: all|file-query|core-mixed|chunk-upload`);
  }

  return {
    scenarios,
    includeQueryThreshold: enableFileQuery || enableCoreMixed,
    includeUploadThreshold: enableChunkUpload || enableCoreMixed,
  };
}

const smokePlan = buildSmokePlan();

export const options = {
  scenarios: smokePlan.scenarios,
  thresholds: mergeThresholds(
    getGlobalThresholds(),
    smokePlan.includeQueryThreshold ? getQueryThresholds() : {},
    smokePlan.includeUploadThreshold ? getUploadThresholds() : {},
  ),
};

/**
 * 初始化压测上下文（登录并返回 token）。
 *
 * @returns {{token:string, config:{baseUrl:string, tenantId:string, username:string, password:string, runId:string, resultDir:string, profile:string, scenario:string}}} 上下文
 */
export function setup() {
  const token = loginOrFail(baseConfig, 'local_smoke_setup', 1);
  return {
    token,
    config: baseConfig,
  };
}

/**
 * 运行 file-query smoke 场景。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function runFileQuerySmoke(data) {
  runFileQueryFlow(data, 'smoke_file_query');
  sleep(1);
}

/**
 * 运行 core-mixed smoke 场景。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function runCoreMixedSmoke(data) {
  runCoreMixedIteration(data, 'smoke_core_mixed');
  sleep(1);
}

/**
 * 运行 chunk-upload smoke 场景（仅 K6_SCENARIO=chunk-upload 时启用）。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function runChunkUploadSmoke(data) {
  runChunkUploadFlow(data, 'smoke_chunk_upload');
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
    console.warn(`[k6-cleanup] local-smoke teardown 清理异常: ${error && error.message ? error.message : error}`);
  }
}

export const handleSummary = createSummaryHandler(baseConfig, 'local-smoke');
