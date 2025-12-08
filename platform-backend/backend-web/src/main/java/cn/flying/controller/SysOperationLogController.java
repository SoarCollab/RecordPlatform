package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.vo.audit.AuditLogQueryVO;
import cn.flying.service.SysAuditService;
import cn.flying.service.SysOperationLogService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统操作日志控制器
 * 注：查询和导出功能使用 SysAuditService，避免代码重复
 */
@RestController
@RequestMapping("/api/v1/system/logs")
@PreAuthorize("hasRole('admin')") // 仅允许管理员访问
@Tag(name = "系统操作日志", description = "包括操作日志的查询、导出、清空等操作。")
public class SysOperationLogController {

    @Resource
    private SysOperationLogService operationLogService;

    @Resource
    private SysAuditService auditService;

    /**
     * 分页查询操作日志
     *
     * @param pageNum   当前页码
     * @param pageSize  每页记录数
     * @param module    操作模块
     * @param username  操作用户
     * @param status    操作状态
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询操作日志")
    @OperationLog(module = "系统管理", operationType = "查询", description = "分页查询操作日志")
    public IPage<SysOperationLog> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {

        AuditLogQueryVO queryVO = new AuditLogQueryVO();
        queryVO.setPageNum(pageNum);
        queryVO.setPageSize(pageSize);
        queryVO.setModule(module);
        queryVO.setUsername(username);
        queryVO.setStatus(status);
        queryVO.setStartTime(startTime);
        queryVO.setEndTime(endTime);

        return auditService.queryOperationLogs(queryVO);
    }

    /**
     * 获取操作日志详情
     *
     * @param id 日志ID
     * @return 日志详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取操作日志详情")
    @OperationLog(module = "系统管理", operationType = "查询", description = "查询操作日志详情")
    public SysOperationLog getLogDetail(@PathVariable Long id) {
        return operationLogService.getLogDetailById(id);
    }

    /**
     * 导出操作日志
     *
     * @param module    操作模块
     * @param username  操作用户
     * @param status    操作状态
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 日志列表
     */
    @GetMapping("/export")
    @Operation(summary = "导出操作日志")
    @OperationLog(module = "系统管理", operationType = "导出", description = "导出操作日志")
    public List<SysOperationLog> export(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {

        AuditLogQueryVO queryVO = new AuditLogQueryVO();
        queryVO.setModule(module);
        queryVO.setUsername(username);
        queryVO.setStatus(status);
        queryVO.setStartTime(startTime);
        queryVO.setEndTime(endTime);

        return auditService.exportOperationLogs(queryVO);
    }

    /**
     * 清空操作日志
     */
    @DeleteMapping("/clean")
    @Operation(summary = "清空操作日志")
    @OperationLog(module = "系统管理", operationType = "删除", description = "清空操作日志")
    public void cleanLogs() {
        operationLogService.cleanOperationLogs();
    }
} 