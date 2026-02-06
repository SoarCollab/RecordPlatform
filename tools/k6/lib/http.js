import http from 'k6/http';

/**
 * 安全读取统一响应体中的业务 code。
 *
 * @param {import('k6/http').RefinedResponse<'text'>} response HTTP 响应
 * @returns {number|undefined} 业务状态码
 */
export function safeResultCode(response) {
  try {
    return response.json('code');
  } catch (_) {
    return undefined;
  }
}

/**
 * 安全读取 JSON 路径，避免因非 JSON 响应抛错。
 *
 * @param {import('k6/http').RefinedResponse<'text'>} response HTTP 响应
 * @param {string} path JSON 路径
 * @returns {*} 读取结果
 */
export function safeJsonPath(response, path) {
  try {
    return response.json(path);
  } catch (_) {
    return undefined;
  }
}

/**
 * 构建基础请求头，默认注入租户标识。
 *
 * @param {string} tenantId 租户 ID
 * @param {Record<string, string>} extraHeaders 额外请求头
 * @returns {Record<string, string>} 请求头
 */
export function buildHeaders(tenantId, extraHeaders = {}) {
  return Object.assign(
    {
      'X-Tenant-ID': String(tenantId),
    },
    extraHeaders,
  );
}

/**
 * 构建带鉴权的请求头。
 *
 * @param {string} tenantId 租户 ID
 * @param {string} token JWT
 * @param {Record<string, string>} extraHeaders 额外请求头
 * @returns {Record<string, string>} 请求头
 */
export function buildAuthHeaders(tenantId, token, extraHeaders = {}) {
  return buildHeaders(tenantId, Object.assign({ Authorization: `Bearer ${token}` }, extraHeaders));
}

/**
 * 发送 GET 请求并自动合并 tags。
 *
 * @param {string} url 请求地址
 * @param {{headers?: Record<string,string>, tags?: Record<string,string>}} params 请求参数
 * @returns {import('k6/http').RefinedResponse<'text'>} HTTP 响应
 */
export function get(url, params = {}) {
  return http.get(url, params);
}

/**
 * 发送 POST 请求并自动合并 tags。
 *
 * @param {string} url 请求地址
 * @param {*} body 请求体
 * @param {{headers?: Record<string,string>, tags?: Record<string,string>}} params 请求参数
 * @returns {import('k6/http').RefinedResponse<'text'>} HTTP 响应
 */
export function post(url, body, params = {}) {
  return http.post(url, body, params);
}

/**
 * 发送 DELETE 请求并自动合并 tags。
 *
 * @param {string} url 请求地址
 * @param {{headers?: Record<string,string>, tags?: Record<string,string>}} params 请求参数
 * @returns {import('k6/http').RefinedResponse<'text'>} HTTP 响应
 */
export function del(url, params = {}) {
  return http.del(url, null, params);
}

/**
 * 使用表单参数调用 POST（对应 @RequestParam 接口）。
 *
 * @param {string} url 请求地址
 * @param {Record<string, string>} formData 表单参数
 * @param {{headers?: Record<string,string>, tags?: Record<string,string>}} params 请求参数
 * @returns {import('k6/http').RefinedResponse<'text'>} HTTP 响应
 */
export function postForm(url, formData, params = {}) {
  const headers = Object.assign({}, params.headers || {}, {
    'Content-Type': 'application/x-www-form-urlencoded',
  });
  return post(url, formData, Object.assign({}, params, { headers }));
}
