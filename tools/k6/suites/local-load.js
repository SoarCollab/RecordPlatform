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
 * 构建本地 load 套件计划（场景 + 阈值开关）。
 *
 * @returns {{scenarios:Record<string, any>, includeQueryThreshold:boolean, includeUploadThreshold:boolean}} 套件计划
 */
function buildLoadPlan() {
  const scenarios = {};

  const enableQuery = shouldEnableScenario('file-query');
  const enableUpload = shouldEnableScenario('chunk-upload');

  if (enableQuery) {
    scenarios.fileQueryLoad = {
      executor: 'constant-arrival-rate',
      exec: 'runFileQueryLoad',
      rate: parseIntEnv('QUERY_RATE', 10, 1),
      timeUnit: '1s',
      duration: __ENV.QUERY_DURATION || '3m',
      preAllocatedVUs: parseIntEnv('QUERY_PRE_ALLOCATED_VUS', 20, 1),
      maxVUs: parseIntEnv('QUERY_MAX_VUS', 50, 1),
      startTime: '0s',
    };
  }

  if (enableUpload) {
    scenarios.chunkUploadLoad = {
      executor: 'constant-arrival-rate',
      exec: 'runChunkUploadLoad',
      rate: parseIntEnv('UPLOAD_RATE', 2, 1),
      timeUnit: '1s',
      duration: __ENV.UPLOAD_DURATION || '3m',
      preAllocatedVUs: parseIntEnv('UPLOAD_PRE_ALLOCATED_VUS', 5, 1),
      maxVUs: parseIntEnv('UPLOAD_MAX_VUS', 20, 1),
      startTime: enableQuery ? __ENV.LOAD_CHUNK_START_TIME || '3m10s' : '0s',
    };
  }

  if (Object.keys(scenarios).length === 0) {
    throw new Error(`K6_SCENARIO=${baseConfig.scenario} 无有效场景，可选值: all|file-query|chunk-upload`);
  }

  return {
    scenarios,
    includeQueryThreshold: enableQuery,
    includeUploadThreshold: enableUpload,
  };
}

const loadPlan = buildLoadPlan();

export const options = {
  scenarios: loadPlan.scenarios,
  thresholds: mergeThresholds(
    getGlobalThresholds(),
    loadPlan.includeQueryThreshold ? getQueryThresholds() : {},
    loadPlan.includeUploadThreshold ? getUploadThresholds() : {},
  ),
};

/**
 * 初始化压测上下文（登录并返回 token）。
 *
 * @returns {{token:string, config:{baseUrl:string, tenantId:string, username:string, password:string, runId:string, resultDir:string, profile:string, scenario:string}}} 上下文
 */
export function setup() {
  const token = loginOrFail(baseConfig, 'local_load_setup', 1);
  return {
    token,
    config: baseConfig,
  };
}

/**
 * 运行 file-query load 场景。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function runFileQueryLoad(data) {
  runFileQueryFlow(data, 'load_file_query');
}

/**
 * 运行 chunk-upload load 场景。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function runChunkUploadLoad(data) {
  runChunkUploadFlow(data, 'load_chunk_upload');
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
    console.warn(`[k6-cleanup] local-load teardown 清理异常: ${error && error.message ? error.message : error}`);
  }
}

export const handleSummary = createSummaryHandler(baseConfig, 'local-load');
