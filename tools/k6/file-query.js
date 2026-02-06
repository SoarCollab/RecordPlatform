import { sleep } from 'k6';
import {
  createConstantVusOptions,
  ensureRequiredConfig,
  getBaseConfig,
  getGlobalThresholds,
  getQueryThresholds,
  mergeThresholds,
  parseBooleanEnv,
  buildRequestTags,
} from './lib/config.js';
import { loginOrFail } from './lib/auth.js';
import { checkApiSuccess } from './lib/assertions.js';
import { cleanupRunFiles } from './lib/cleanup.js';
import { buildAuthHeaders, get } from './lib/http.js';
import { createSummaryHandler } from './lib/summary.js';

const baseConfig = getBaseConfig();
ensureRequiredConfig(baseConfig);
const cleanupEnabled = parseBooleanEnv('CLEANUP', true);

export const options = createConstantVusOptions(
  10,
  '30s',
  mergeThresholds(getGlobalThresholds(), getQueryThresholds()),
);

/**
 * 执行文件查询三连请求（page/list/stats）。
 *
 * @param {{config:{baseUrl:string, tenantId:string}, token:string}} context 压测上下文
 * @param {string} scenarioName 场景名
 * @returns {boolean} 三个请求是否全部成功
 */
export function runFileQueryFlow(context, scenarioName = 'file-query') {
  const headers = buildAuthHeaders(context.config.tenantId, context.token);

  const pageTags = buildRequestTags(scenarioName, 'files_page', 'GET');
  const pageRes = get(`${context.config.baseUrl}/files/page?pageNum=1&pageSize=10`, {
    headers,
    tags: pageTags,
  });
  const pageOk = checkApiSuccess(pageRes, 'files/page', pageTags);

  const listTags = buildRequestTags(scenarioName, 'files_list', 'GET');
  const listRes = get(`${context.config.baseUrl}/files/list`, {
    headers,
    tags: listTags,
  });
  const listOk = checkApiSuccess(listRes, 'files/list', listTags);

  const statsTags = buildRequestTags(scenarioName, 'files_stats', 'GET');
  const statsRes = get(`${context.config.baseUrl}/files/stats`, {
    headers,
    tags: statsTags,
  });
  const statsOk = checkApiSuccess(statsRes, 'files/stats', statsTags);

  return pageOk && listOk && statsOk;
}

/**
 * 初始化压测上下文（登录并返回 token）。
 *
 * @returns {{token:string, config:{baseUrl:string, tenantId:string, username:string, password:string, runId:string, resultDir:string, profile:string, scenario:string}}} 上下文
 */
export function setup() {
  const token = loginOrFail(baseConfig, 'file_query_setup', 1);
  return {
    token,
    config: baseConfig,
  };
}

/**
 * 默认执行函数（k6 入口）。
 *
 * @param {{token:string, config:{baseUrl:string, tenantId:string}}} data setup 返回上下文
 */
export default function (data) {
  runFileQueryFlow(data, 'file-query');
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
    console.warn(`[k6-cleanup] file-query teardown 清理异常: ${error && error.message ? error.message : error}`);
  }
}

export const handleSummary = createSummaryHandler(baseConfig, 'file-query');
