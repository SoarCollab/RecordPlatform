import {
  ensureRequiredConfig,
  getBaseConfig,
  getDirectPathThresholds,
  getGlobalThresholds,
  getQueryThresholds,
  getSafeSystemTags,
  getSummaryTrendStats,
  getUploadThresholds,
  mergeThresholds,
  parseBooleanEnv,
  parseIntEnv,
} from '../lib/config.js';
import { loginOrFail } from '../lib/auth.js';
import { cleanupRunFiles } from '../lib/cleanup.js';
import { createSummaryHandler } from '../lib/summary.js';
import { runChunkUploadFlow } from '../chunk-upload.js';
import { captureDirectSnapshotAvailability, runDirectPathFlow } from '../direct-path.js';
import { runFileQueryFlow } from '../file-query.js';
import { directCleanupFailureRate } from '../lib/metrics.js';

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
 * @returns {{scenarios:Record<string, any>, includeQueryThreshold:boolean, includeUploadThreshold:boolean, includeDirectThreshold:boolean}} 套件计划
 */
function buildLoadPlan() {
  const scenarios = {};

  const enableQuery = shouldEnableScenario('file-query');
  const enableUpload = shouldEnableScenario('chunk-upload');
  const enableDirectPath = shouldEnableScenario('direct-path');

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

  if (enableDirectPath) {
    scenarios.directPathLoad = {
      executor: 'constant-arrival-rate',
      exec: 'runDirectPathLoad',
      rate: parseIntEnv('DIRECT_PATH_RATE', 1, 1),
      timeUnit: '1s',
      duration: __ENV.DIRECT_PATH_DURATION || '3m',
      preAllocatedVUs: parseIntEnv('DIRECT_PATH_PRE_ALLOCATED_VUS', 5, 1),
      maxVUs: parseIntEnv('DIRECT_PATH_MAX_VUS', 20, 1),
      startTime: enableQuery || enableUpload ? __ENV.LOAD_DIRECT_START_TIME || '6m20s' : '0s',
    };
  }

  if (Object.keys(scenarios).length === 0) {
    throw new Error(`K6_SCENARIO=${baseConfig.scenario} 无有效场景，可选值: all|file-query|chunk-upload|direct-path`);
  }

  return {
    scenarios,
    includeQueryThreshold: enableQuery,
    includeUploadThreshold: enableUpload,
    includeDirectThreshold: enableDirectPath,
  };
}

const loadPlan = buildLoadPlan();

export const options = {
  scenarios: loadPlan.scenarios,
  summaryTrendStats: getSummaryTrendStats(),
  systemTags: getSafeSystemTags(),
  thresholds: mergeThresholds(
    getGlobalThresholds(),
    loadPlan.includeQueryThreshold ? getQueryThresholds() : {},
    loadPlan.includeUploadThreshold ? getUploadThresholds() : {},
    loadPlan.includeDirectThreshold ? getDirectPathThresholds() : {},
  ),
};

/**
 * 初始化压测上下文（登录并返回 token）。
 *
 * @returns {{token:string, config:{baseUrl:string, tenantId:string, username:string, password:string, runId:string, resultDir:string, profile:string, scenario:string}}} 上下文
 */
export function setup() {
  const token = loginOrFail(baseConfig, 'local_load_setup', 1);
  const context = {
    token,
    config: baseConfig,
  };
  if (loadPlan.includeDirectThreshold) {
    captureDirectSnapshotAvailability(context, 'load_direct_path', 'start');
  }
  return context;
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
 * 运行 direct-path load 场景。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function runDirectPathLoad(data) {
  runDirectPathFlow(data, 'load_direct_path');
}

/**
 * 执行收尾清理，失败仅告警不抛错。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function teardown(data) {
  try {
    if (!cleanupEnabled) {
      if (loadPlan.includeDirectThreshold) {
        directCleanupFailureRate.add(true, { reason: 'disabled' });
        throw new Error('direct-path load 要求启用 CLEANUP');
      }
      return;
    }

    try {
      const result = cleanupRunFiles(data);
      if (loadPlan.includeDirectThreshold) {
        directCleanupFailureRate.add(!result.ok, { reason: result.reason || 'none' });
        if (!result.ok) {
          throw new Error(`direct-path load 清理失败: ${result.reason}`);
        }
      }
    } catch (error) {
      if (loadPlan.includeDirectThreshold) {
        throw error;
      }
      console.warn(`[k6-cleanup] local-load teardown 清理异常: ${error && error.message ? error.message : error}`);
    }
  } finally {
    if (loadPlan.includeDirectThreshold) {
      captureDirectSnapshotAvailability(data, 'load_direct_path', 'end');
    }
  }
}

export const handleSummary = createSummaryHandler(baseConfig, 'local-load');
