package cn.flying.identity.aspect;

import cn.flying.identity.dto.OperationLog;
import cn.flying.identity.service.OperationLogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 异步操作日志记录器
 * 负责在独立的 Spring 代理上异步保存操作日志，避免切面类内自调用导致 @Async 失效。
 */
@Slf4j
@Component
public class OperationLogAsyncRecorder {

    @Resource
    private OperationLogService operationLogService;

    /**
     * 异步保存操作日志
     *
     * @param logEntity 需要保存的操作日志实体
     */
    @Async("taskExecutor")
    public void saveAsync(OperationLog logEntity) {
        try {
            operationLogService.saveOperationLog(logEntity);
        } catch (Exception e) {
            log.error("异步保存操作日志失败", e);
        }
    }
}
