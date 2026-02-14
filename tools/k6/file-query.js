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
 * 将查询参数对象编码为 URL 查询串。
 *
 * @param {Record<string, string|number|undefined|null>} query 查询参数
 * @returns {string} 编码后的查询串
 */
function buildQueryString(query) {
  return Object.entries(query || {})
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&');
}

/**
 * 执行 `/files` 查询并按 endpoint 标签记录指标。
 *
 * @param {{config:{baseUrl:string, tenantId:string}, token:string}} context 压测上下文
 * @param {string} scenarioName 场景名
 * @param {string} endpointTag endpoint 标签
 * @param {Record<string, string|number|undefined|null>} query 查询参数
 * @param {string} label 断言标签
 * @returns {boolean} 请求是否成功
 */
function runFilesQuery(context, scenarioName, endpointTag, query, label) {
  const headers = buildAuthHeaders(context.config.tenantId, context.token);
  const tags = buildRequestTags(scenarioName, endpointTag, 'GET');
  const queryString = buildQueryString(query);
  const url = queryString
    ? `${context.config.baseUrl}/files?${queryString}`
    : `${context.config.baseUrl}/files`;
  const response = get(url, {
    headers,
    tags,
  });
  return checkApiSuccess(response, label, tags);
}

/**
 * 执行文件查询四类场景（basic/keyword/combo/stats）。
 *
 * @param {{config:{baseUrl:string, tenantId:string}, token:string}} context 压测上下文
 * @param {string} scenarioName 场景名
 * @returns {boolean} 四个请求是否全部成功
 */
export function runFileQueryFlow(context, scenarioName = 'file-query') {
  const now = new Date();
  const startTime = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000).toISOString();
  const endTime = now.toISOString();
  const keyword = `k6-${context.config.runId}`;

  const basicOk = runFilesQuery(
    context,
    scenarioName,
    'files_basic',
    {
      pageNum: 1,
      pageSize: 10,
    },
    'files basic',
  );

  const keywordOk = runFilesQuery(
    context,
    scenarioName,
    'files_keyword',
    {
      pageNum: 1,
      pageSize: 10,
      keyword,
    },
    'files keyword',
  );

  const comboOk = runFilesQuery(
    context,
    scenarioName,
    'files_combo',
    {
      pageNum: 1,
      pageSize: 10,
      keyword,
      status: 1,
      startTime,
      endTime,
    },
    'files combo',
  );

  const statsTags = buildRequestTags(scenarioName, 'files_stats', 'GET');
  const statsRes = get(`${context.config.baseUrl}/files/stats`, {
    headers: buildAuthHeaders(context.config.tenantId, context.token),
    tags: statsTags,
  });
  const statsOk = checkApiSuccess(statsRes, 'files/stats', statsTags);

  return basicOk && keywordOk && comboOk && statsOk;
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
