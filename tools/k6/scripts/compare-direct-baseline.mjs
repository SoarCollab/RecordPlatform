import fs from 'node:fs';
import path from 'node:path';

/**
 * 读取并解析直传基线 JSON。
 *
 * @param {string} filePath 文件路径
 * @returns {Record<string, any>} 基线对象
 */
function readBaseline(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

/**
 * 解析命令行参数。
 *
 * @returns {{baseline:string,candidate:string,output:string|null,threshold:number}} 参数
 */
function parseArgs() {
  const result = {
    baseline: '',
    candidate: '',
    output: null,
    threshold: 20,
  };
  for (let index = 2; index < process.argv.length; index += 1) {
    const name = process.argv[index];
    const value = process.argv[index + 1];
    if (name === '--baseline') {
      result.baseline = value;
      index += 1;
    } else if (name === '--candidate') {
      result.candidate = value;
      index += 1;
    } else if (name === '--output') {
      result.output = value;
      index += 1;
    } else if (name === '--threshold-percent') {
      result.threshold = Number(value);
      index += 1;
    } else {
      throw new Error(`未知参数: ${name}`);
    }
  }
  if (!result.baseline || !result.candidate) {
    throw new Error('必须提供 --baseline 和 --candidate');
  }
  if (!Number.isFinite(result.threshold) || result.threshold < 0) {
    throw new Error('--threshold-percent 必须是非负数');
  }
  return result;
}

/**
 * 计算候选值相对基线的百分比变化。
 *
 * @param {number|null|undefined} baseline 基线值
 * @param {number|null|undefined} candidate 候选值
 * @returns {number|null} 百分比变化
 */
function percentChange(baseline, candidate) {
  if (!Number.isFinite(baseline) || !Number.isFinite(candidate) || baseline <= 0) {
    return null;
  }
  return ((candidate - baseline) / baseline) * 100;
}

/**
 * 提取嵌套数值。
 *
 * @param {Record<string, any>} source 对象
 * @param {string[]} keys 路径
 * @returns {number|null} 数值
 */
function readNumber(source, keys) {
  let value = source;
  for (const key of keys) {
    value = value?.[key];
  }
  return Number.isFinite(value) ? Number(value) : null;
}

/**
 * 提取决定性能可比性的环境与负载合同。
 *
 * @param {Record<string, any>} source 基线对象
 * @returns {Record<string, any>} 可比性合同
 */
function readCompatibilityContract(source) {
  return {
    schemaVersion: source.schemaVersion ?? null,
    profile: source.profile ?? null,
    scenario: source.scenario ?? null,
    engine: source.environment?.engine ?? null,
    engineArtifact: source.environment?.engineArtifact ?? null,
    workload: {
      totalChunks: readNumber(source, ['workload', 'totalChunks']),
      chunkSize: readNumber(source, ['workload', 'chunkSize']),
    },
    execution: {
      executor: source.execution?.executor ?? null,
      concurrency: readNumber(source, ['execution', 'concurrency']),
      duration: source.execution?.duration ?? null,
      preAllocatedVUs: source.execution?.preAllocatedVUs ?? null,
      maxVUs: source.execution?.maxVUs ?? null,
    },
  };
}

/**
 * 独立复核基线的必需样本与指标，禁止信任可被手工篡改的 evidence.valid 标记。
 *
 * @param {Record<string, any>} source 基线对象
 * @returns {boolean} 证据是否完整
 */
function hasCompleteEvidence(source) {
  const requiredNumbers = [];
  for (const phase of ['upload', 'download', 'endToEnd']) {
    for (const percentile of ['p95', 'p99']) {
      requiredNumbers.push(readNumber(source, ['latencyMs', phase, percentile]));
    }
  }
  requiredNumbers.push(
    readNumber(source, ['throughput', 'uploadedBytesPerSecond']),
    readNumber(source, ['throughput', 'downloadedBytesPerSecond']),
    readNumber(source, ['failure', 'flowRate']),
    readNumber(source, ['failure', 'cleanupRate']),
  );
  return source.evidence?.valid === true &&
    source.evidence?.metricsComplete === true &&
    source.evidence?.compatibilityComplete === true &&
    Number(source.evidence?.flowSamples) > 0 &&
    Number(source.evidence?.cleanupSamples) > 0 &&
    Number(source.evidence?.completedFiles) > 0 &&
    requiredNumbers.every((value) => Number.isFinite(value));
}

/**
 * 比较一项“越小越好”的时延指标。
 *
 * @param {string} label 指标名
 * @param {number|null} baseline 基线值
 * @param {number|null} candidate 候选值
 * @param {number} threshold 阈值百分比
 * @returns {{line:string,regression:boolean}} 比较结果
 */
function compareLatency(label, baseline, candidate, threshold) {
  const delta = percentChange(baseline, candidate);
  const regression = delta !== null && delta > threshold;
  return {
    line: `| ${label} | ${baseline ?? 'N/A'} | ${candidate ?? 'N/A'} | ${delta === null ? 'N/A' : `${delta.toFixed(2)}%`} | ${regression ? 'REGRESSION' : 'OK'} |`,
    regression,
  };
}

/**
 * 比较一项“越大越好”的吞吐指标。
 *
 * @param {string} label 指标名
 * @param {number|null} baseline 基线值
 * @param {number|null} candidate 候选值
 * @param {number} threshold 阈值百分比
 * @returns {{line:string,regression:boolean}} 比较结果
 */
function compareThroughput(label, baseline, candidate, threshold) {
  const delta = percentChange(baseline, candidate);
  const regression = delta !== null && delta < -threshold;
  return {
    line: `| ${label} | ${baseline ?? 'N/A'} | ${candidate ?? 'N/A'} | ${delta === null ? 'N/A' : `${delta.toFixed(2)}%`} | ${regression ? 'REGRESSION' : 'OK'} |`,
    regression,
  };
}

/**
 * 比较同环境直传基线并返回 Markdown 与退出码。
 *
 * @param {Record<string, any>} baseline 基线
 * @param {Record<string, any>} candidate 候选
 * @param {number} threshold 阈值百分比
 * @returns {{report:string,exitCode:number}} 比较结果
 */
function compareBaselines(baseline, candidate, threshold) {
  const baselineEvidenceValid = hasCompleteEvidence(baseline);
  const candidateEvidenceValid = hasCompleteEvidence(candidate);
  if (!baselineEvidenceValid || !candidateEvidenceValid) {
    return {
      report: [
        '# Direct path baseline comparison',
        '',
        'Result: INVALID_EVIDENCE',
        '',
        `- Baseline evidence valid: ${baselineEvidenceValid}`,
        `- Candidate evidence valid: ${candidateEvidenceValid}`,
        '',
      ].join('\n'),
      exitCode: 1,
    };
  }
  const baselineFingerprint = baseline.environment?.fingerprint;
  const candidateFingerprint = candidate.environment?.fingerprint;
  const baselineContract = readCompatibilityContract(baseline);
  const candidateContract = readCompatibilityContract(candidate);
  const contractMatches = JSON.stringify(baselineContract) === JSON.stringify(candidateContract);
  if (!baselineFingerprint || baselineFingerprint !== candidateFingerprint || !contractMatches) {
    return {
      report: [
        '# Direct path baseline comparison',
        '',
        'Result: NOT_COMPARABLE',
        '',
        `- Baseline fingerprint: \`${baselineFingerprint || 'unavailable'}\``,
        `- Candidate fingerprint: \`${candidateFingerprint || 'unavailable'}\``,
        `- Environment fingerprint match: ${baselineFingerprint === candidateFingerprint}`,
        `- Workload/execution contract match: ${contractMatches}`,
        `- Baseline contract: \`${JSON.stringify(baselineContract)}\``,
        `- Candidate contract: \`${JSON.stringify(candidateContract)}\``,
        '- 不同环境、运行引擎、并发、时长或分片负载不会给出虚假性能回归结论。',
        '',
      ].join('\n'),
      exitCode: 0,
    };
  }

  const comparisons = [];
  for (const phase of ['upload', 'download', 'endToEnd']) {
    for (const percentile of ['p95', 'p99']) {
      comparisons.push(compareLatency(
        `${phase}.${percentile}`,
        readNumber(baseline, ['latencyMs', phase, percentile]),
        readNumber(candidate, ['latencyMs', phase, percentile]),
        threshold,
      ));
    }
  }
  comparisons.push(compareThroughput(
    'uploadedBytesPerSecond',
    readNumber(baseline, ['throughput', 'uploadedBytesPerSecond']),
    readNumber(candidate, ['throughput', 'uploadedBytesPerSecond']),
    threshold,
  ));
  comparisons.push(compareThroughput(
    'downloadedBytesPerSecond',
    readNumber(baseline, ['throughput', 'downloadedBytesPerSecond']),
    readNumber(candidate, ['throughput', 'downloadedBytesPerSecond']),
    threshold,
  ));

  const baselineFlow = readNumber(baseline, ['failure', 'flowRate']);
  const candidateFlow = readNumber(candidate, ['failure', 'flowRate']);
  const baselineCleanup = readNumber(baseline, ['failure', 'cleanupRate']);
  const candidateCleanup = readNumber(candidate, ['failure', 'cleanupRate']);
  const flowRegression = baselineFlow === 0 && candidateFlow !== null && candidateFlow > 0;
  const cleanupRegression = baselineCleanup === 0 && candidateCleanup !== null && candidateCleanup > 0;
  comparisons.push({
    line: `| flowFailureRate | ${baselineFlow ?? 'N/A'} | ${candidateFlow ?? 'N/A'} | N/A | ${flowRegression ? 'REGRESSION' : 'OK'} |`,
    regression: flowRegression,
  });
  comparisons.push({
    line: `| cleanupFailureRate | ${baselineCleanup ?? 'N/A'} | ${candidateCleanup ?? 'N/A'} | N/A | ${cleanupRegression ? 'REGRESSION' : 'OK'} |`,
    regression: cleanupRegression,
  });

  const regressions = comparisons.filter((item) => item.regression).length;
  return {
    report: [
      '# Direct path baseline comparison',
      '',
      `Result: ${regressions === 0 ? 'PASS' : 'FAIL'}`,
      '',
      `- Environment fingerprint: \`${baselineFingerprint}\``,
      `- Regression threshold: ${threshold}%`,
      '',
      '| Metric | Baseline | Candidate | Change | Result |',
      '|---|---:|---:|---:|---|',
      ...comparisons.map((item) => item.line),
      '',
    ].join('\n'),
    exitCode: regressions === 0 ? 0 : 1,
  };
}

const args = parseArgs();
const result = compareBaselines(readBaseline(args.baseline), readBaseline(args.candidate), args.threshold);
if (args.output) {
  fs.mkdirSync(path.dirname(args.output), { recursive: true });
  fs.writeFileSync(args.output, result.report, 'utf8');
}
process.stdout.write(result.report);
process.exitCode = result.exitCode;
