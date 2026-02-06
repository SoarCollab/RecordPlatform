import { sleep } from 'k6';
import {
  createConstantVusOptions,
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
const queryWeight = Math.min(100, Math.max(0, parseIntEnv('MIX_QUERY_WEIGHT', 70, 0)));

export const options = createConstantVusOptions(
  4,
  '2m',
  mergeThresholds(getGlobalThresholds(), getQueryThresholds(), getUploadThresholds()),
);

/**
 * 按权重选择执行查询或上传流程。
 *
 * @returns {boolean} true 表示执行查询流程
 */
function shouldRunQueryFlow() {
  return Math.random() * 100 < queryWeight;
}

/**
 * 执行核心混合场景（70% 查询 + 30% 上传）。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string, runId:string}}} context 压测上下文
 * @param {string} scenarioName 场景名
 */
export function runCoreMixedIteration(context, scenarioName = 'core-mixed') {
  if (shouldRunQueryFlow()) {
    runFileQueryFlow(context, `${scenarioName}_query`);
    return;
  }

  runChunkUploadFlow(context, `${scenarioName}_upload`);
}

/**
 * 初始化压测上下文（登录并返回 token）。
 *
 * @returns {{token:string, config:{baseUrl:string, tenantId:string, username:string, password:string, runId:string, resultDir:string, profile:string, scenario:string}}} 上下文
 */
export function setup() {
  const token = loginOrFail(baseConfig, 'core_mixed_setup', 1);
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
  runCoreMixedIteration(data, 'core-mixed');
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
    console.warn(`[k6-cleanup] core-mixed teardown 清理异常: ${error && error.message ? error.message : error}`);
  }
}

export const handleSummary = createSummaryHandler(baseConfig, 'core-mixed');
