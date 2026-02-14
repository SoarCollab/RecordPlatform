import { buildRequestTags } from './config.js';
import { buildAuthHeaders, del, get, safeJsonPath, safeResultCode } from './http.js';

/**
 * 从分页记录中提取可删除标识（优先 fileHash）。
 *
 * @param {any[]} records 文件记录列表
 * @returns {string[]} 标识列表
 */
function extractIdentifiers(records) {
  const identifiers = [];
  for (const record of records || []) {
    if (record && record.fileHash) {
      identifiers.push(String(record.fileHash));
      continue;
    }

    if (record && record.id !== undefined && record.id !== null) {
      identifiers.push(String(record.id));
    }
  }

  return Array.from(new Set(identifiers));
}

/**
 * 按关键字分页查询文件记录。
 *
 * @param {{baseUrl:string, tenantId:string}} config 基础配置
 * @param {string} token JWT
 * @param {string} keyword 搜索关键字
 * @param {number} pageNum 页码
 * @returns {{records:any[], pages:number, ok:boolean}} 查询结果
 */
function queryFilesByKeyword(config, token, keyword, pageNum) {
  const tags = buildRequestTags('teardown_cleanup', 'files_cleanup_query', 'GET');
  const url = `${config.baseUrl}/files?pageNum=${pageNum}&pageSize=100&keyword=${encodeURIComponent(keyword)}`;

  const response = get(url, {
    headers: buildAuthHeaders(config.tenantId, token),
    tags,
  });

  const ok = response.status === 200 && safeResultCode(response) === 200;
  if (!ok) {
    return {
      ok: false,
      records: [],
      pages: 0,
    };
  }

  return {
    ok: true,
    records: safeJsonPath(response, 'data.records') || [],
    pages: safeJsonPath(response, 'data.pages') || 1,
  };
}

/**
 * 执行批量删除请求（逻辑删除）。
 *
 * @param {{baseUrl:string, tenantId:string}} config 基础配置
 * @param {string} token JWT
 * @param {string[]} identifiers 删除标识
 * @returns {boolean} 删除是否成功
 */
function deleteFiles(config, token, identifiers) {
  if (!identifiers || identifiers.length === 0) {
    return true;
  }

  const query = identifiers.map((identifier) => `identifiers=${encodeURIComponent(identifier)}`).join('&');
  const tags = buildRequestTags('teardown_cleanup', 'files_delete', 'DELETE');
  const response = del(`${config.baseUrl}/files/delete?${query}`, {
    headers: buildAuthHeaders(config.tenantId, token),
    tags,
  });

  return response.status === 200 && safeResultCode(response) === 200;
}

/**
 * 根据 runId 兜底清理压测文件，失败时只告警不抛错。
 *
 * @param {{config:{baseUrl:string, tenantId:string, runId:string}, token:string}} context 上下文
 */
export function cleanupRunFiles(context) {
  if (!context || !context.config || !context.token) {
    return;
  }

  const keyword = `k6-${context.config.runId}`;
  const allIdentifiers = [];
  const maxPages = 20;

  let pageNum = 1;
  let totalPages = 1;

  while (pageNum <= totalPages && pageNum <= maxPages) {
    const result = queryFilesByKeyword(context.config, context.token, keyword, pageNum);
    if (!result.ok) {
      console.warn(`[k6-cleanup] 查询待删文件失败，keyword=${keyword}, pageNum=${pageNum}`);
      break;
    }

    const identifiers = extractIdentifiers(result.records);
    allIdentifiers.push(...identifiers);

    totalPages = result.pages;
    pageNum += 1;
  }

  const deduplicated = Array.from(new Set(allIdentifiers));
  if (deduplicated.length === 0) {
    return;
  }

  const success = deleteFiles(context.config, context.token, deduplicated);
  if (!success) {
    console.warn(`[k6-cleanup] 批量删除失败，count=${deduplicated.length}`);
    return;
  }

  console.log(`[k6-cleanup] 批量删除成功，count=${deduplicated.length}`);
}
