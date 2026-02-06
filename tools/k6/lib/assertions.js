import { check } from 'k6';
import {
  businessErrorRate,
  recordBusinessCode,
  recordEndpointResult,
  recordHttpStatus,
} from './metrics.js';
import { safeJsonPath, safeResultCode } from './http.js';

/**
 * 记录一次请求的统一指标（成功率、状态码、业务码）。
 *
 * @param {import('k6/http').RefinedResponse<'text'>} response HTTP 响应
 * @param {Record<string, string>} tags 指标标签
 * @param {boolean} success 是否成功
 */
function recordRequestMetrics(response, tags, success) {
  recordEndpointResult(success, tags);
  recordHttpStatus(response?.status, tags);
  recordBusinessCode(safeResultCode(response), tags);
  businessErrorRate.add(!success, tags);
}

/**
 * 校验统一接口响应（HTTP 状态 + 业务 code）。
 *
 * @param {import('k6/http').RefinedResponse<'text'>} response HTTP 响应
 * @param {string} label 断言标签前缀
 * @param {Record<string, string>} tags 指标标签
 * @param {number} expectedStatus 期望 HTTP 状态码
 * @param {number} expectedCode 期望业务 code
 * @returns {boolean} 是否通过校验
 */
export function checkApiSuccess(response, label, tags = {}, expectedStatus = 200, expectedCode = 200) {
  const statusOk = check(response, {
    [`${label} http ${expectedStatus}`]: (r) => r.status === expectedStatus,
  });

  const codeOk = check(response, {
    [`${label} code ${expectedCode}`]: (r) => safeResultCode(r) === expectedCode,
  });

  const success = statusOk && codeOk;
  recordRequestMetrics(response, tags, success);
  return success;
}

/**
 * 校验登录返回是否包含 token。
 *
 * @param {import('k6/http').RefinedResponse<'text'>} response HTTP 响应
 * @param {string} label 断言标签前缀
 * @param {Record<string, string>} tags 指标标签
 * @returns {string|undefined} token
 */
export function checkTokenPresent(response, label, tags = {}) {
  const token = safeJsonPath(response, 'data.token');
  const tokenOk = check(response, {
    [`${label} token present`]: () => !!token,
  });

  businessErrorRate.add(!tokenOk, tags);
  return token;
}

/**
 * 校验响应体中字段存在。
 *
 * @param {import('k6/http').RefinedResponse<'text'>} response HTTP 响应
 * @param {string} jsonPath JSON 路径
 * @param {string} label 断言标签
 * @param {Record<string, string>} tags 指标标签
 * @returns {*} 字段值
 */
export function checkFieldPresent(response, jsonPath, label, tags = {}) {
  const value = safeJsonPath(response, jsonPath);
  const ok = check(response, {
    [label]: () => value !== undefined && value !== null && value !== '',
  });

  businessErrorRate.add(!ok, tags);
  return value;
}
