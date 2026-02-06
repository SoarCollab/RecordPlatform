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
 * @returns {{lines:string[], failed:number}} 阈值汇总
 */
function collectThresholdStatus(metrics) {
  const lines = [];
  let failed = 0;

  for (const [metricName, metricData] of Object.entries(metrics || {})) {
    const thresholds = metricData.thresholds || {};
    for (const [thresholdName, thresholdResult] of Object.entries(thresholds)) {
      const ok = thresholdResult && thresholdResult.ok === true;
      if (!ok) {
        failed += 1;
      }
      lines.push(`${ok ? 'PASS' : 'FAIL'} | ${metricName} | ${thresholdName}`);
    }
  }

  return { lines, failed };
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

    return `${endpoint} -> p50=${formatMs(durationValues['p(50)'])}, p90=${formatMs(durationValues['p(90)'])}, p95=${formatMs(durationValues['p(95)'])}, error=${formatRate(errorRate)}, requests=${formatCount(stat.requests)}`;
  });
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
  lines.push(`http_req_duration: avg=${formatMs(durationValues.avg)}, p90=${formatMs(durationValues['p(90)'])}, p95=${formatMs(durationValues['p(95)'])}`);
  lines.push(`http_req_failed: rate=${failedValues.rate !== undefined ? Number(failedValues.rate).toFixed(4) : 'N/A'}`);
  lines.push(`checks: rate=${checksValues.rate !== undefined ? Number(checksValues.rate).toFixed(4) : 'N/A'}`);
  lines.push(`upload_e2e_ms: p95=${formatMs(uploadValues['p(95)'])}`);
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

    return {
      [buildResultPath(outputDir, 'summary.txt')]: textSummary,
      [buildResultPath(outputDir, 'summary.json')]: JSON.stringify(data, null, 2),
      [buildResultPath(outputDir, 'metrics.json')]: JSON.stringify(metricsSnapshot, null, 2),
      stdout: textSummary,
    };
  };
}
