package cn.flying.monitor.data.dto;

import java.util.List;

/**
 * 导出列表响应体
 */
public record ExportListResponse(List<ExportResultDTO> items, PaginationInfo pagination) {
}
