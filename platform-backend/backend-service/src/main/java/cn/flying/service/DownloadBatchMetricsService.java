package cn.flying.service;

import cn.flying.dao.vo.file.BatchDownloadMetricsReportVO;

/**
 * 批量下载指标服务。
 */
public interface DownloadBatchMetricsService {

    /**
     * 上报批量下载质量指标并写入监控系统。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param report 指标上报内容
     */
    void reportBatchMetrics(Long tenantId, Long userId, BatchDownloadMetricsReportVO report);
}
