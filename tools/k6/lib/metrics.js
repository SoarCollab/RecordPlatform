import { Counter, Rate, Trend } from 'k6/metrics';

/**
 * 上传端到端耗时（毫秒）。
 */
export const uploadE2eMs = new Trend('upload_e2e_ms', true);

/**
 * 业务失败率（HTTP 或业务 code 校验不通过）。
 */
export const businessErrorRate = new Rate('business_error_rate');

/**
 * 成功完成上传的文件计数。
 */
export const uploadFileCount = new Counter('upload_file_count');

/**
 * 接口请求总量（按 endpoint tag 聚合）。
 */
export const endpointRequestCount = new Counter('endpoint_request_count');

/**
 * 接口失败请求总量（按 endpoint tag 聚合）。
 */
export const endpointFailureCount = new Counter('endpoint_failure_count');

/**
 * HTTP 状态码统计。
 */
export const httpStatusCount = new Counter('http_status_count');

/**
 * 业务 code 统计。
 */
export const businessCodeCount = new Counter('business_code_count');

/**
 * 记录单次上传端到端耗时。
 *
 * @param {number} durationMs 耗时（毫秒）
 * @param {Record<string, string>} tags 指标标签
 */
export function recordUploadE2e(durationMs, tags = {}) {
  uploadE2eMs.add(durationMs, tags);
}

/**
 * 记录成功上传文件计数。
 *
 * @param {Record<string, string>} tags 指标标签
 */
export function recordUploadFile(tags = {}) {
  uploadFileCount.add(1, tags);
}

/**
 * 记录 endpoint 级别请求结果。
 *
 * @param {boolean} success 是否成功
 * @param {Record<string, string>} tags 指标标签
 */
export function recordEndpointResult(success, tags = {}) {
  endpointRequestCount.add(1, tags);
  if (!success) {
    endpointFailureCount.add(1, tags);
  }
}

/**
 * 记录 HTTP 状态码分布。
 *
 * @param {number|undefined|null} status HTTP 状态码
 * @param {Record<string, string>} tags 指标标签
 */
export function recordHttpStatus(status, tags = {}) {
  if (status === undefined || status === null) {
    return;
  }

  httpStatusCount.add(1, Object.assign({}, tags, { status: String(status) }));
}

/**
 * 记录业务 code 分布。
 *
 * @param {number|undefined|null} code 业务 code
 * @param {Record<string, string>} tags 指标标签
 */
export function recordBusinessCode(code, tags = {}) {
  if (code === undefined || code === null) {
    return;
  }

  businessCodeCount.add(1, Object.assign({}, tags, { code: String(code) }));
}
