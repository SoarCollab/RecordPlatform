package cn.flying.service.saga;

import cn.flying.dao.entity.FileSaga;
import cn.flying.dao.mapper.FileSagaMapper;
import cn.flying.service.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga 补偿步骤持久化辅助服务。
 * 使用 REQUIRES_NEW 传播级别确保每个补偿步骤的状态能立即独立提交，
 * 解决外部调用（如 MinIO 删除）成功但后续事务回滚导致的状态不一致问题。
 * <p>
 * 原子化策略：
 * 1. 每个补偿步骤完成后立即在独立事务中持久化
 * 2. 外部调用前记录"开始"状态，成功后记录"完成"状态
 * 3. 重试时根据已持久化的步骤状态跳过已完成的操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaCompensationHelper {

    private final FileSagaMapper sagaMapper;

    /**
     * 在独立事务中持久化 Saga 的 payload。
     * 无论外层事务是否回滚，此操作都会提交。
     *
     * @param saga        Saga 实体
     * @param payloadJson 序列化后的 payload JSON
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void persistPayloadInNewTransaction(FileSaga saga, String payloadJson) {
        saga.setPayload(payloadJson);
        sagaMapper.updatePayloadById(saga.getId(), payloadJson);
        log.debug("Saga payload 已持久化: id={}", saga.getId());
    }

    /**
     * 在独立事务中更新 Saga 状态。
     * 用于记录补偿开始/完成等关键状态变更。
     *
     * @param saga Saga 实体
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void updateSagaStatusInNewTransaction(FileSaga saga) {
        sagaMapper.updateById(saga);
        log.debug("Saga 状态已更新: id={}, status={}", saga.getId(), saga.getStatus());
    }

    /**
     * 在独立事务中记录补偿步骤完成。
     * 包含 payload 和 Saga 主表的更新。
     *
     * @param saga        Saga 实体
     * @param payloadJson 序列化后的 payload JSON
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void persistCompensationStepCompletion(FileSaga saga, String payloadJson) {
        saga.setPayload(payloadJson);
        sagaMapper.updateById(saga);
        log.debug("Saga 补偿步骤已记录: id={}", saga.getId());
    }

    /**
     * 在独立事务中插入新的 Saga 记录。
     *
     * @param saga Saga 实体
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void insertSagaInNewTransaction(FileSaga saga) {
        sagaMapper.insert(saga);
        log.debug("Saga 已创建: id={}, requestId={}", saga.getId(), saga.getRequestId());
    }

    /**
     * 在独立事务中更新 Saga 的步骤状态。
     *
     * @param saga Saga 实体
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void updateSagaStepInNewTransaction(FileSaga saga) {
        sagaMapper.updateById(saga);
        log.debug("Saga 步骤已更新: id={}, step={}", saga.getId(), saga.getCurrentStep());
    }

    /**
     * 在独立事务中发布 Outbox 事件。
     * 用于非事务上下文中需要调用 OutboxService.appendEvent() 的场景。
     * <p>
     * 设计说明：OutboxService.appendEvent() 使用 MANDATORY 传播级别，
     * 需要在已存在的事务中调用。本方法提供一个 REQUIRES_NEW 事务包装器，
     * 允许从非事务上下文（如 Saga 编排器）中发布事件。
     *
     * @param outboxService  OutboxService 实例
     * @param aggregateType  聚合类型
     * @param aggregateId    聚合ID
     * @param eventType      事件类型
     * @param payload        事件载荷
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void publishEventInNewTransaction(OutboxService outboxService,
                                              String aggregateType,
                                              Long aggregateId,
                                              String eventType,
                                              String payload) {
        outboxService.appendEvent(aggregateType, aggregateId, eventType, payload);
        log.debug("Outbox 事件已发布: type={}, aggregateId={}", eventType, aggregateId);
    }
}
