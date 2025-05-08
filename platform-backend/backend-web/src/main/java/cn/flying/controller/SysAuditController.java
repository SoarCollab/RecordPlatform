package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.vo.audit.*;
import cn.flying.service.SysAuditService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 系统审计控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/system/audit")
@Tag(name = "系统审计", description = "系统审计相关接口，包括操作日志查询、高频操作监控、敏感操作审计等")
@PreAuthorize("hasRole('admin')")  // 只有管理员可以访问审计功能
public class SysAuditController {

    @Resource
    private SysAuditService auditService;

    /**
     * 获取审计概览数据
     */
    @GetMapping("/overview")
    @Operation(summary = "获取审计概览数据")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取审计概览数据")
    public Result<Map<String, Object>> getAuditOverview() {
        return Result.success(auditService.getAuditOverview());
    }

    /**
     * 分页查询操作日志
     */
    @GetMapping("/logs/page")
    @Operation(summary = "分页查询操作日志")
    @OperationLog(module = "系统审计", operationType = "查询", description = "分页查询操作日志")
    public Result<IPage<SysOperationLog>> queryOperationLogs(@RequestBody AuditLogQueryVO queryVO) {
        return Result.success(auditService.queryOperationLogs(queryVO));
    }

    /**
     * 获取操作日志详情
     */
    @GetMapping("/logs/{id}")
    @Operation(summary = "获取操作日志详情")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取操作日志详情")
    public Result<SysOperationLog> getLogDetail(@PathVariable Long id) {
        return Result.success(auditService.getLogDetail(id));
    }

    /**
     * 导出操作日志
     */
    @PostMapping("/logs/export")
    @Operation(summary = "导出操作日志")
    //@OperationLog(module = "系统审计", operationType = "导出", description = "导出操作日志")
    public void exportOperationLogs(@RequestBody AuditLogQueryVO queryVO, HttpServletResponse response) throws IOException {
        List<SysOperationLog> logs = auditService.exportOperationLogs(queryVO);
        
        // 创建Excel工作簿
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("操作日志");
            
            // 创建标题行
            Row headerRow = sheet.createRow(0);
            String[] headers = {"ID", "用户ID", "用户名", "操作模块", "操作类型", "操作描述", 
                               "请求方法", "请求URL", "请求IP", "操作状态", "操作时间", "执行时长(ms)"};
            
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }
            
            // 填充数据行
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (int i = 0; i < logs.size(); i++) {
                Row row = sheet.createRow(i + 1);
                SysOperationLog log = logs.get(i);
                
                row.createCell(0).setCellValue(log.getId());
                row.createCell(1).setCellValue(log.getUserId());
                row.createCell(2).setCellValue(log.getUsername());
                row.createCell(3).setCellValue(log.getModule());
                row.createCell(4).setCellValue(log.getOperationType());
                row.createCell(5).setCellValue(log.getDescription());
                row.createCell(6).setCellValue(log.getMethod());
                row.createCell(7).setCellValue(log.getRequestUrl());
                row.createCell(8).setCellValue(log.getRequestIp());
                row.createCell(9).setCellValue(log.getStatus() == 0 ? "成功" : "失败");
                row.createCell(10).setCellValue(log.getOperationTime().format(formatter));
                row.createCell(11).setCellValue(log.getExecutionTime() != null ? log.getExecutionTime() : 0);
            }
            
            // 自动调整列宽
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            
            // 设置响应头
            String fileName = URLEncoder.encode("操作日志_" + System.currentTimeMillis() + ".xlsx", StandardCharsets.UTF_8);
            response.setContentType("application/vnd.ms-excel");
            response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
            
            // 写入响应
            ServletOutputStream outputStream = response.getOutputStream();
            workbook.write(outputStream);
            outputStream.flush();
        }
    }

    /**
     * 获取高频操作记录
     */
    @GetMapping("/high-frequency")
    @Operation(summary = "获取高频操作记录")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取高频操作记录")
    public Result<List<HighFrequencyOperationVO>> getHighFrequencyOperations() {
        return Result.success(auditService.getHighFrequencyOperations());
    }

    /**
     * 获取敏感操作记录
     */
    @PostMapping("/sensitive/page")
    @Operation(summary = "获取敏感操作记录")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取敏感操作记录")
    public Result<IPage<SysOperationLog>> getSensitiveOperations(@RequestBody AuditLogQueryVO queryVO) {
        return Result.success(auditService.getSensitiveOperations(queryVO));
    }

    /**
     * 获取错误操作统计
     */
    @GetMapping("/error-stats")
    @Operation(summary = "获取错误操作统计")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取错误操作统计")
    public Result<List<ErrorOperationStatsVO>> getErrorOperationStats() {
        return Result.success(auditService.getErrorOperationStats());
    }

    /**
     * 获取用户操作时间分布
     */
    @GetMapping("/time-distribution")
    @Operation(summary = "获取用户操作时间分布")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取用户操作时间分布")
    public Result<List<UserTimeDistributionVO>> getUserTimeDistribution() {
        return Result.success(auditService.getUserTimeDistribution());
    }

    /**
     * 获取审计配置列表
     */
    @GetMapping("/configs")
    @Operation(summary = "获取审计配置列表")
    @OperationLog(module = "系统审计", operationType = "查询", description = "获取审计配置列表")
    public Result<List<AuditConfigVO>> getAuditConfigs() {
        return Result.success(auditService.getAuditConfigs());
    }

    /**
     * 更新审计配置
     */
    @PutMapping("/configs")
    @Operation(summary = "更新审计配置")
    @OperationLog(module = "系统审计", operationType = "修改", description = "更新审计配置")
    public Result<Boolean> updateAuditConfig(@RequestBody AuditConfigVO configVO) {
        return Result.success(auditService.updateAuditConfig(configVO));
    }

    /**
     * 手动检查异常操作
     */
    @GetMapping("/check-anomalies")
    @Operation(summary = "手动检查异常操作")
    @OperationLog(module = "系统审计", operationType = "检查", description = "手动检查异常操作")
    public Result<Map<String, Object>> checkAnomalies() {
        return Result.success(auditService.checkAnomalies());
    }

    /**
     * 执行日志备份
     */
    @PostMapping("/backup-logs")
    @Operation(summary = "执行日志备份")
    @Parameter(name = "days", description = "备份多少天前的日志")
    @Parameter(name = "deleteAfterBackup", description = "备份后是否删除")
    @OperationLog(module = "系统审计", operationType = "备份", description = "执行日志备份")
    public Result<String> backupLogs(
            @RequestParam(defaultValue = "180") Integer days,
            @RequestParam(defaultValue = "false") Boolean deleteAfterBackup) {
        return Result.success(auditService.backupLogs(days, deleteAfterBackup));
    }
} 