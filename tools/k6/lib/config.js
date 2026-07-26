/**
 * 解析整数环境变量，异常或未设置时返回默认值。
 *
 * @param {string} name 环境变量名
 * @param {number} fallback 默认值
 * @param {number} min 最小允许值
 * @returns {number} 解析后的整数
 */
export function parseIntEnv(name, fallback, min = Number.MIN_SAFE_INTEGER) {
  const rawValue = __ENV[name];
  if (rawValue === undefined || rawValue === null || rawValue === '') {
    return fallback;
  }

  const parsed = parseInt(rawValue, 10);
  if (Number.isNaN(parsed)) {
    return fallback;
  }

  return parsed < min ? min : parsed;
}

/**
 * 解析布尔环境变量。
 *
 * @param {string} name 环境变量名
 * @param {boolean} fallback 默认值
 * @returns {boolean} 布尔结果
 */
export function parseBooleanEnv(name, fallback = false) {
  const rawValue = __ENV[name];
  if (rawValue === undefined || rawValue === null || rawValue === '') {
    return fallback;
  }

  return ['1', 'true', 'yes', 'on'].includes(String(rawValue).toLowerCase());
}

/**
 * 生成默认运行 ID，用于关联本次压测数据与清理逻辑。
 *
 * @returns {string} 运行 ID
 */
export function generateDefaultRunId() {
  return new Date().toISOString().replace(/[^0-9]/g, '').slice(0, 14);
}

/**
 * 构建基础配置（认证、地址、运行标识、报告路径）。
 *
 * @returns {{baseUrl:string, tenantId:string, username:string, password:string, runId:string, resultDir:string, profile:string, scenario:string, suite:string, directExecution:Record<string, any>}} 基础配置
 */
export function getBaseConfig() {
  const runId = __ENV.RUN_ID || generateDefaultRunId();
  const directPath = getDirectPathConfig();
  const profile = __ENV.K6_PROFILE || 'smoke';
  const suite = __ENV.K6_SUITE || 'local';

  return {
    baseUrl: __ENV.BASE_URL || 'http://localhost:8000/record-platform/api/v1',
    tenantId: __ENV.TENANT_ID || '1',
    username: __ENV.USERNAME || '',
    password: __ENV.PASSWORD || '',
    runId,
    resultDir: __ENV.RESULT_DIR || 'tools/k6/results',
    profile,
    scenario: __ENV.K6_SCENARIO || 'all',
    suite,
    engine: __ENV.K6_ENGINE || 'unknown',
    engineArtifact: __ENV.K6_ENGINE_ARTIFACT || 'unavailable:not-configured',
    environmentFingerprint: __ENV.ENVIRONMENT_FINGERPRINT || 'unavailable:not-configured',
    resourceSnapshotPath: __ENV.DIRECT_RESOURCE_SNAPSHOT_PATH || '',
    lifecycleSnapshotPath: __ENV.DIRECT_LIFECYCLE_SNAPSHOT_PATH || '',
    directPath,
    directExecution: getDirectExecutionConfig(profile, suite),
  };
}

/**
 * 构建可审计的 direct-path 执行模型，防止不同并发或时长被误当成同一基线。
 *
 * @param {string} profile 运行档位
 * @param {string} suite 套件来源（local|ci）
 * @returns {{executor:string,concurrency:number,duration:string,preAllocatedVUs:number|null,maxVUs:number|null}} 执行参数
 */
function getDirectExecutionConfig(profile, suite) {
  if (suite === 'ci') {
    return {
      executor: 'constant-vus',
      concurrency: parseIntEnv('CI_DIRECT_PATH_VUS', 1, 1),
      duration: __ENV.CI_DIRECT_PATH_DURATION || '60s',
      preAllocatedVUs: null,
      maxVUs: null,
    };
  }
  if (profile === 'load') {
    return {
      executor: 'constant-arrival-rate',
      concurrency: parseIntEnv('DIRECT_PATH_RATE', 1, 1),
      duration: __ENV.DIRECT_PATH_DURATION || '3m',
      preAllocatedVUs: parseIntEnv('DIRECT_PATH_PRE_ALLOCATED_VUS', 5, 1),
      maxVUs: parseIntEnv('DIRECT_PATH_MAX_VUS', 20, 1),
    };
  }
  return {
    executor: 'constant-vus',
    concurrency: parseIntEnv('SMOKE_DIRECT_PATH_VUS', parseIntEnv('VUS', 1, 1), 1),
    duration: __ENV.SMOKE_DIRECT_PATH_DURATION || __ENV.DURATION || '90s',
    preAllocatedVUs: null,
    maxVUs: null,
  };
}

/**
 * 校验关键配置项是否存在，缺失时抛出错误终止执行。
 *
 * @param {Record<string, string>} config 配置对象
 * @param {string[]} requiredFields 必填字段
 */
export function ensureRequiredConfig(config, requiredFields = ['baseUrl', 'tenantId', 'username', 'password']) {
  const missingFields = requiredFields.filter((field) => {
    const value = config[field];
    return value === undefined || value === null || value === '';
  });

  if (missingFields.length > 0) {
    throw new Error(`缺少必填配置: ${missingFields.join(', ')}`);
  }
}

/**
 * 构建上传链路压测配置。
 *
 * @returns {{totalChunks:number, chunkSize:number}} 上传配置
 */
export function getUploadConfig() {
  return {
    totalChunks: parseIntEnv('TOTAL_CHUNKS', 5, 1),
    chunkSize: parseIntEnv('CHUNK_SIZE', 1024, 1),
  };
}

/**
 * 构建直传上传/下载压测配置。
 *
 * @returns {{totalChunks:number, chunkSize:number, p99BudgetMs:number}} 直传配置
 */
export function getDirectPathConfig() {
  return {
    totalChunks: parseIntEnv('DIRECT_TOTAL_CHUNKS', parseIntEnv('TOTAL_CHUNKS', 2, 1), 1),
    chunkSize: parseIntEnv('DIRECT_CHUNK_SIZE', parseIntEnv('CHUNK_SIZE', 64 * 1024, 1), 1),
    p99BudgetMs: parseIntEnv('DIRECT_P99_BUDGET_MS', 60_000, 1),
  };
}

/**
 * 构建请求标签，统一指标聚合维度。
 *
 * @param {string} scenario 场景名
 * @param {string} endpoint 接口标识
 * @param {string} method HTTP 方法
 * @returns {{scenario:string, endpoint:string, method:string}} 标签对象
 */
export function buildRequestTags(scenario, endpoint, method) {
  return {
    scenario,
    endpoint,
    method,
  };
}

/**
 * 获取全局阈值配置。
 *
 * @returns {Record<string, string[]>} 阈值映射
 */
export function getGlobalThresholds() {
  return {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  };
}

/**
 * 获取查询链路阈值配置。
 *
 * @returns {Record<string, string[]>} 阈值映射
 */
export function getQueryThresholds() {
  return {
    'http_req_duration{endpoint:files_basic}': ['p(95)<800'],
    'http_req_duration{endpoint:files_keyword}': ['p(95)<800'],
    'http_req_duration{endpoint:files_combo}': ['p(95)<1000'],
    'http_req_duration{endpoint:files_stats}': ['p(95)<800'],
  };
}

/**
 * 获取上传链路阈值配置。
 *
 * @returns {Record<string, string[]>} 阈值映射
 */
export function getUploadThresholds() {
  return {
    'http_req_duration{endpoint:upload_start}': ['p(95)<1200'],
    'http_req_duration{endpoint:upload_chunk}': ['p(95)<1500'],
    'http_req_duration{endpoint:upload_complete}': ['p(95)<1500'],
    upload_e2e_ms: ['p(95)<6000'],
  };
}

/**
 * 获取直传全链路阈值，确保失败、哈希漂移和清理残留都能使运行失败。
 *
 * @returns {Record<string, string[]>} 直传阈值映射
 */
export function getDirectPathThresholds() {
  const budget = getDirectPathConfig().p99BudgetMs;
  return {
    direct_flow_failure_rate: ['rate==0'],
    direct_cleanup_failure_rate: ['rate==0'],
    direct_upload_e2e_ms: [`p(99)<${budget}`],
    direct_download_e2e_ms: [`p(99)<${budget}`],
    direct_path_e2e_ms: [`p(99)<${budget}`],
  };
}

/**
 * 合并多组阈值配置。
 *
 * @param {...Record<string, string[]>} thresholdGroups 阈值组
 * @returns {Record<string, string[]>} 合并后的阈值
 */
export function mergeThresholds(...thresholdGroups) {
  return Object.assign({}, ...thresholdGroups);
}

/**
 * 返回报告必须保留的 Trend 统计项，确保 p50/p95/p99 可进入 handleSummary。
 *
 * @returns {string[]} Trend 统计项
 */
export function getSummaryTrendStats() {
  return ['avg', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'];
}

/**
 * 返回不会记录请求 URL/name 的系统标签，防止预签名查询参数进入输出或 artifact。
 *
 * @returns {string[]} 允许的 k6 系统标签
 */
export function getSafeSystemTags() {
  return [
    'status',
    'method',
    'group',
    'check',
    'error',
    'error_code',
    'scenario',
    'service',
    'expected_response',
  ];
}

/**
 * 构建常驻 VU 的基础 options。
 *
 * @param {number} defaultVus 默认 VU 数
 * @param {string} defaultDuration 默认持续时间
 * @param {Record<string, string[]>} thresholds 阈值配置
 * @returns {{vus:number, duration:string, thresholds:Record<string, string[]>}} k6 options
 */
export function createConstantVusOptions(defaultVus, defaultDuration, thresholds) {
  return {
    vus: parseIntEnv('VUS', defaultVus, 1),
    duration: __ENV.DURATION || defaultDuration,
    thresholds,
    summaryTrendStats: getSummaryTrendStats(),
    systemTags: getSafeSystemTags(),
  };
}

/**
 * 按运行目录和文件名拼接输出路径。
 *
 * @param {string} resultDir 结果目录
 * @param {string} fileName 文件名
 * @returns {string} 输出路径
 */
export function buildResultPath(resultDir, fileName) {
  const normalizedDir = (resultDir || '').replace(/\/+$/, '');
  if (!normalizedDir) {
    return fileName;
  }
  return `${normalizedDir}/${fileName}`;
}
