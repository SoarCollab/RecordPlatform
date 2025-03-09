package cn.flying.service.impl;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.vo.SysOperationLogVO;
import cn.flying.service.SysOperationLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统操作日志服务实现类
 */
@Service
public class SysOperationLogServiceImpl extends ServiceImpl<SysOperationLogMapper, SysOperationLog> implements SysOperationLogService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveOperationLog(SysOperationLog operationLog) {
        baseMapper.insert(operationLog);
    }

    @Override
    public IPage<SysOperationLogVO> queryOperationLogs(IPage<SysOperationLogVO> page, String module, 
                                                     String username, Integer status, 
                                                     String startTime, String endTime) {
        LambdaQueryWrapper<SysOperationLog> queryWrapper = new LambdaQueryWrapper<>();
        
        // 构建查询条件
        if (StringUtils.isNotBlank(module)) {
            queryWrapper.like(SysOperationLog::getModule, module);
        }
        if (StringUtils.isNotBlank(username)) {
            queryWrapper.like(SysOperationLog::getUsername, username);
        }
        if (status != null) {
            queryWrapper.eq(SysOperationLog::getStatus, status);
        }
        if (StringUtils.isNotBlank(startTime) && StringUtils.isNotBlank(endTime)) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime start = LocalDateTime.parse(startTime, formatter);
            LocalDateTime end = LocalDateTime.parse(endTime, formatter);
            queryWrapper.between(SysOperationLog::getOperationTime, start, end);
        }
        
        // 按操作时间降序排序
        queryWrapper.orderByDesc(SysOperationLog::getOperationTime);
        
        // 分页查询
        Page<SysOperationLog> logPage = new Page<>(page.getCurrent(), page.getSize());
        Page<SysOperationLog> resultPage = baseMapper.selectPage(logPage, queryWrapper);
        
        // 转换为VO对象
        IPage<SysOperationLogVO> resultVoPage = new Page<>(resultPage.getCurrent(), resultPage.getSize(), resultPage.getTotal());
        List<SysOperationLogVO> voList = new ArrayList<>();
        
        for (SysOperationLog log : resultPage.getRecords()) {
            SysOperationLogVO vo = new SysOperationLogVO();
            BeanUtils.copyProperties(log, vo);
            voList.add(vo);
        }
        
        resultVoPage.setRecords(voList);
        return resultVoPage;
    }

    @Override
    public List<SysOperationLogVO> exportOperationLogs(String module, String username, Integer status, 
                                                    String startTime, String endTime) {
        LambdaQueryWrapper<SysOperationLog> queryWrapper = new LambdaQueryWrapper<>();
        
        // 构建查询条件
        if (StringUtils.isNotBlank(module)) {
            queryWrapper.like(SysOperationLog::getModule, module);
        }
        if (StringUtils.isNotBlank(username)) {
            queryWrapper.like(SysOperationLog::getUsername, username);
        }
        if (status != null) {
            queryWrapper.eq(SysOperationLog::getStatus, status);
        }
        if (StringUtils.isNotBlank(startTime) && StringUtils.isNotBlank(endTime)) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime start = LocalDateTime.parse(startTime, formatter);
            LocalDateTime end = LocalDateTime.parse(endTime, formatter);
            queryWrapper.between(SysOperationLog::getOperationTime, start, end);
        }
        
        // 按操作时间降序排序
        queryWrapper.orderByDesc(SysOperationLog::getOperationTime);
        
        // 查询列表
        List<SysOperationLog> logList = baseMapper.selectList(queryWrapper);
        
        // 转换为VO对象
        List<SysOperationLogVO> voList = new ArrayList<>();
        for (SysOperationLog log : logList) {
            SysOperationLogVO vo = new SysOperationLogVO();
            BeanUtils.copyProperties(log, vo);
            voList.add(vo);
        }
        
        return voList;
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