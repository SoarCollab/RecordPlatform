package cn.flying.service.impl;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.service.SysOperationLogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统操作日志服务实现类
 * 注：查询和导出功能已迁移至 SysAuditServiceImpl，避免重复
 */
@Service
public class SysOperationLogServiceImpl extends ServiceImpl<SysOperationLogMapper, SysOperationLog> implements SysOperationLogService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveOperationLog(SysOperationLog operationLog) {
        baseMapper.insert(operationLog);
    }

    @Override
    public SysOperationLog getLogDetailById(Long id) {
        return baseMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cleanOperationLogs() {
        baseMapper.delete(null);
    }
} 