#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";

const KNOWN_SEVERITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO", "UNKNOWN"];
const REVIEW_STATUS_TRUE_POSITIVE = "TRUE_POSITIVE";
const REVIEW_STATUS_FALSE_POSITIVE = "FALSE_POSITIVE";

/**
 * 解析命令行参数。
 *
 * @param {string[]} argv 原始参数列表
 * @returns {{
 *   semgrepPath:string,
 *   trivyPath:string,
 *   sbomPath:string,
 *   reviewPath:string,
 *   outputMarkdownPath:string,
 *   outputJsonPath:string
 * }} 解析后的参数
 */
function parseArgs(argv) {
  const options = {
    semgrepPath: "security-artifacts/semgrep/semgrep.json",
    trivyPath: "security-artifacts/trivy/trivy.json",
    sbomPath: "security-artifacts/sbom/sbom.cdx.json",
    reviewPath: "",
    outputMarkdownPath: "",
    outputJsonPath: "",
  };

  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    const nextValue = argv[index + 1];

    if (token === "--semgrep" && nextValue) {
      options.semgrepPath = nextValue;
      index += 1;
      continue;
    }
    if (token === "--trivy" && nextValue) {
      options.trivyPath = nextValue;
      index += 1;
      continue;
    }
    if (token === "--sbom" && nextValue) {
      options.sbomPath = nextValue;
      index += 1;
      continue;
    }
    if (token === "--review" && nextValue) {
      options.reviewPath = nextValue;
      index += 1;
      continue;
    }
    if (token === "--output-md" && nextValue) {
      options.outputMarkdownPath = nextValue;
      index += 1;
      continue;
    }
    if (token === "--output-json" && nextValue) {
      options.outputJsonPath = nextValue;
      index += 1;
      continue;
    }
    if (token === "--help") {
      printUsage(0);
    }

    throw new Error(`未知参数: ${token}`);
  }

  return options;
}

/**
 * 打印使用说明并退出。
 *
 * @param {number} exitCode 退出码
 */
function printUsage(exitCode) {
  const usage = [
    "Usage: node tools/security/scripts/render-security-poc-observation.mjs [options]",
    "",
    "Options:",
    "  --semgrep <file>       Semgrep JSON 文件路径（默认：security-artifacts/semgrep/semgrep.json）",
    "  --trivy <file>         Trivy JSON 文件路径（默认：security-artifacts/trivy/trivy.json）",
    "  --sbom <file>          CycloneDX SBOM JSON 文件路径（默认：security-artifacts/sbom/sbom.cdx.json）",
    "  --review <file>        人工复核 JSON 文件路径（可选）",
    "  --output-md <file>     Markdown 输出路径（可选）",
    "  --output-json <file>   JSON 摘要输出路径（可选）",
    "  --help                 显示帮助",
  ].join("\n");
  process.stderr.write(`${usage}\n`);
  process.exit(exitCode);
}

/**
 * 安全读取 JSON 文件；若文件不存在或解析失败则记录告警并返回 null。
 *
 * @param {string} filePath 文件路径
 * @param {string} label 数据标签
 * @param {string[]} warnings 警告收集器
 * @returns {Promise<any|null>} 解析后的对象
 */
async function readJsonIfExists(filePath, label, warnings) {
  if (!filePath) {
    return null;
  }

  const absolutePath = path.resolve(filePath);
  try {
    const rawContent = await fs.readFile(absolutePath, "utf8");
    return JSON.parse(rawContent);
  } catch (error) {
    if (error && typeof error === "object" && "code" in error && error.code === "ENOENT") {
      warnings.push(`${label} 文件不存在：${absolutePath}`);
      return null;
    }
    warnings.push(`${label} 解析失败：${absolutePath}`);
    return null;
  }
}

/**
 * 将内容写入目标文件，并自动创建父目录。
 *
 * @param {string} filePath 文件路径
 * @param {string} content 文本内容
 * @returns {Promise<void>} 写入完成
 */
async function writeTextFile(filePath, content) {
  const absolutePath = path.resolve(filePath);
  await fs.mkdir(path.dirname(absolutePath), { recursive: true });
  await fs.writeFile(absolutePath, content, "utf8");
}

/**
 * 创建统一的严重级别计数器。
 *
 * @returns {{CRITICAL:number,HIGH:number,MEDIUM:number,LOW:number,INFO:number,UNKNOWN:number}} 初始化计数器
 */
function createSeverityCounter() {
  return {
    CRITICAL: 0,
    HIGH: 0,
    MEDIUM: 0,
    LOW: 0,
    INFO: 0,
    UNKNOWN: 0,
  };
}

/**
 * 将不同工具的严重级别映射到统一枚举。
 *
 * @param {string|null|undefined} rawSeverity 原始严重级别
 * @returns {"CRITICAL"|"HIGH"|"MEDIUM"|"LOW"|"INFO"|"UNKNOWN"} 标准化级别
 */
function normalizeSeverity(rawSeverity) {
  const normalized = String(rawSeverity || "UNKNOWN").trim().toUpperCase();

  if (KNOWN_SEVERITIES.includes(normalized)) {
    return normalized;
  }
  if (normalized === "ERROR") {
    return "HIGH";
  }
  if (normalized === "WARNING" || normalized === "WARN") {
    return "MEDIUM";
  }
  if (normalized === "NOTICE") {
    return "LOW";
  }
  return "UNKNOWN";
}

/**
 * 增加严重级别计数器。
 *
 * @param {{[key:string]:number}} counter 严重级别计数器
 * @param {string} severity 严重级别
 */
function incrementSeverityCounter(counter, severity) {
  const key = normalizeSeverity(severity);
  counter[key] = (counter[key] || 0) + 1;
}

/**
 * 将输入值安全转换为数字。
 *
 * @param {unknown} value 输入值
 * @returns {number|null} 转换结果
 */
function toNumber(value) {
  const converted = Number(value);
  if (Number.isFinite(converted)) {
    return converted;
  }
  return null;
}

/**
 * 汇总 Semgrep 扫描结果。
 *
 * @param {any|null} payload Semgrep JSON
 * @returns {{
 *   scanner:string,
 *   findings:number,
 *   severity:{[key:string]:number}
 * }} 汇总结果
 */
function summarizeSemgrep(payload) {
  const severity = createSeverityCounter();
  const results = Array.isArray(payload?.results) ? payload.results : [];

  for (const item of results) {
    const itemSeverity = item?.extra?.severity ?? item?.severity;
    incrementSeverityCounter(severity, itemSeverity);
  }

  return {
    scanner: "semgrep",
    findings: results.length,
    severity,
  };
}

/**
 * 汇总 Trivy 漏洞扫描结果。
 *
 * @param {any|null} payload Trivy JSON
 * @returns {{
 *   scanner:string,
 *   findings:number,
 *   severity:{[key:string]:number}
 * }} 汇总结果
 */
function summarizeTrivy(payload) {
  const severity = createSeverityCounter();
  const resultGroups = Array.isArray(payload?.Results) ? payload.Results : [];
  let findings = 0;

  for (const group of resultGroups) {
    const vulnerabilities = Array.isArray(group?.Vulnerabilities) ? group.Vulnerabilities : [];
    findings += vulnerabilities.length;
    for (const vulnerability of vulnerabilities) {
      incrementSeverityCounter(severity, vulnerability?.Severity);
    }
  }

  return {
    scanner: "trivy",
    findings,
    severity,
  };
}

/**
 * 解析 SBOM 漏洞节点中的严重级别。
 *
 * @param {any} vulnerability CycloneDX 漏洞对象
 * @returns {string} 严重级别
 */
function resolveSbomSeverity(vulnerability) {
  const ratings = Array.isArray(vulnerability?.ratings) ? vulnerability.ratings : [];
  for (const rating of ratings) {
    if (rating?.severity) {
      return rating.severity;
    }
  }
  return vulnerability?.severity || "UNKNOWN";
}

/**
 * 汇总 CycloneDX SBOM 信息。
 *
 * @param {any|null} payload SBOM JSON
 * @returns {{
 *   scanner:string,
 *   findings:number,
 *   componentCount:number,
 *   severity:{[key:string]:number}
 * }} 汇总结果
 */
function summarizeSbom(payload) {
  const severity = createSeverityCounter();
  const vulnerabilities = Array.isArray(payload?.vulnerabilities) ? payload.vulnerabilities : [];
  const components = Array.isArray(payload?.components) ? payload.components : [];

  for (const vulnerability of vulnerabilities) {
    incrementSeverityCounter(severity, resolveSbomSeverity(vulnerability));
  }

  return {
    scanner: "sbom",
    findings: vulnerabilities.length,
    componentCount: components.length,
    severity,
  };
}

/**
 * 读取扫描 run-meta 运行元数据。
 *
 * @param {string} scanFilePath 扫描结果文件路径
 * @param {string} label 扫描标签
 * @param {string[]} warnings 警告收集器
 * @returns {Promise<{scanner:string,startAt:string|null,endAt:string|null,durationSeconds:number|null,metaPath:string}>} 元数据
 */
async function readRunMeta(scanFilePath, label, warnings) {
  const metaPath = path.join(path.dirname(path.resolve(scanFilePath)), "run-meta.json");
  const payload = await readJsonIfExists(metaPath, `${label} run-meta`, warnings);
  const durationSeconds = toNumber(payload?.durationSeconds ?? payload?.duration ?? payload?.elapsedSeconds);

  return {
    scanner: label,
    startAt: payload?.startAt ?? payload?.startTime ?? null,
    endAt: payload?.endAt ?? payload?.endTime ?? null,
    durationSeconds: durationSeconds === null ? null : Math.max(0, Math.round(durationSeconds)),
    metaPath,
  };
}

/**
 * 归一化人工复核状态。
 *
 * @param {string|null|undefined} rawStatus 原始状态
 * @returns {string} 归一化状态
 */
function normalizeReviewStatus(rawStatus) {
  return String(rawStatus || "NOT_REVIEWED").trim().toUpperCase();
}

/**
 * 提取人工复核条目列表。
 *
 * @param {any|null} reviewPayload 人工复核 JSON
 * @returns {Array<any>} 复核条目
 */
function resolveReviewEntries(reviewPayload) {
  if (!reviewPayload || typeof reviewPayload !== "object") {
    return [];
  }
  if (Array.isArray(reviewPayload.reviewEntries)) {
    return reviewPayload.reviewEntries;
  }
  if (Array.isArray(reviewPayload.reviews)) {
    return reviewPayload.reviews;
  }
  return [];
}

/**
 * 汇总人工复核数据并计算误报率。
 *
 * @param {any|null} reviewPayload 人工复核 JSON
 * @returns {{
 *   totalEntries:number,
 *   reviewedSampleCount:number,
 *   falsePositiveCount:number,
 *   falsePositiveRate:number|null,
 *   scannerBreakdown:Record<string,{reviewedSampleCount:number,falsePositiveCount:number,falsePositiveRate:number|null}>
 * }} 汇总结果
 */
function summarizeManualReview(reviewPayload) {
  const entries = resolveReviewEntries(reviewPayload);
  const scannerBreakdown = {};
  let reviewedSampleCount = 0;
  let falsePositiveCount = 0;

  for (const entry of entries) {
    const scanner = String(entry?.scanner || "unknown").trim().toLowerCase();
    const status = normalizeReviewStatus(entry?.status);

    if (!scannerBreakdown[scanner]) {
      scannerBreakdown[scanner] = {
        reviewedSampleCount: 0,
        falsePositiveCount: 0,
        falsePositiveRate: null,
      };
    }

    if (status === REVIEW_STATUS_TRUE_POSITIVE || status === REVIEW_STATUS_FALSE_POSITIVE) {
      reviewedSampleCount += 1;
      scannerBreakdown[scanner].reviewedSampleCount += 1;
    }

    if (status === REVIEW_STATUS_FALSE_POSITIVE) {
      falsePositiveCount += 1;
      scannerBreakdown[scanner].falsePositiveCount += 1;
    }
  }

  for (const scanner of Object.keys(scannerBreakdown)) {
    const scannerSummary = scannerBreakdown[scanner];
    if (scannerSummary.reviewedSampleCount > 0) {
      scannerSummary.falsePositiveRate =
        scannerSummary.falsePositiveCount / scannerSummary.reviewedSampleCount;
    }
  }

  return {
    totalEntries: entries.length,
    reviewedSampleCount,
    falsePositiveCount,
    falsePositiveRate:
      reviewedSampleCount === 0 ? null : falsePositiveCount / reviewedSampleCount,
    scannerBreakdown,
  };
}

/**
 * 合并多个严重级别计数器。
 *
 * @param {Array<{[key:string]:number}>} counters 计数器列表
 * @returns {{[key:string]:number}} 合并后的计数器
 */
function mergeSeverityCounters(counters) {
  const merged = createSeverityCounter();
  for (const counter of counters) {
    for (const severity of KNOWN_SEVERITIES) {
      merged[severity] += Number(counter?.[severity] || 0);
    }
  }
  return merged;
}

/**
 * 将秒数格式化为可读文本。
 *
 * @param {number|null} durationSeconds 秒数
 * @returns {string} 格式化文本
 */
function formatDuration(durationSeconds) {
  if (durationSeconds === null || durationSeconds === undefined) {
    return "N/A";
  }
  return `${durationSeconds}s`;
}

/**
 * 将时间文本格式化为展示值。
 *
 * @param {string|null} dateText ISO 时间文本
 * @returns {string} 展示值
 */
function formatDate(dateText) {
  if (!dateText) {
    return "N/A";
  }
  return dateText;
}

/**
 * 将比率格式化为百分比文本。
 *
 * @param {number|null} rate 比率（0~1）
 * @returns {string} 百分比文本
 */
function formatRate(rate) {
  if (rate === null || rate === undefined) {
    return "N/A";
  }
  return `${(rate * 100).toFixed(2)}%`;
}

/**
 * 生成 W5 阻断阈值建议。
 *
 * @param {{
 *   severityTotals:{[key:string]:number},
 *   reviewSummary:{reviewedSampleCount:number,falsePositiveRate:number|null},
 *   runMeta:{semgrep:any,trivy:any,sbom:any}
 * }} context 汇总上下文
 * @returns {string[]} 建议列表
 */
function buildThresholdSuggestions(context) {
  const criticalCount = context.severityTotals.CRITICAL;
  const highCount = context.severityTotals.HIGH;
  const reviewedSampleCount = context.reviewSummary.reviewedSampleCount;
  const falsePositiveRate = context.reviewSummary.falsePositiveRate;
  const durations = [context.runMeta.semgrep, context.runMeta.trivy, context.runMeta.sbom]
    .map((item) => item.durationSeconds)
    .filter((item) => item !== null && item !== undefined);
  const maxDuration = durations.length === 0 ? null : Math.max(...durations);

  const suggestions = [];
  suggestions.push(
    criticalCount > 0
      ? `建议阈值 1：W5 将“CRITICAL > 0”设为发布阻断（当前观测=${criticalCount}）。`
      : "建议阈值 1：W5 将“CRITICAL > 0”设为发布阻断（当前观测为 0，可继续验证稳定性）。",
  );
  suggestions.push(
    `建议阈值 2：W5 将“HIGH > 10 且连续两轮未下降”设为阻断升级条件（当前 HIGH=${highCount}）。`,
  );

  if (reviewedSampleCount < 20) {
    suggestions.push(
      `建议阈值 3：当前人工复核样本仅 ${reviewedSampleCount} 条，先保持信息级，补齐至少 20 条再锁定误报率阈值。`,
    );
  } else {
    suggestions.push(
      `建议阈值 3：误报率阈值可设为 <=20%（当前观测=${formatRate(falsePositiveRate)}）。`,
    );
  }

  if (maxDuration === null) {
    suggestions.push("建议阈值 4：先连续采集两轮扫描耗时，再确定 W5 的时长预算门槛。");
  } else {
    suggestions.push(
      `建议阈值 4：扫描总时长预算建议 <=20 分钟（当前单轮最大 job=${formatDuration(maxDuration)}）。`,
    );
  }

  return suggestions;
}

/**
 * 构建 Markdown 观测报告。
 *
 * @param {{
 *   generatedAt:string,
 *   summaries:{semgrep:any,trivy:any,sbom:any},
 *   runMeta:{semgrep:any,trivy:any,sbom:any},
 *   severityTotals:{[key:string]:number},
 *   reviewSummary:any,
 *   thresholdSuggestions:string[],
 *   warnings:string[]
 * }} context 汇总上下文
 * @returns {string} Markdown 报告
 */
function buildMarkdownReport(context) {
  const lines = [];
  lines.push("# Security PoC Observation Report");
  lines.push("");
  lines.push(`- 生成时间（UTC）：${context.generatedAt}`);
  lines.push("- 口径：信息级观测，不触发 PR/发布阻断");
  lines.push("");

  lines.push("## 1. 告警分布");
  lines.push("");
  lines.push("| 扫描器 | 总告警数 | CRITICAL | HIGH | MEDIUM | LOW | INFO | UNKNOWN | 备注 |");
  lines.push("|---|---:|---:|---:|---:|---:|---:|---:|---|");
  lines.push(
    `| Semgrep | ${context.summaries.semgrep.findings} | ${context.summaries.semgrep.severity.CRITICAL} | ${context.summaries.semgrep.severity.HIGH} | ${context.summaries.semgrep.severity.MEDIUM} | ${context.summaries.semgrep.severity.LOW} | ${context.summaries.semgrep.severity.INFO} | ${context.summaries.semgrep.severity.UNKNOWN} | SAST 规则命中 |`,
  );
  lines.push(
    `| Trivy | ${context.summaries.trivy.findings} | ${context.summaries.trivy.severity.CRITICAL} | ${context.summaries.trivy.severity.HIGH} | ${context.summaries.trivy.severity.MEDIUM} | ${context.summaries.trivy.severity.LOW} | ${context.summaries.trivy.severity.INFO} | ${context.summaries.trivy.severity.UNKNOWN} | 依赖漏洞命中 |`,
  );
  lines.push(
    `| SBOM | ${context.summaries.sbom.findings} | ${context.summaries.sbom.severity.CRITICAL} | ${context.summaries.sbom.severity.HIGH} | ${context.summaries.sbom.severity.MEDIUM} | ${context.summaries.sbom.severity.LOW} | ${context.summaries.sbom.severity.INFO} | ${context.summaries.sbom.severity.UNKNOWN} | 组件数=${context.summaries.sbom.componentCount} |`,
  );
  lines.push(
    `| 合计 | ${context.summaries.semgrep.findings + context.summaries.trivy.findings + context.summaries.sbom.findings} | ${context.severityTotals.CRITICAL} | ${context.severityTotals.HIGH} | ${context.severityTotals.MEDIUM} | ${context.severityTotals.LOW} | ${context.severityTotals.INFO} | ${context.severityTotals.UNKNOWN} | - |`,
  );
  lines.push("");

  lines.push("## 2. 扫描耗时");
  lines.push("");
  lines.push("| 扫描器 | startAt | endAt | durationSeconds | 元数据路径 |");
  lines.push("|---|---|---|---:|---|");
  lines.push(
    `| Semgrep | ${formatDate(context.runMeta.semgrep.startAt)} | ${formatDate(context.runMeta.semgrep.endAt)} | ${formatDuration(context.runMeta.semgrep.durationSeconds)} | \`${context.runMeta.semgrep.metaPath}\` |`,
  );
  lines.push(
    `| Trivy | ${formatDate(context.runMeta.trivy.startAt)} | ${formatDate(context.runMeta.trivy.endAt)} | ${formatDuration(context.runMeta.trivy.durationSeconds)} | \`${context.runMeta.trivy.metaPath}\` |`,
  );
  lines.push(
    `| SBOM | ${formatDate(context.runMeta.sbom.startAt)} | ${formatDate(context.runMeta.sbom.endAt)} | ${formatDuration(context.runMeta.sbom.durationSeconds)} | \`${context.runMeta.sbom.metaPath}\` |`,
  );
  lines.push("");

  lines.push("## 3. 人工复核与误报率");
  lines.push("");
  lines.push(`- 复核条目总数：${context.reviewSummary.totalEntries}`);
  lines.push(`- 参与误报口径样本数（TRUE_POSITIVE + FALSE_POSITIVE）：${context.reviewSummary.reviewedSampleCount}`);
  lines.push(`- 误报数：${context.reviewSummary.falsePositiveCount}`);
  lines.push(`- 误报率：${formatRate(context.reviewSummary.falsePositiveRate)}`);
  lines.push("");

  const scanners = Object.keys(context.reviewSummary.scannerBreakdown).sort();
  if (scanners.length > 0) {
    lines.push("| 扫描器 | 样本数 | 误报数 | 误报率 |");
    lines.push("|---|---:|---:|---:|");
    for (const scanner of scanners) {
      const scannerSummary = context.reviewSummary.scannerBreakdown[scanner];
      lines.push(
        `| ${scanner} | ${scannerSummary.reviewedSampleCount} | ${scannerSummary.falsePositiveCount} | ${formatRate(scannerSummary.falsePositiveRate)} |`,
      );
    }
    lines.push("");
  }

  lines.push("## 4. W5 阻断阈值建议");
  lines.push("");
  for (const suggestion of context.thresholdSuggestions) {
    lines.push(`- ${suggestion}`);
  }
  lines.push("");

  if (context.warnings.length > 0) {
    lines.push("## 5. 观测告警");
    lines.push("");
    for (const warning of context.warnings) {
      lines.push(`- ${warning}`);
    }
    lines.push("");
  }

  return `${lines.join("\n")}\n`;
}

/**
 * 构建 JSON 摘要对象。
 *
 * @param {{
 *   generatedAt:string,
 *   sources:{semgrepPath:string,trivyPath:string,sbomPath:string,reviewPath:string|null},
 *   summaries:{semgrep:any,trivy:any,sbom:any},
 *   runMeta:{semgrep:any,trivy:any,sbom:any},
 *   severityTotals:{[key:string]:number},
 *   reviewSummary:any,
 *   thresholdSuggestions:string[],
 *   warnings:string[]
 * }} context 汇总上下文
 * @returns {Record<string, any>} JSON 摘要
 */
function buildJsonSummary(context) {
  return {
    generatedAt: context.generatedAt,
    sources: context.sources,
    scans: {
      semgrep: {
        ...context.summaries.semgrep,
        runMeta: context.runMeta.semgrep,
      },
      trivy: {
        ...context.summaries.trivy,
        runMeta: context.runMeta.trivy,
      },
      sbom: {
        ...context.summaries.sbom,
        runMeta: context.runMeta.sbom,
      },
    },
    totals: {
      findings:
        context.summaries.semgrep.findings
        + context.summaries.trivy.findings
        + context.summaries.sbom.findings,
      severity: context.severityTotals,
    },
    review: context.reviewSummary,
    thresholdSuggestions: context.thresholdSuggestions,
    warnings: context.warnings,
  };
}

/**
 * 脚本入口：读取扫描结果并输出观测报告。
 */
async function main() {
  const options = parseArgs(process.argv.slice(2));
  const warnings = [];

  const semgrepPayload = await readJsonIfExists(options.semgrepPath, "Semgrep JSON", warnings);
  const trivyPayload = await readJsonIfExists(options.trivyPath, "Trivy JSON", warnings);
  const sbomPayload = await readJsonIfExists(options.sbomPath, "SBOM JSON", warnings);
  const reviewPayload = options.reviewPath
    ? await readJsonIfExists(options.reviewPath, "人工复核 JSON", warnings)
    : null;

  const summaries = {
    semgrep: summarizeSemgrep(semgrepPayload),
    trivy: summarizeTrivy(trivyPayload),
    sbom: summarizeSbom(sbomPayload),
  };
  const runMeta = {
    semgrep: await readRunMeta(options.semgrepPath, "semgrep", warnings),
    trivy: await readRunMeta(options.trivyPath, "trivy", warnings),
    sbom: await readRunMeta(options.sbomPath, "sbom", warnings),
  };
  const severityTotals = mergeSeverityCounters([
    summaries.semgrep.severity,
    summaries.trivy.severity,
    summaries.sbom.severity,
  ]);
  const reviewSummary = summarizeManualReview(reviewPayload);
  const generatedAt = new Date().toISOString();

  const context = {
    generatedAt,
    sources: {
      semgrepPath: path.resolve(options.semgrepPath),
      trivyPath: path.resolve(options.trivyPath),
      sbomPath: path.resolve(options.sbomPath),
      reviewPath: options.reviewPath ? path.resolve(options.reviewPath) : null,
    },
    summaries,
    runMeta,
    severityTotals,
    reviewSummary,
    thresholdSuggestions: buildThresholdSuggestions({
      severityTotals,
      reviewSummary,
      runMeta,
    }),
    warnings,
  };

  const markdown = buildMarkdownReport(context);
  const jsonSummary = buildJsonSummary(context);

  process.stdout.write(markdown);

  if (options.outputMarkdownPath) {
    await writeTextFile(options.outputMarkdownPath, markdown);
  }
  if (options.outputJsonPath) {
    await writeTextFile(options.outputJsonPath, `${JSON.stringify(jsonSummary, null, 2)}\n`);
  }
}

main().catch((error) => {
  process.stderr.write(
    `[render-security-poc-observation] 执行失败: ${error instanceof Error ? error.message : String(error)}\n`,
  );
  process.exit(1);
});
