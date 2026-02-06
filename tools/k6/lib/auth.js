import { buildRequestTags } from './config.js';
import { checkApiSuccess, checkTokenPresent } from './assertions.js';
import { buildHeaders, post } from './http.js';

/**
 * 执行一次登录请求并返回 token。
 *
 * @param {{baseUrl:string, tenantId:string, username:string, password:string}} config 基础配置
 * @param {string} scenarioName 场景名
 * @returns {{token:string|undefined, ok:boolean}} 登录结果
 */
export function login(config, scenarioName = 'auth') {
  const tags = buildRequestTags(scenarioName, 'auth_login', 'POST');
  const response = post(
    `${config.baseUrl}/auth/login`,
    JSON.stringify({
      username: config.username,
      password: config.password,
    }),
    {
      headers: buildHeaders(config.tenantId, {
        'Content-Type': 'application/json',
      }),
      tags,
    },
  );

  const success = checkApiSuccess(response, 'auth/login', tags, 200, 200);
  const token = checkTokenPresent(response, 'auth/login', tags);

  return {
    token,
    ok: success && !!token,
  };
}

/**
 * 带重试执行登录，重试失败则返回空 token。
 *
 * @param {{baseUrl:string, tenantId:string, username:string, password:string}} config 基础配置
 * @param {string} scenarioName 场景名
 * @param {number} maxRetries 最大重试次数
 * @returns {string|undefined} token
 */
export function loginWithRetry(config, scenarioName = 'auth', maxRetries = 1) {
  let attempts = 0;
  while (attempts <= maxRetries) {
    attempts += 1;
    const result = login(config, scenarioName);
    if (result.ok) {
      return result.token;
    }
  }
  return undefined;
}

/**
 * 执行登录并在失败时抛错，确保压测快速失败。
 *
 * @param {{baseUrl:string, tenantId:string, username:string, password:string}} config 基础配置
 * @param {string} scenarioName 场景名
 * @param {number} maxRetries 最大重试次数
 * @returns {string} token
 */
export function loginOrFail(config, scenarioName = 'auth', maxRetries = 1) {
  const token = loginWithRetry(config, scenarioName, maxRetries);
  if (!token) {
    throw new Error('登录失败：未获取到有效 token，请检查账号、密码和 X-Tenant-ID。');
  }
  return token;
}
