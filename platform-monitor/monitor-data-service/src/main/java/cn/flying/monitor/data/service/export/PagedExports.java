package cn.flying.monitor.data.service.export;

import cn.flying.monitor.data.dto.ExportResultDTO;

import java.util.List;

/**
 * 分页导出结果容器
 */
public record PagedExports(List<ExportResultDTO> items, int total) {
}
