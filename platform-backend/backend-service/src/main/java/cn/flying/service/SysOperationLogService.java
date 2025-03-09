package cn.flying.service;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.vo.SysOperationLogVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 系统操作日志服务接口
 */
public interface SysOperationLogService extends IService<SysOperationLog> {

    /**
     * 保存操作日志
     *
     * @param operationLog 操作日志信息
     */
    void saveOperationLog(SysOperationLog operationLog);

    /**
     * 分页查询操作日志
     *
     * @param page       分页参数
     * @param module     操作模块
     * @param username   操作用户
     * @param status     操作状态
     * @param startTime  开始时间
     * @param endTime    结束时间
     * @return 分页结果
     */
    IPage<SysOperationLogVO> queryOperationLogs(IPage<SysOperationLogVO> page, String module, 
                                              String username, Integer status, 
                                              String startTime, String endTime);

    /**
     * 导出操作日志
     *
     * @param module     操作模块
     * @param username   操作用户
     * @param status     操作状态
     * @param startTime  开始时间
     * @param endTime    结束时间
     * @return 日志列表
     */
    List<SysOperationLogVO> exportOperationLogs(String module, String username, Integer status, 
                                             String startTime, String endTime);

    /**
     * 根据ID获取操作日志详情
     *
     * @param id 日志ID
     * @return 日志详情
     */
    SysOperationLog getLogDetailById(Long id);

    /**
     * 清空操作日志
     */
    void cleanOperationLogs();
} 