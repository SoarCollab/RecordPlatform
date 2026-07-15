"use strict";

const form = document.getElementById("verify-form");
const submit = document.getElementById("submit");
const requestError = document.getElementById("request-error");
const result = document.getElementById("result");
const outcome = document.getElementById("outcome");
const outcomeHelp = document.getElementById("outcome-help");
const summary = document.getElementById("summary");
const checks = document.getElementById("checks");
const checkCount = document.getElementById("check-count");
const reportMeta = document.getElementById("report-meta");

const labels = [
    ["proofId", "Proof ID"],
    ["fileId", "文件 ID"],
    ["fileVersion", "文件版本"],
    ["issuedStatus", "签发状态"],
    ["currentStatus", "当前状态"],
    ["keyId", "签名密钥"],
    ["keyVersion", "密钥版本"],
    ["keySource", "签发者信任来源"],
    ["contentHash", "内容哈希"],
    ["merkleRoot", "Merkle Root"],
    ["chainType", "链类型"],
    ["chainId", "链 ID"],
    ["batchTransactionHash", "交易哈希"],
    ["liveBlockNumber", "实时区块"],
    ["contractAddress", "合约地址"],
    ["contractVersion", "合约版本"],
    ["abiFingerprint", "ABI 指纹"],
    ["liveChainSource", "链上证据来源"]
];

const outcomeDescriptions = {
    VALID: "所有本地证据、可信签名、当前状态和实时链上根均已通过。",
    INVALID: "至少一项确定性验证失败；请查看失败检查项，不要依赖该证明。",
    INDETERMINATE: "至少一项可信依赖不可用或未配置；这不是验证成功。",
    ERROR: "验证器无法安全完成处理；请查看错误检查项或联系实例运维方。"
};

/** Submits multipart bytes and renders only server-produced structured text. */
form.addEventListener("submit", async (event) => {
    event.preventDefault();
    clearError();
    setBusy(true);
    try {
        const payload = new FormData();
        payload.append("original", document.getElementById("original").files[0]);
        payload.append("proof", document.getElementById("proof").files[0]);
        const key = document.getElementById("trusted-key").files[0];
        if (key) {
            payload.append("trustedKey", key);
        }
        const response = await fetch("/api/v1/verify", {
            method: "POST",
            body: payload,
            headers: {"Accept": "application/json"},
            credentials: "same-origin"
        });
        const body = await parseJson(response);
        if (!response.ok) {
            throw new Error(body.message || `验证请求失败（HTTP ${response.status}）`);
        }
        renderReport(body);
    } catch (error) {
        showError(error instanceof Error ? error.message : "验证请求失败");
    } finally {
        setBusy(false);
    }
});

/** Parses JSON while converting an invalid server response into a safe local error. */
async function parseJson(response) {
    try {
        return await response.json();
    } catch (error) {
        throw new Error(`验证器返回了无效响应（HTTP ${response.status}）`);
    }
}

/** Renders one report without assigning untrusted strings as HTML. */
function renderReport(report) {
    const safeOutcome = ["VALID", "INVALID", "INDETERMINATE", "ERROR"].includes(report.outcome)
        ? report.outcome
        : "ERROR";
    outcome.textContent = safeOutcome;
    outcome.className = `outcome ${safeOutcome.toLowerCase()}`;
    outcomeHelp.textContent = outcomeDescriptions[safeOutcome];
    renderSummary(report.summary || {});
    renderChecks(Array.isArray(report.checks) ? report.checks : []);
    reportMeta.textContent = `报告 ${value(report.schemaVersion)} · 验证器 ${value(report.verifierVersion)} · ${value(report.verifiedAt)}`;
    result.hidden = false;
    result.scrollIntoView({behavior: "smooth", block: "start"});
}

/** Builds summary description pairs from a fixed allowlist of report fields. */
function renderSummary(data) {
    summary.replaceChildren();
    labels.forEach(([field, label]) => {
        const wrapper = document.createElement("div");
        wrapper.className = "summary-item";
        const term = document.createElement("dt");
        term.textContent = label;
        const detail = document.createElement("dd");
        detail.textContent = value(data[field]);
        wrapper.append(term, detail);
        summary.append(wrapper);
    });
}

/** Builds the ordered verification table using text-only DOM nodes. */
function renderChecks(items) {
    checks.replaceChildren();
    items.forEach((check) => {
        const row = document.createElement("tr");
        const status = document.createElement("td");
        const statusText = document.createElement("span");
        const normalizedStatus = ["PASS", "FAIL", "INDETERMINATE", "ERROR"].includes(check.status)
            ? check.status
            : "ERROR";
        statusText.className = `check-status ${normalizedStatus.toLowerCase()}`;
        statusText.textContent = normalizedStatus;
        status.append(statusText);
        row.append(status, cell(check.id), cell(check.code), cell(check.message));
        checks.append(row);
    });
    checkCount.textContent = `${items.length} 项`;
}

/** Creates one plain-text table cell. */
function cell(content) {
    const item = document.createElement("td");
    item.textContent = value(content);
    return item;
}

/** Normalizes absent values for human display. */
function value(content) {
    return content === null || content === undefined || content === "" ? "未提供" : String(content);
}

/** Toggles submit state without disabling browser-side required-field validation. */
function setBusy(busy) {
    submit.disabled = busy;
    submit.textContent = busy ? "正在流式核验…" : "开始验证";
}

/** Displays a bounded request failure and hides a stale report. */
function showError(message) {
    requestError.textContent = String(message).slice(0, 500);
    requestError.hidden = false;
    result.hidden = true;
}

/** Clears the request failure before the next submission. */
function clearError() {
    requestError.textContent = "";
    requestError.hidden = true;
}
