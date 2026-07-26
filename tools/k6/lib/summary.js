import { buildResultPath } from './config.js';

/**
 * 构建输出目录（直接使用 RESULT_DIR，避免重复拼接 runId）。
 *
 * @param {{resultDir:string, runId:string}} config 运行配置
 * @returns {string} 输出目录
 */
function getOutputDir(config) {
  return (config.resultDir || 'tools/k6/results').replace(/\/+$/, '');
}

/**
 * 格式化数值为毫秒字符串。
 *
 * @param {number|undefined} value 数值
 * @returns {string} 可读文本
 */
function formatMs(value) {
  if (value === undefined || value === null) {
    return 'N/A';
  }
  return `${Number(value).toFixed(2)}ms`;
}

/**
 * 格式化百分比文本。
 *
 * @param {number|undefined} value 比例值（0~1）
 * @returns {string} 百分比文本
 */
function formatRate(value) {
  if (value === undefined || value === null) {
    return 'N/A';
  }
  return `${(Number(value) * 100).toFixed(2)}%`;
}

/**
 * 格式化计数字段。
 *
 * @param {number|undefined} value 数值
 * @returns {string} 计数字符串
 */
function formatCount(value) {
  if (value === undefined || value === null) {
    return 'N/A';
  }
  return `${Math.round(Number(value))}`;
}

/**
 * 从指标 key 中解析 tags。
 *
 * @param {string} metricKey 指标 key
 * @returns {Record<string, string>} tag 映射
 */
function parseMetricTags(metricKey) {
  const match = metricKey.match(/^[^{}]+\{(.+)\}$/);
  if (!match || !match[1]) {
    return {};
  }

  const tags = {};
  const pairs = match[1].split(',');
  for (const pair of pairs) {
    const separatorIndex = pair.indexOf(':');
    if (separatorIndex <= 0) {
      continue;
    }

    const key = pair.slice(0, separatorIndex).trim();
    const value = pair.slice(separatorIndex + 1).trim();
    if (key) {
      tags[key] = value;
    }
  }

  return tags;
}

/**
 * 从指标中提取阈值结果。
 *
 * @param {Record<string, any>} metrics 指标对象
 * @returns {{lines:string[], failed:number, items:Array<{metric:string, threshold:string, ok:boolean}>}} 阈值汇总
 */
function collectThresholdStatus(metrics) {
  const lines = [];
  let failed = 0;
  const items = [];

  for (const [metricName, metricData] of Object.entries(metrics || {})) {
    const thresholds = metricData.thresholds || {};
    for (const [thresholdName, thresholdResult] of Object.entries(thresholds)) {
      const ok = thresholdResult && thresholdResult.ok === true;
      if (!ok) {
        failed += 1;
      }
      lines.push(`${ok ? 'PASS' : 'FAIL'} | ${metricName} | ${thresholdName}`);
      items.push({
        metric: metricName,
        threshold: thresholdName,
        ok,
      });
    }
  }

  return { lines, failed, items };
}

/**
 * 聚合 endpoint 维度统计（时延、请求量、失败量）。
 *
 * @param {Record<string, any>} metrics 指标对象
 * @returns {Record<string, {durationValues:Record<string,number>, requests:number, failures:number}>} 端点聚合结果
 */
function collectEndpointStats(metrics) {
  const endpointStats = {};

  for (const [metricKey, metricData] of Object.entries(metrics || {})) {
    const tags = parseMetricTags(metricKey);
    const endpoint = tags.endpoint;
    if (!endpoint) {
      continue;
    }

    if (!endpointStats[endpoint]) {
      endpointStats[endpoint] = {
        durationValues: {},
        requests: 0,
        failures: 0,
      };
    }

    const values = metricData?.values || {};
    const count = Number(values.count || 0);

    if (metricKey.startsWith('http_req_duration{')) {
      endpointStats[endpoint].durationValues = values;
    } else if (metricKey.startsWith('endpoint_request_count{')) {
      endpointStats[endpoint].requests += count;
    } else if (metricKey.startsWith('endpoint_failure_count{')) {
      endpointStats[endpoint].failures += count;
    }
  }

  return endpointStats;
}

/**
 * 构建 endpoint 维度报告行。
 *
 * @param {Record<string, {durationValues:Record<string,number>, requests:number, failures:number}>} endpointStats 端点聚合结果
 * @returns {string[]} endpoint 指标行
 */
function buildEndpointMetricLines(endpointStats) {
  const endpoints = Object.keys(endpointStats || {}).sort();

  return endpoints.map((endpoint) => {
    const stat = endpointStats[endpoint];
    const durationValues = stat.durationValues || {};
    const errorRate = stat.requests > 0 ? stat.failures / stat.requests : undefined;

    return `${endpoint} -> p50=${formatMs(durationValues['p(50)'])}, p90=${formatMs(durationValues['p(90)'])}, p95=${formatMs(durationValues['p(95)'])}, p99=${formatMs(durationValues['p(99)'])}, error=${formatRate(errorRate)}, requests=${formatCount(stat.requests)}`;
  });
}

/**
 * 构建 endpoint 维度结构化快照，供报告自动回填使用。
 *
 * @param {Record<string, {durationValues:Record<string,number>, requests:number, failures:number}>} endpointStats 端点聚合结果
 * @returns {Record<string, {p50:number|null, p90:number|null, p95:number|null, p99:number|null, errorRate:number|null, requests:number, failures:number}>} endpoint 快照
 */
function buildEndpointMetricSnapshot(endpointStats) {
  const snapshot = {};
  for (const endpoint of Object.keys(endpointStats || {}).sort()) {
    const stat = endpointStats[endpoint];
    const durationValues = stat.durationValues || {};
    const errorRate = stat.requests > 0 ? stat.failures / stat.requests : null;

    snapshot[endpoint] = {
      p50: durationValues['p(50)'] ?? null,
      p90: durationValues['p(90)'] ?? null,
      p95: durationValues['p(95)'] ?? null,
      p99: durationValues['p(99)'] ?? null,
      errorRate,
      requests: stat.requests,
      failures: stat.failures,
    };
  }
  return snapshot;
}

/**
 * 聚合并排序样本计数。
 *
 * @param {{key:string, count:number}[]} samples 原始样本
 * @returns {{key:string, count:number}[]} 聚合后样本
 */
function aggregateSamples(samples) {
  const map = {};
  for (const sample of samples) {
    const key = sample.key;
    if (!key) {
      continue;
    }
    map[key] = (map[key] || 0) + sample.count;
  }

  return Object.entries(map)
    .map(([key, count]) => ({ key, count: Number(count) }))
    .sort((left, right) => right.count - left.count);
}

/**
 * 提取关键失败样本（HTTP 状态码与业务 code）。
 *
 * @param {Record<string, any>} metrics 指标对象
 * @returns {{statusLines:string[], codeLines:string[]}} 失败样本行
 */
function collectFailureSamples(metrics) {
  const rawStatusSamples = [];
  const rawCodeSamples = [];

  for (const [metricKey, metricData] of Object.entries(metrics || {})) {
    const tags = parseMetricTags(metricKey);
    const count = Number(metricData?.values?.count || 0);
    if (count <= 0) {
      continue;
    }

    if (metricKey.startsWith('http_status_count{')) {
      const status = tags.status;
      if (status && status !== '200') {
        rawStatusSamples.push({ key: status, count });
      }
    }

    if (metricKey.startsWith('business_code_count{')) {
      const code = tags.code;
      if (code && code !== '200') {
        rawCodeSamples.push({ key: code, count });
      }
    }
  }

  const statusLines = aggregateSamples(rawStatusSamples)
    .slice(0, 10)
    .map((item) => `HTTP ${item.key}: ${item.count}`);

  const codeLines = aggregateSamples(rawCodeSamples)
    .slice(0, 10)
    .map((item) => `BUSINESS ${item.key}: ${item.count}`);

  return {
    statusLines,
    codeLines,
  };
}

/**
 * 构建精简 metrics 快照，便于后处理。
 *
 * @param {Record<string, any>} metrics 指标对象
 * @returns {Record<string, any>} 精简快照
 */
function buildMetricsSnapshot(metrics) {
  const snapshot = {};
  for (const [metricName, metricData] of Object.entries(metrics || {})) {
    snapshot[metricName] = {
      type: metricData.type,
      contains: metricData.contains,
      values: metricData.values,
      thresholds: metricData.thresholds,
    };
  }
  return snapshot;
}

/**
 * 提取 Trend 指标的分位值，缺失时保留 null。
 *
 * @param {Record<string, any>} metrics 指标对象
 * @param {string} metricName 指标名
 * @returns {{p50:number|null,p95:number|null,p99:number|null,avg:number|null}} 分位值
 */
function readTrend(metrics, metricName) {
  const values = metrics?.[metricName]?.values || {};
  return {
    p50: values['p(50)'] ?? null,
    p95: values['p(95)'] ?? null,
    p99: values['p(99)'] ?? null,
    avg: values.avg ?? null,
  };
}

/**
 * 提取 Rate 指标并显式标识未采集状态。
 *
 * @param {Record<string, any>} metrics 指标对象
 * @param {string} metricName 指标名
 * @param {boolean} configured 是否配置了采集端点
 * @returns {{status:string,rate:number|null,reason:string|null}} 可用性状态
 */
function readAvailability(metrics, metricName, configured) {
  if (!configured) {
    return {
      status: 'unavailable',
      rate: null,
      reason: 'not_configured',
    };
  }
  const values = metrics?.[metricName]?.values;
  if (!values || values.rate === undefined || values.rate === null) {
    return {
      status: 'unavailable',
      rate: null,
      reason: 'no_samples',
    };
  }
  return {
    status: Number(values.rate) > 0 ? 'available' : 'unavailable',
    rate: Number(values.rate),
    reason: Number(values.rate) > 0 ? null : 'probe_failed',
  };
}

/**
 * 构建开始/结束两阶段快照可用性，禁止用单次探测冒充前后对照。
 *
 * @param {Record<string, any>} metrics 指标对象
 * @param {string} prefix 指标前缀
 * @param {boolean} configured 是否配置了快照端点
 * @returns {{start:Record<string,any>,end:Record<string,any>}} 两阶段状态
 */
function readSnapshotPair(metrics, prefix, configured) {
  return {
    start: readAvailability(metrics, `${prefix}_start_availability`, configured),
    end: readAvailability(metrics, `${prefix}_end_availability`, configured),
  };
}

/**
 * 读取 Counter 数值；指标缺失时返回 null，禁止把未执行伪装成零。
 *
 * @param {Record<string, any>} values Counter values
 * @param {string} name 字段名
 * @returns {number|null} 真实数值或不可用
 */
function readFiniteValue(values, name) {
  const value = values?.[name];
  return Number.isFinite(value) ? Number(value) : null;
}

/**
 * 构建直传全链路基线，环境不一致时由比较器拒绝比较。
 *
 * @param {any} data k6 summary 数据
 * @param {string} suiteName 套件名
 * @param {{runId:string,profile:string,scenario:string,engine:string,environmentFingerprint:string,resourceSnapshotPath:string,lifecycleSnapshotPath:string,directExecution:Record<string,any>}} config 配置
 * @returns {Record<string, any>} 直传基线
 */
function buildDirectPathBaseline(data, suiteName, config) {
  const metrics = data.metrics || {};
  const uploaded = metrics.direct_uploaded_bytes?.values || {};
  const downloaded = metrics.direct_downloaded_bytes?.values || {};
  const files = metrics.direct_file_count?.values || {};
  const flowFailure = metrics.direct_flow_failure_rate?.values || {};
  const cleanupFailure = metrics.direct_cleanup_failure_rate?.values || {};
  const flowSamples = Number(flowFailure.passes || 0) + Number(flowFailure.fails || 0);
  const cleanupSamples = Number(cleanupFailure.passes || 0) + Number(cleanupFailure.fails || 0);
  const completedFiles = Number(files.count || 0);
  const latency = {
    upload: readTrend(metrics, 'direct_upload_e2e_ms'),
    download: readTrend(metrics, 'direct_download_e2e_ms'),
    endToEnd: readTrend(metrics, 'direct_path_e2e_ms'),
  };
  const throughput = {
    uploadedBytes: readFiniteValue(uploaded, 'count'),
    uploadedBytesPerSecond: readFiniteValue(uploaded, 'rate'),
    downloadedBytes: readFiniteValue(downloaded, 'count'),
    downloadedBytesPerSecond: readFiniteValue(downloaded, 'rate'),
    completedFiles: readFiniteValue(files, 'count'),
    completedFilesPerSecond: readFiniteValue(files, 'rate'),
  };
  const requiredNumbers = [
    latency.upload.p95,
    latency.upload.p99,
    latency.download.p95,
    latency.download.p99,
    latency.endToEnd.p95,
    latency.endToEnd.p99,
    throughput.uploadedBytesPerSecond,
    throughput.downloadedBytesPerSecond,
    flowFailure.rate,
    cleanupFailure.rate,
  ];
  const metricsComplete = requiredNumbers.every((value) => Number.isFinite(value));
  const compatibilityComplete = Boolean(
    config.environmentFingerprint &&
    !String(config.environmentFingerprint).startsWith('unavailable:') &&
    config.engine &&
    config.engineArtifact &&
    !String(config.engineArtifact).startsWith('unavailable:') &&
    config.directPath &&
    Number.isFinite(config.directPath.totalChunks) &&
    Number.isFinite(config.directPath.chunkSize) &&
    config.directExecution?.executor &&
    Number.isFinite(config.directExecution?.concurrency) &&
    config.directExecution?.duration,
  );
  return {
    schemaVersion: 2,
    runId: config.runId,
    profile: config.profile,
    scenario: config.scenario,
    suite: suiteName,
    generatedAt: new Date().toISOString(),
    environment: {
      fingerprint: config.environmentFingerprint || 'unavailable:not-configured',
      engine: config.engine || 'unknown',
      engineArtifact: config.engineArtifact || 'unavailable:not-configured',
    },
    workload: config.directPath || null,
    execution: config.directExecution || null,
    latencyMs: latency,
    throughput,
    failure: {
      flowRate: flowFailure.rate ?? null,
      cleanupRate: cleanupFailure.rate ?? null,
    },
    evidence: {
      flowSamples,
      cleanupSamples,
      completedFiles,
      metricsComplete,
      compatibilityComplete,
      valid: flowSamples > 0 && cleanupSamples > 0 && completedFiles > 0 &&
        metricsComplete && compatibilityComplete,
    },
    resourceSnapshot: readSnapshotPair(
      metrics,
      'direct_resource_snapshot',
      Boolean(config.resourceSnapshotPath),
    ),
    lifecycleSnapshot: readSnapshotPair(
      metrics,
      'direct_lifecycle_snapshot',
      Boolean(config.lifecycleSnapshotPath),
    ),
  };
}

/**
 * 渲染直传基线的人类可读 Markdown 报告。
 *
 * @param {Record<string, any>} baseline 直传基线
 * @returns {string} Markdown 报告
 */
function buildDirectPathReport(baseline) {
  const upload = baseline.latencyMs.upload;
  const download = baseline.latencyMs.download;
  const endToEnd = baseline.latencyMs.endToEnd;
  const lines = [
    '# Direct path load report',
    '',
    `- Run: \`${baseline.runId}\``,
    `- Profile/scenario: \`${baseline.profile}/${baseline.scenario}\``,
    `- Environment fingerprint: \`${baseline.environment.fingerprint}\``,
    `- Engine: \`${baseline.environment.engine}\` (${baseline.environment.engineArtifact})`,
    `- Workload: ${baseline.workload ? `${baseline.workload.totalChunks} chunks x ${baseline.workload.chunkSize} bytes` : 'unavailable'}`,
    `- Execution: ${baseline.execution ? `${baseline.execution.executor}, concurrency=${baseline.execution.concurrency}, duration=${baseline.execution.duration}` : 'unavailable'}`,
    '',
    '| Metric | p50 | p95 | p99 |',
    '|---|---:|---:|---:|',
    `| Upload | ${formatMs(upload.p50)} | ${formatMs(upload.p95)} | ${formatMs(upload.p99)} |`,
    `| Download | ${formatMs(download.p50)} | ${formatMs(download.p95)} | ${formatMs(download.p99)} |`,
    `| End-to-end | ${formatMs(endToEnd.p50)} | ${formatMs(endToEnd.p95)} | ${formatMs(endToEnd.p99)} |`,
    '',
    `- Upload throughput: ${baseline.throughput.uploadedBytesPerSecond === null ? 'N/A' : `${Number(baseline.throughput.uploadedBytesPerSecond).toFixed(2)} bytes/s`}`,
    `- Download throughput: ${baseline.throughput.downloadedBytesPerSecond === null ? 'N/A' : `${Number(baseline.throughput.downloadedBytesPerSecond).toFixed(2)} bytes/s`}`,
    `- Flow failure rate: ${formatRate(baseline.failure.flowRate)}`,
    `- Cleanup failure rate: ${formatRate(baseline.failure.cleanupRate)}`,
    `- Evidence valid: ${baseline.evidence.valid} (flows=${baseline.evidence.flowSamples}, cleanup=${baseline.evidence.cleanupSamples}, files=${baseline.evidence.completedFiles})`,
    `- Resource snapshot start/end: ${baseline.resourceSnapshot.start.status} / ${baseline.resourceSnapshot.end.status}`,
    `- Lifecycle snapshot start/end: ${baseline.lifecycleSnapshot.start.status} / ${baseline.lifecycleSnapshot.end.status}`,
    '',
    '> unavailable 表示目标环境未配置或未成功返回观测快照，不会伪装成 0。',
    '',
  ];
  return lines.join('\n');
}

/**
 * 生成人类可读文本报告。
 *
 * @param {any} data k6 summary 数据
 * @param {string} suiteName 套件名
 * @param {{runId:string, profile:string, scenario:string}} config 配置
 * @returns {string} 文本报告
 */
function buildTextSummary(data, suiteName, config) {
  const metrics = data.metrics || {};
  const requestValues = metrics.http_reqs?.values || {};
  const durationValues = metrics.http_req_duration?.values || {};
  const failedValues = metrics.http_req_failed?.values || {};
  const checksValues = metrics.checks?.values || {};
  const uploadValues = metrics.upload_e2e_ms?.values || {};
  const directUploadValues = metrics.direct_upload_e2e_ms?.values || {};
  const directDownloadValues = metrics.direct_download_e2e_ms?.values || {};
  const directPathValues = metrics.direct_path_e2e_ms?.values || {};

  const thresholdStatus = collectThresholdStatus(metrics);
  const endpointStats = collectEndpointStats(metrics);
  const endpointLines = buildEndpointMetricLines(endpointStats);
  const failureSamples = collectFailureSamples(metrics);

  const lines = [];
  lines.push(`K6 Report | suite=${suiteName} | runId=${config.runId}`);
  lines.push(`profile=${config.profile} | scenario=${config.scenario}`);
  lines.push('');
  lines.push('== Global Metrics ==');
  lines.push(`http_reqs: count=${formatCount(requestValues.count)}`);
  lines.push(`http_req_duration: avg=${formatMs(durationValues.avg)}, p90=${formatMs(durationValues['p(90)'])}, p95=${formatMs(durationValues['p(95)'])}, p99=${formatMs(durationValues['p(99)'])}`);
  lines.push(`http_req_failed: rate=${failedValues.rate !== undefined ? Number(failedValues.rate).toFixed(4) : 'N/A'}`);
  lines.push(`checks: rate=${checksValues.rate !== undefined ? Number(checksValues.rate).toFixed(4) : 'N/A'}`);
  lines.push(`upload_e2e_ms: p95=${formatMs(uploadValues['p(95)'])}`);
  lines.push(`direct_upload_e2e_ms: p95=${formatMs(directUploadValues['p(95)'])}, p99=${formatMs(directUploadValues['p(99)'])}`);
  lines.push(`direct_download_e2e_ms: p95=${formatMs(directDownloadValues['p(95)'])}, p99=${formatMs(directDownloadValues['p(99)'])}`);
  lines.push(`direct_path_e2e_ms: p95=${formatMs(directPathValues['p(95)'])}, p99=${formatMs(directPathValues['p(99)'])}`);
  lines.push('');

  lines.push('== Endpoint Metrics ==');
  if (endpointLines.length === 0) {
    lines.push('No endpoint-tagged metrics found.');
  } else {
    lines.push(...endpointLines);
  }
  lines.push('');

  lines.push('== Thresholds ==');
  if (thresholdStatus.lines.length === 0) {
    lines.push('No thresholds configured.');
  } else {
    lines.push(...thresholdStatus.lines);
  }
  lines.push(`Threshold Failed Count: ${thresholdStatus.failed}`);
  lines.push('');

  lines.push('== Failure Samples ==');
  if (failureSamples.statusLines.length === 0 && failureSamples.codeLines.length === 0) {
    lines.push('No non-200 status or non-200 business code samples.');
  } else {
    if (failureSamples.statusLines.length > 0) {
      lines.push('HTTP Status Samples:');
      lines.push(...failureSamples.statusLines);
    }

    if (failureSamples.codeLines.length > 0) {
      lines.push('Business Code Samples:');
      lines.push(...failureSamples.codeLines);
    }
  }

  return `${lines.join('\n')}\n`;
}

/**
 * 构建可直接回填检索基线报告的结构化 JSON 快照。
 *
 * @param {any} data k6 summary 数据
 * @param {string} suiteName 套件名
 * @param {{runId:string, profile:string, scenario:string}} config 配置
 * @returns {{runId:string, profile:string, scenario:string, suite:string, generatedAt:string, thresholdFailedCount:number, thresholds:Array<{metric:string, threshold:string, ok:boolean}>, endpoints:Record<string, {p50:number|null, p90:number|null, p95:number|null, p99:number|null, errorRate:number|null, requests:number, failures:number}>}} 基线快照
 */
function buildQueryBaselineSnapshot(data, suiteName, config) {
  const metrics = data.metrics || {};
  const thresholdStatus = collectThresholdStatus(metrics);
  const endpointStats = collectEndpointStats(metrics);
  return {
    runId: config.runId,
    profile: config.profile,
    scenario: config.scenario,
    suite: suiteName,
    generatedAt: new Date().toISOString(),
    thresholdFailedCount: thresholdStatus.failed,
    thresholds: thresholdStatus.items,
    endpoints: buildEndpointMetricSnapshot(endpointStats),
  };
}

/**
 * 创建统一 handleSummary 处理器。
 *
 * @param {{resultDir:string, runId:string, profile:string, scenario:string}} config 配置
 * @param {string} suiteName 套件名
 * @returns {(data:any)=>Record<string,string>} handleSummary 函数
 */
export function createSummaryHandler(config, suiteName) {
  const outputDir = getOutputDir(config);

  return function handleSummary(data) {
    const textSummary = buildTextSummary(data, suiteName, config);
    const metricsSnapshot = buildMetricsSnapshot(data.metrics || {});
    const queryBaselineSnapshot = buildQueryBaselineSnapshot(data, suiteName, config);
    const directPathBaseline = buildDirectPathBaseline(data, suiteName, config);
    const directPathReport = buildDirectPathReport(directPathBaseline);

    return {
      [buildResultPath(outputDir, 'summary.txt')]: textSummary,
      [buildResultPath(outputDir, 'summary.json')]: JSON.stringify(data, null, 2),
      [buildResultPath(outputDir, 'metrics.json')]: JSON.stringify(metricsSnapshot, null, 2),
      [buildResultPath(outputDir, 'query-baseline.json')]: JSON.stringify(queryBaselineSnapshot, null, 2),
      [buildResultPath(outputDir, 'direct-path-baseline.json')]: JSON.stringify(directPathBaseline, null, 2),
      [buildResultPath(outputDir, 'direct-path-report.md')]: directPathReport,
      stdout: textSummary,
    };
  };
}
