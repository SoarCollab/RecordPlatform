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
 * 直传上传阶段耗时（毫秒）。
 */
export const directUploadE2eMs = new Trend('direct_upload_e2e_ms', true);

/**
 * 直传下载校验阶段耗时（毫秒）。
 */
export const directDownloadE2eMs = new Trend('direct_download_e2e_ms', true);

/**
 * 直传创建到下载校验完成的全链路耗时（毫秒）。
 */
export const directPathE2eMs = new Trend('direct_path_e2e_ms', true);

/**
 * 直传流程失败率。
 */
export const directFlowFailureRate = new Rate('direct_flow_failure_rate');

/**
 * 直传清理失败率。
 */
export const directCleanupFailureRate = new Rate('direct_cleanup_failure_rate');

/**
 * 直传上传字节数。
 */
export const directUploadedBytes = new Counter('direct_uploaded_bytes');

/**
 * 直传下载并校验通过的字节数。
 */
export const directDownloadedBytes = new Counter('direct_downloaded_bytes');

/**
 * 完成直传上传并下载校验的文件数。
 */
export const directFileCount = new Counter('direct_file_count');

/**
 * 目标资源快照可用率。
 */
export const directResourceSnapshotStartAvailability = new Rate(
  'direct_resource_snapshot_start_availability',
);

/**
 * 目标资源结束快照可用率。
 */
export const directResourceSnapshotEndAvailability = new Rate(
  'direct_resource_snapshot_end_availability',
);

/**
 * 存储生命周期快照可用率。
 */
export const directLifecycleSnapshotStartAvailability = new Rate(
  'direct_lifecycle_snapshot_start_availability',
);

/**
 * 存储生命周期结束快照可用率。
 */
export const directLifecycleSnapshotEndAvailability = new Rate(
  'direct_lifecycle_snapshot_end_availability',
);

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
 * 记录一次直传上传、下载和端到端结果。
 *
 * @param {{uploadMs:number, downloadMs:number, totalMs:number, uploadedBytes:number, downloadedBytes:number, ok:boolean}} result 直传结果
 * @param {Record<string, string>} tags 指标标签
 */
export function recordDirectPathResult(result, tags = {}) {
  directUploadE2eMs.add(result.uploadMs, tags);
  directDownloadE2eMs.add(result.downloadMs, tags);
  directPathE2eMs.add(result.totalMs, tags);
  directUploadedBytes.add(result.uploadedBytes, tags);
  directDownloadedBytes.add(result.downloadedBytes, tags);
  directFlowFailureRate.add(!result.ok, tags);
  if (result.ok) {
    directFileCount.add(1, tags);
  }
}

/**
 * 记录目标资源与存储生命周期快照是否可用。
 *
 * @param {boolean} resourceAvailable 资源快照是否可用
 * @param {boolean} lifecycleAvailable 生命周期快照是否可用
 * @param {'start'|'end'} stage 快照阶段
 * @param {Record<string, string>} tags 指标标签
 */
export function recordDirectSnapshotAvailability(
  resourceAvailable,
  lifecycleAvailable,
  stage,
  tags = {},
) {
  if (stage === 'start') {
    directResourceSnapshotStartAvailability.add(resourceAvailable, tags);
    directLifecycleSnapshotStartAvailability.add(lifecycleAvailable, tags);
    return;
  }
  directResourceSnapshotEndAvailability.add(resourceAvailable, tags);
  directLifecycleSnapshotEndAvailability.add(lifecycleAvailable, tags);
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
