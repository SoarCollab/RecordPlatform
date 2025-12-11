package cn.flying.service.saga;

import cn.flying.dao.entity.FileSaga;
import cn.flying.dao.mapper.FileSagaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga 补偿步骤持久化辅助服务。
 * 使用 REQUIRES_NEW 传播级别确保每个补偿步骤的状态能立即独立提交，
 * 解决外部调用（如 MinIO 删除）成功但后续事务回滚导致的状态不一致问题。
 *
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
     * @param saga Saga 实体
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
     * @param saga Saga 实体
     * @param payloadJson 序列化后的 payload JSON
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void persistCompensationStepCompletion(FileSaga saga, String payloadJson) {
        saga.setPayload(payloadJson);
        sagaMapper.updateById(saga);
        log.debug("Saga 补偿步骤已记录: id={}", saga.getId());
    }
}
