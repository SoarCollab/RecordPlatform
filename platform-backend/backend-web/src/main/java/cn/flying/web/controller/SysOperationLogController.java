package cn.flying.web.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.vo.SysOperationLogVO;
import cn.flying.service.SysOperationLogService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统操作日志控制器
 */
@RestController
@RequestMapping("/api/system/logs")
public class SysOperationLogController {

    @Resource
    private SysOperationLogService operationLogService;

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
    @OperationLog(module = "系统管理", operationType = "查询", description = "分页查询操作日志")
    public IPage<SysOperationLogVO> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        
        Page<SysOperationLogVO> page = new Page<>(pageNum, pageSize);
        return operationLogService.queryOperationLogs(page, module, username, status, startTime, endTime);
    }

    /**
     * 获取操作日志详情
     *
     * @param id 日志ID
     * @return 日志详情
     */
    @GetMapping("/{id}")
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
    @OperationLog(module = "系统管理", operationType = "导出", description = "导出操作日志")
    public List<SysOperationLogVO> export(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        
        return operationLogService.exportOperationLogs(module, username, status, startTime, endTime);
    }

    /**
     * 清空操作日志
     */
    @DeleteMapping("/clean")
    @OperationLog(module = "系统管理", operationType = "删除", description = "清空操作日志")
    public void cleanLogs() {
        operationLogService.cleanOperationLogs();
    }
} 