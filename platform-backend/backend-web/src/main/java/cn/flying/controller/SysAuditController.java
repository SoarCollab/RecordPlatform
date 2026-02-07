package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.vo.audit.AuditConfigVO;
import cn.flying.dao.vo.audit.AuditLogQueryVO;
import cn.flying.dao.vo.audit.AuditLogVO;
import cn.flying.dao.vo.audit.ErrorOperationStatsVO;
import cn.flying.dao.vo.audit.HighFrequencyOperationVO;
import cn.flying.dao.vo.audit.UserTimeDistributionVO;
import cn.flying.service.SysAuditService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 系统审计控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system/audit")
@Tag(name = "系统审计", description = "系统审计相关接口，包括操作日志查询、高频操作监控、敏感操作审计等")
@PreAuthorize("isAdminOrMonitor()")
public class SysAuditController {

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private SysAuditService auditService;

    /**
     * 获取审计概览数据。
     *
     * @return 概览数据
     */
    @GetMapping("/overview")
    @Operation(summary = "获取审计概览数据")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取审计概览数据")
    public Result<Map<String, Object>> getAuditOverview() {
        return Result.success(auditService.getAuditOverview());
    }

    /**
     * 分页查询审计日志（REST 新路径，GET）。
     *
     * @param queryVO 查询条件
     * @return 分页结果
     */
    @GetMapping("/logs")
    @Operation(summary = "分页查询审计日志（REST，GET）")
    public Result<IPage<AuditLogVO>> getAuditLogs(@ModelAttribute AuditLogQueryVO queryVO) {
        return Result.success(queryAuditLogs(queryVO));
    }

    /**
     * 分页查询审计日志（REST 新路径，POST）。
     *
     * @param queryVO 查询条件
     * @return 分页结果
     */
    @PostMapping("/logs/query")
    @Operation(summary = "分页查询审计日志（REST，POST）")
    public Result<IPage<AuditLogVO>> queryAuditLogsByBody(@Valid @RequestBody AuditLogQueryVO queryVO) {
        return Result.success(queryAuditLogs(queryVO));
    }

    /**
     * 获取操作日志详情。
     *
     * @param id 日志 ID
     * @return 日志详情
     */
    @GetMapping("/logs/{id}")
    @Operation(summary = "获取操作日志详情")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取操作日志详情")
    public Result<SysOperationLog> getLogDetail(@PathVariable Long id) {
        return Result.success(auditService.getLogDetail(id));
    }

    /**
     * 导出审计日志（POST Body）。
     *
     * @param queryVO  查询条件
     * @param response HTTP 响应
     * @throws IOException IO 异常
     */
    @PostMapping("/logs/export")
    @Operation(summary = "导出审计日志（POST Body）")
    public void exportAuditLogsByBody(@RequestBody(required = false) AuditLogQueryVO queryVO, HttpServletResponse response) throws IOException {
        doExportLogs(Objects.requireNonNullElseGet(queryVO, AuditLogQueryVO::new), response);
    }

    /**
     * 统一的审计日志分页查询：查询 SysOperationLog 并转换为前端展示 VO。
     *
     * @param queryVO 查询条件
     * @return 分页结果
     */
    private IPage<AuditLogVO> queryAuditLogs(AuditLogQueryVO queryVO) {
        IPage<SysOperationLog> logPage = auditService.queryOperationLogs(Objects.requireNonNullElseGet(queryVO, AuditLogQueryVO::new));
        return logPage.convert(this::toAuditLogVO);
    }

    /**
     * 将操作日志实体转换为前端展示 VO。
     *
     * @param log 操作日志实体
     * @return 审计日志 VO
     */
    private AuditLogVO toAuditLogVO(SysOperationLog log) {
        return AuditLogVO.builder()
                .id(String.valueOf(log.getId()))
                .userId(log.getUserId())
                .username(log.getUsername())
                .action(log.getOperationType())
                .module(log.getModule())
                .detail(log.getRequestParam())
                .ip(log.getRequestIp())
                .status(log.getStatus())
                .errorMessage(log.getErrorMsg())
                .duration(log.getExecutionTime())
                .createTime(log.getOperationTime() != null
                        ? log.getOperationTime().format(DATETIME_FORMATTER)
                        : null)
                .build();
    }

    /**
     * 执行导出：查询日志并写出 Excel。
     *
     * @param queryVO  查询条件
     * @param response HTTP 响应
     * @throws IOException IO 异常
     */
    private void doExportLogs(AuditLogQueryVO queryVO, HttpServletResponse response) throws IOException {
        List<SysOperationLog> logs = auditService.exportOperationLogs(queryVO);

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("操作日志");

            Row headerRow = sheet.createRow(0);
            String[] headers = {"ID", "用户ID", "用户名", "操作模块", "操作类型", "操作描述",
                    "请求方法", "请求URL", "请求IP", "操作状态", "操作时间", "执行时长(ms)"};

            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }

            for (int i = 0; i < logs.size(); i++) {
                Row row = sheet.createRow(i + 1);
                SysOperationLog log = logs.get(i);

                row.createCell(0).setCellValue(toCellString(log.getId()));
                row.createCell(1).setCellValue(toCellString(log.getUserId()));
                row.createCell(2).setCellValue(toCellString(log.getUsername()));
                row.createCell(3).setCellValue(toCellString(log.getModule()));
                row.createCell(4).setCellValue(toCellString(log.getOperationType()));
                row.createCell(5).setCellValue(toCellString(log.getDescription()));
                row.createCell(6).setCellValue(toCellString(log.getMethod()));
                row.createCell(7).setCellValue(toCellString(log.getRequestUrl()));
                row.createCell(8).setCellValue(toCellString(log.getRequestIp()));
                row.createCell(9).setCellValue(log.getStatus() == 0 ? "成功" : "失败");
                row.createCell(10).setCellValue(log.getOperationTime() != null
                        ? log.getOperationTime().format(DATETIME_FORMATTER) : "");
                row.createCell(11).setCellValue(log.getExecutionTime() != null ? log.getExecutionTime() : 0);
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            String fileName = URLEncoder.encode("操作日志_" + System.currentTimeMillis() + ".xlsx", StandardCharsets.UTF_8);
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=" + fileName);

            ServletOutputStream outputStream = response.getOutputStream();
            workbook.write(outputStream);
            outputStream.flush();
        }
    }

    /**
     * 将值安全转换为 Excel 单元格字符串。
     *
     * @param value 任意值
     * @return 字符串
     */
    private String toCellString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 获取高频操作记录。
     *
     * @return 高频记录
     */
    @GetMapping("/high-frequency")
    @Operation(summary = "获取高频操作记录")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取高频操作记录")
    public Result<List<HighFrequencyOperationVO>> getHighFrequencyOperations() {
        return Result.success(auditService.getHighFrequencyOperations());
    }

    /**
     * 获取敏感操作记录。
     *
     * @param queryVO 查询条件
     * @return 分页结果
     */
    @PostMapping("/sensitive/page")
    @Operation(summary = "获取敏感操作记录")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取敏感操作记录")
    public Result<IPage<SysOperationLog>> getSensitiveOperations(@Valid @RequestBody AuditLogQueryVO queryVO) {
        return Result.success(auditService.getSensitiveOperations(queryVO));
    }

    /**
     * 获取错误操作统计。
     *
     * @return 错误统计
     */
    @GetMapping("/error-stats")
    @Operation(summary = "获取错误操作统计")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取错误操作统计")
    public Result<List<ErrorOperationStatsVO>> getErrorOperationStats() {
        return Result.success(auditService.getErrorOperationStats());
    }

    /**
     * 获取用户操作时间分布。
     *
     * @return 时间分布数据
     */
    @GetMapping("/time-distribution")
    @Operation(summary = "获取用户操作时间分布")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取用户操作时间分布")
    public Result<List<UserTimeDistributionVO>> getUserTimeDistribution() {
        return Result.success(auditService.getUserTimeDistribution());
    }

    /**
     * 获取审计配置列表。
     *
     * @return 配置列表
     */
    @GetMapping("/configs")
    @Operation(summary = "获取审计配置列表")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取审计配置列表")
    public Result<List<AuditConfigVO>> getAuditConfigs() {
        return Result.success(auditService.getAuditConfigs());
    }

    /**
     * 更新审计配置。
     *
     * @param configVO 配置参数
     * @return 是否成功
     */
    @PutMapping("/configs")
    @Operation(summary = "更新审计配置")
    @OperationLog(module = "系统审计", operationType = "修改", description = "更新审计配置")
    public Result<Boolean> updateAuditConfig(@Valid @RequestBody AuditConfigVO configVO) {
        return Result.success(auditService.updateAuditConfig(configVO));
    }

    /**
     * 手动检查异常操作（REST 新路径）。
     *
     * @return 检查结果
     */
    @PostMapping("/anomalies/check")
    @Operation(summary = "手动检查异常操作（REST）")
    @OperationLog(module = "系统审计", operationType = "检查", description = "手动检查异常操作（REST）")
    public Result<Map<String, Object>> checkAnomaliesByPost() {
        return Result.success(auditService.checkAnomalies());
    }

    /**
     * 执行日志备份（REST 新路径）。
     *
     * @param days              备份天数
     * @param deleteAfterBackup 备份后是否删除
     * @return 备份结果
     */
    @PostMapping("/logs/backups")
    @Operation(summary = "执行日志备份（REST）")
    @OperationLog(module = "系统审计", operationType = "备份", description = "执行日志备份（REST）")
    public Result<String> backupLogsRest(
            @RequestParam(defaultValue = "180") Integer days,
            @RequestParam(defaultValue = "false") Boolean deleteAfterBackup) {
        return Result.success(auditService.backupLogs(days, deleteAfterBackup));
    }
}
