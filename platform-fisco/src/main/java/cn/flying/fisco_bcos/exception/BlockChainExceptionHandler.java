package cn.flying.fisco_bcos.exception;

import cn.flying.fisco_bcos.adapter.model.ChainException;
import cn.flying.fisco_bcos.monitor.FiscoMetrics;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * 区块链异常处理器
 * 将底层异常转换为统一的 Result 响应，避免敏感信息泄露
 * 同时记录 Prometheus 监控指标
 *
 * <p>支持的异常类型：
 * <ul>
 *   <li>{@link ChainException} - 区块链业务异常，记录 warn 日志</li>
 *   <li>{@link SocketTimeoutException} - 超时异常，返回 BLOCKCHAIN_TIMEOUT</li>
 *   <li>{@link ConnectException} - 连接异常，返回 BLOCKCHAIN_UNREACHABLE</li>
 *   <li>{@link java.io.IOException} - IO 异常，返回 BLOCKCHAIN_ERROR</li>
 *   <li>其他异常 - 记录完整堆栈，返回 fallbackEnum</li>
 * </ul>
 */
public final class BlockChainExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BlockChainExceptionHandler.class);

    private BlockChainExceptionHandler() {
        // 工具类禁止实例化
    }

    /**
     * 处理区块链操作异常
     *
     * @param e         捕获的异常
     * @param operation 操作名称（用于日志）
     * @param <T>       返回值类型
     * @return 统一的错误响应
     */
    public static <T> Result<T> handle(Exception e, String operation) {
        return handle(e, operation, ResultEnum.BLOCKCHAIN_ERROR, null);
    }

    /**
     * 处理区块链操作异常，使用指定的错误码
     *
     * @param e             捕获的异常
     * @param operation     操作名称
     * @param fallbackEnum  默认错误码
     * @param <T>           返回值类型
     * @return 统一的错误响应
     */
    public static <T> Result<T> handle(Exception e, String operation, ResultEnum fallbackEnum) {
        return handle(e, operation, fallbackEnum, null);
    }

    /**
     * 处理区块链操作异常，带监控指标记录
     *
     * @param e             捕获的异常
     * @param operation     操作名称
     * @param fallbackEnum  默认错误码
     * @param metrics       监控指标（可选）
     * @param <T>           返回值类型
     * @return 统一的错误响应
     */
    public static <T> Result<T> handle(Exception e, String operation, ResultEnum fallbackEnum, FiscoMetrics metrics) {
        // 优先处理 ChainException（业务异常）
        if (e instanceof ChainException chainEx) {
            log.warn("[{}] 链操作失败: {}", operation, chainEx.getMessage());
            if (metrics != null) {
                metrics.recordFailure();
            }
            return Result.error(fallbackEnum, null);
        }

        // 检查根异常类型
        Throwable rootCause = getRootCause(e);

        if (rootCause instanceof SocketTimeoutException) {
            log.warn("[{}] 区块链操作超时: {}", operation, rootCause.getMessage());
            if (metrics != null) {
                metrics.recordTimeout();
            }
            return Result.error(ResultEnum.BLOCKCHAIN_TIMEOUT, null);
        }

        if (rootCause instanceof ConnectException) {
            log.warn("[{}] 区块链节点连接失败: {}", operation, rootCause.getMessage());
            if (metrics != null) {
                metrics.recordConnectionError();
            }
            return Result.error(ResultEnum.BLOCKCHAIN_UNREACHABLE, null);
        }

        if (rootCause instanceof java.io.IOException) {
            log.error("[{}] 区块链网络IO异常: {}", operation, rootCause.getMessage());
            if (metrics != null) {
                metrics.recordFailure();
            }
            return Result.error(ResultEnum.BLOCKCHAIN_ERROR, null);
        }

        // 其他未知异常，记录完整堆栈但不暴露给客户端
        log.error("[{}] 未知异常", operation, e);
        if (metrics != null) {
            metrics.recordFailure();
        }
        return Result.error(fallbackEnum, null);
    }

    /**
     * 获取异常的根本原因
     */
    private static Throwable getRootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
