#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";

/**
 * 解析命令行参数。
 *
 * @param {string[]} argv 原始参数列表。
 * @returns {{smokeDir:string, loadDir:string, outputPath:string|null}} 解析结果。
 */
function parseArgs(argv) {
  const options = {
    smokeDir: "",
    loadDir: "",
    outputPath: null,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    const nextValue = argv[index + 1];
    if (token === "--smoke-dir" && nextValue) {
      options.smokeDir = nextValue;
      index += 1;
      continue;
    }
    if (token === "--load-dir" && nextValue) {
      options.loadDir = nextValue;
      index += 1;
      continue;
    }
    if (token === "--output" && nextValue) {
      options.outputPath = nextValue;
      index += 1;
      continue;
    }
    if (token === "--help") {
      printUsage(0);
    }
  }

  if (!options.smokeDir || !options.loadDir) {
    printUsage(1);
  }

  return options;
}

/**
 * 打印脚本使用说明并退出。
 *
 * @param {number} exitCode 退出码。
 */
function printUsage(exitCode) {
  const usage = [
    "Usage: node tools/k6/scripts/render-query-baseline.mjs --smoke-dir <dir> --load-dir <dir> [--output <file>]",
    "",
    "Options:",
    "  --smoke-dir <dir>   smoke 结果目录（包含 query-baseline.json）",
    "  --load-dir <dir>    load 结果目录（包含 query-baseline.json）",
    "  --output <file>     输出 Markdown 文件路径（可选）",
    "  --help              显示帮助",
  ].join("\n");
  process.stderr.write(`${usage}\n`);
  process.exit(exitCode);
}

/**
 * 读取 JSON 文件并解析为对象。
 *
 * @param {string} filePath JSON 文件路径。
 * @returns {Promise<any>} 解析后的 JSON 对象。
 */
async function readJsonFile(filePath) {
  const content = await fs.readFile(filePath, "utf8");
  return JSON.parse(content);
}

/**
 * 将数值格式化为毫秒文本。
 *
 * @param {number|null|undefined} value 指标值。
 * @returns {string} 格式化结果。
 */
function formatMs(value) {
  if (value === null || value === undefined) {
    return "N/A";
  }
  return `${Number(value).toFixed(2)}ms`;
}

/**
 * 将错误率格式化为百分比文本。
 *
 * @param {number|null|undefined} value 错误率（0~1）。
 * @returns {string} 百分比文本。
 */
function formatRate(value) {
  if (value === null || value === undefined) {
    return "N/A";
  }
  return `${(Number(value) * 100).toFixed(2)}%`;
}

/**
 * 计算阈值结论文本。
 *
 * @param {{thresholdFailedCount:number}} baselineSnapshot 基线快照。
 * @returns {string} 结论文本。
 */
function resolveThresholdConclusion(baselineSnapshot) {
  return baselineSnapshot.thresholdFailedCount > 0
    ? `未通过（失败 ${baselineSnapshot.thresholdFailedCount} 项）`
    : "通过";
}

/**
 * 构建“结果回填模板”表格行。
 *
 * @param {"smoke"|"load"} profileLabel 档位标签。
 * @param {string} resultDir 结果目录。
 * @param {{runId:string, thresholdFailedCount:number}} baselineSnapshot 基线快照。
 * @returns {string} Markdown 表格行。
 */
function buildRunTableRow(profileLabel, resultDir, baselineSnapshot) {
  const resultPath = path.relative(process.cwd(), resultDir) || ".";
  const conclusion = resolveThresholdConclusion(baselineSnapshot);
  return `| ${profileLabel} | \`${baselineSnapshot.runId}\` | \`${resultPath}\` | ${conclusion} | 由 \`query-baseline.json\` 自动汇总 |`;
}

/**
 * 构建“指标摘录”表格行。
 *
 * @param {"smoke"|"load"} profileLabel 档位标签。
 * @param {{endpoints:Record<string, {p50:number|null, p90:number|null, p95:number|null, errorRate:number|null, requests:number}>}} baselineSnapshot 基线快照。
 * @returns {string[]} Markdown 表格行列表。
 */
function buildEndpointRows(profileLabel, baselineSnapshot) {
  const rows = [];
  const endpoints = Object.keys(baselineSnapshot.endpoints || {}).sort();
  for (const endpoint of endpoints) {
    const endpointStat = baselineSnapshot.endpoints[endpoint];
    rows.push(
      `| ${profileLabel} | ${endpoint} | ${formatMs(endpointStat.p50)} | ${formatMs(endpointStat.p90)} | ${formatMs(endpointStat.p95)} | ${formatRate(endpointStat.errorRate)} | ${endpointStat.requests} |`,
    );
  }
  return rows;
}

/**
 * 构建完整 Markdown 汇总内容。
 *
 * @param {string} smokeDir smoke 结果目录。
 * @param {string} loadDir load 结果目录。
 * @param {any} smokeBaseline smoke 基线快照。
 * @param {any} loadBaseline load 基线快照。
 * @returns {string} Markdown 文本。
 */
function buildMarkdown(smokeDir, loadDir, smokeBaseline, loadBaseline) {
  const runRows = [
    buildRunTableRow("smoke", smokeDir, smokeBaseline),
    buildRunTableRow("load", loadDir, loadBaseline),
  ];
  const endpointRows = [
    ...buildEndpointRows("smoke", smokeBaseline),
    ...buildEndpointRows("load", loadBaseline),
  ];

  const lines = [];
  lines.push("## 4. 结果回填模板（自动汇总）");
  lines.push("");
  lines.push("| 运行档位 | RUN_ID | 结果路径 | 阈值结论 | 备注 |");
  lines.push("|---|---|---|---|---|");
  lines.push(...runRows);
  lines.push("");
  lines.push("## 5. 指标摘录（自动汇总）");
  lines.push("");
  lines.push("| 运行档位 | endpoint | p50 | p90 | p95 | errorRate | requests |");
  lines.push("|---|---|---|---|---|---|---|");
  if (endpointRows.length === 0) {
    lines.push("| - | - | N/A | N/A | N/A | N/A | 0 |");
  } else {
    lines.push(...endpointRows);
  }
  lines.push("");
  lines.push(`> 生成时间（UTC）：${new Date().toISOString()}`);
  return `${lines.join("\n")}\n`;
}

/**
 * 脚本入口：读取 smoke/load 基线快照并输出 Markdown。
 */
async function main() {
  const options = parseArgs(process.argv.slice(2));
  const smokeDir = path.resolve(options.smokeDir);
  const loadDir = path.resolve(options.loadDir);

  const smokeBaseline = await readJsonFile(path.join(smokeDir, "query-baseline.json"));
  const loadBaseline = await readJsonFile(path.join(loadDir, "query-baseline.json"));
  const markdown = buildMarkdown(smokeDir, loadDir, smokeBaseline, loadBaseline);

  process.stdout.write(markdown);

  if (options.outputPath) {
    const outputFile = path.resolve(options.outputPath);
    await fs.mkdir(path.dirname(outputFile), { recursive: true });
    await fs.writeFile(outputFile, markdown, "utf8");
  }
}

main().catch((error) => {
  process.stderr.write(`[render-query-baseline] 执行失败: ${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
