import { sleep } from 'k6';
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
import { runCoreMixedIteration } from '../scenarios/core-mixed.js';
import { directCleanupFailureRate } from '../lib/metrics.js';

const baseConfig = getBaseConfig();
ensureRequiredConfig(baseConfig);
const cleanupEnabled = parseBooleanEnv('CLEANUP', true);
const includeChunkInCi = parseBooleanEnv('CI_INCLUDE_CHUNK', false);
const includeDirectInCi = parseBooleanEnv('CI_INCLUDE_DIRECT', false);

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
 * 构建 CI smoke 套件计划（场景 + 阈值开关）。
 *
 * @returns {{scenarios:Record<string, any>, includeQueryThreshold:boolean, includeUploadThreshold:boolean, includeDirectThreshold:boolean}} 套件计划
 */
function buildCiPlan() {
  const scenarios = {};

  const enableFileQuery = shouldEnableScenario('file-query');
  const enableCoreMixed = shouldEnableScenario('core-mixed');
  const enableChunkUpload = includeChunkInCi && shouldEnableScenario('chunk-upload');
  const enableDirectPath = (includeDirectInCi || baseConfig.scenario === 'direct-path')
    && shouldEnableScenario('direct-path');

  if (enableFileQuery) {
    scenarios.fileQueryCiSmoke = {
      executor: 'constant-vus',
      exec: 'runFileQueryCiSmoke',
      vus: parseIntEnv('CI_FILE_QUERY_VUS', 2, 1),
      duration: __ENV.CI_FILE_QUERY_DURATION || '60s',
      startTime: '0s',
    };
  }

  if (enableCoreMixed) {
    scenarios.coreMixedCiSmoke = {
      executor: 'constant-vus',
      exec: 'runCoreMixedCiSmoke',
      vus: parseIntEnv('CI_CORE_MIXED_VUS', 2, 1),
      duration: __ENV.CI_CORE_MIXED_DURATION || '90s',
      startTime: enableFileQuery ? __ENV.CI_CORE_MIXED_START_TIME || '65s' : '0s',
    };
  }

  if (enableChunkUpload) {
    scenarios.chunkUploadCiSmoke = {
      executor: 'constant-vus',
      exec: 'runChunkUploadCiSmoke',
      vus: parseIntEnv('CI_CHUNK_UPLOAD_VUS', 1, 1),
      duration: __ENV.CI_CHUNK_UPLOAD_DURATION || '60s',
      startTime: enableFileQuery || enableCoreMixed ? __ENV.CI_CHUNK_START_TIME || '160s' : '0s',
    };
  }

  if (enableDirectPath) {
    scenarios.directPathCiSmoke = {
      executor: 'constant-vus',
      exec: 'runDirectPathCiSmoke',
      vus: parseIntEnv('CI_DIRECT_PATH_VUS', 1, 1),
      duration: __ENV.CI_DIRECT_PATH_DURATION || '60s',
      startTime: Object.keys(scenarios).length > 0 ? __ENV.CI_DIRECT_PATH_START_TIME || '230s' : '0s',
    };
  }

  if (Object.keys(scenarios).length === 0) {
    throw new Error(`K6_SCENARIO=${baseConfig.scenario} 无有效场景，可选值: all|file-query|core-mixed|chunk-upload|direct-path`);
  }

  return {
    scenarios,
    includeQueryThreshold: enableFileQuery || enableCoreMixed,
    includeUploadThreshold: enableChunkUpload || enableCoreMixed,
    includeDirectThreshold: enableDirectPath,
  };
}

const ciPlan = buildCiPlan();

export const options = {
  scenarios: ciPlan.scenarios,
  summaryTrendStats: getSummaryTrendStats(),
  systemTags: getSafeSystemTags(),
  thresholds: mergeThresholds(
    getGlobalThresholds(),
    ciPlan.includeQueryThreshold ? getQueryThresholds() : {},
    ciPlan.includeUploadThreshold ? getUploadThresholds() : {},
    ciPlan.includeDirectThreshold ? getDirectPathThresholds() : {},
  ),
};

/**
 * 初始化压测上下文（登录并返回 token）。
 *
 * @returns {{token:string, config:{baseUrl:string, tenantId:string, username:string, password:string, runId:string, resultDir:string, profile:string, scenario:string}}} 上下文
 */
export function setup() {
  const token = loginOrFail(baseConfig, 'ci_smoke_setup', 1);
  const context = {
    token,
    config: baseConfig,
  };
  if (ciPlan.includeDirectThreshold) {
    captureDirectSnapshotAvailability(context, 'ci_direct_path', 'start');
  }
  return context;
}

/**
 * 运行 file-query CI smoke 场景。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function runFileQueryCiSmoke(data) {
  runFileQueryFlow(data, 'ci_file_query');
  sleep(1);
}

/**
 * 运行 core-mixed CI smoke 场景。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function runCoreMixedCiSmoke(data) {
  runCoreMixedIteration(data, 'ci_core_mixed');
  sleep(1);
}

/**
 * 运行 chunk-upload CI smoke 场景（默认关闭）。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function runChunkUploadCiSmoke(data) {
  runChunkUploadFlow(data, 'ci_chunk_upload');
  sleep(1);
}

/**
 * 运行 direct-path CI smoke 场景。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function runDirectPathCiSmoke(data) {
  runDirectPathFlow(data, 'ci_direct_path');
  sleep(1);
}

/**
 * 执行收尾清理，失败仅告警不抛错。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} data setup 返回上下文
 */
export function teardown(data) {
  try {
    if (!cleanupEnabled) {
      if (ciPlan.includeDirectThreshold) {
        directCleanupFailureRate.add(true, { reason: 'disabled' });
        throw new Error('direct-path CI smoke 要求启用 CLEANUP');
      }
      return;
    }

    try {
      const result = cleanupRunFiles(data);
      if (ciPlan.includeDirectThreshold) {
        directCleanupFailureRate.add(!result.ok, { reason: result.reason || 'none' });
        if (!result.ok) {
          throw new Error(`direct-path CI smoke 清理失败: ${result.reason}`);
        }
      }
    } catch (error) {
      if (ciPlan.includeDirectThreshold) {
        throw error;
      }
      console.warn(`[k6-cleanup] ci-smoke teardown 清理异常: ${error && error.message ? error.message : error}`);
    }
  } finally {
    if (ciPlan.includeDirectThreshold) {
      captureDirectSnapshotAvailability(data, 'ci_direct_path', 'end');
    }
  }
}

export const handleSummary = createSummaryHandler(baseConfig, 'ci-smoke');
