package cn.flying.fisco_bcos.monitor;

import cn.flying.fisco_bcos.service.SharingService;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fisco.bcos.sdk.v3.client.protocol.response.TotalTransactionCount;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * FISCO BCOS 区块链 Prometheus 监控指标。
 * 提供区块链操作的计数器、计时器和状态仪表盘。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FiscoMetrics {

    private final MeterRegistry registry;
    private final SharingService sharingService;

    private Counter txSuccessCounter;
    private Counter txFailureCounter;
    private Counter txTimeoutCounter;
    private Counter txConnectionErrorCounter;

    private Timer storeFileTimer;
    private Timer queryFileTimer;
    private Timer deleteFileTimer;
    private Timer shareFileTimer;

    private final AtomicLong currentBlockHeight = new AtomicLong(0);
    private final AtomicLong totalTransactions = new AtomicLong(0);
    private final AtomicLong failedTransactions = new AtomicLong(0);

    @PostConstruct
    public void init() {
        // 交易计数器
        txSuccessCounter = Counter.builder("fisco.tx.total")
                .description("区块链交易成功总数")
                .tag("status", "success")
                .register(registry);

        txFailureCounter = Counter.builder("fisco.tx.total")
                .description("区块链交易失败总数")
                .tag("status", "failure")
                .register(registry);

        txTimeoutCounter = Counter.builder("fisco.tx.errors")
                .description("区块链超时错误数")
                .tag("type", "timeout")
                .register(registry);

        txConnectionErrorCounter = Counter.builder("fisco.tx.errors")
                .description("区块链连接错误数")
                .tag("type", "connection")
                .register(registry);

        // 操作计时器
        storeFileTimer = Timer.builder("fisco.operation.duration")
                .description("文件存储操作耗时")
                .tag("operation", "storeFile")
                .register(registry);

        queryFileTimer = Timer.builder("fisco.operation.duration")
                .description("文件查询操作耗时")
                .tag("operation", "queryFile")
                .register(registry);

        deleteFileTimer = Timer.builder("fisco.operation.duration")
                .description("文件删除操作耗时")
                .tag("operation", "deleteFile")
                .register(registry);

        shareFileTimer = Timer.builder("fisco.operation.duration")
                .description("文件分享操作耗时")
                .tag("operation", "shareFile")
                .register(registry);

        // 区块链状态仪表盘
        Gauge.builder("fisco.block.height", currentBlockHeight, AtomicLong::get)
                .description("当前区块高度")
                .register(registry);

        Gauge.builder("fisco.transactions.total", totalTransactions, AtomicLong::get)
                .description("链上交易总数")
                .register(registry);

        Gauge.builder("fisco.transactions.failed", failedTransactions, AtomicLong::get)
                .description("链上失败交易数")
                .register(registry);

        log.info("FISCO Prometheus 监控指标已初始化");
    }

    /**
     * 记录交易成功
     */
    public void recordSuccess() {
        txSuccessCounter.increment();
    }

    /**
     * 记录交易失败
     */
    public void recordFailure() {
        txFailureCounter.increment();
    }

    /**
     * 记录超时错误
     */
    public void recordTimeout() {
        txTimeoutCounter.increment();
        txFailureCounter.increment();
    }

    /**
     * 记录连接错误
     */
    public void recordConnectionError() {
        txConnectionErrorCounter.increment();
        txFailureCounter.increment();
    }

    /**
     * 获取文件存储计时器
     */
    public Timer.Sample startStoreTimer() {
        return Timer.start(registry);
    }

    /**
     * 停止并记录文件存储耗时
     */
    public void stopStoreTimer(Timer.Sample sample) {
        sample.stop(storeFileTimer);
    }

    /**
     * 获取文件查询计时器
     */
    public Timer.Sample startQueryTimer() {
        return Timer.start(registry);
    }

    /**
     * 停止并记录文件查询耗时
     */
    public void stopQueryTimer(Timer.Sample sample) {
        sample.stop(queryFileTimer);
    }

    /**
     * 获取文件删除计时器
     */
    public Timer.Sample startDeleteTimer() {
        return Timer.start(registry);
    }

    /**
     * 停止并记录文件删除耗时
     */
    public void stopDeleteTimer(Timer.Sample sample) {
        sample.stop(deleteFileTimer);
    }

    /**
     * 获取文件分享计时器
     */
    public Timer.Sample startShareTimer() {
        return Timer.start(registry);
    }

    /**
     * 停止并记录文件分享耗时
     */
    public void stopShareTimer(Timer.Sample sample) {
        sample.stop(shareFileTimer);
    }

    /**
     * 刷新区块链状态指标。
     * 建议定时调用（如每分钟一次）。
     */
    public void refreshBlockchainStatus() {
        try {
            TotalTransactionCount totalTransactionCount = sharingService.getCurrentBlockChainMessage();
            if (totalTransactionCount != null) {
                TotalTransactionCount.TransactionCountInfo info = totalTransactionCount.getTotalTransactionCount();
                if (info != null) {
                    currentBlockHeight.set(parseHexLong(info.getBlockNumber()));
                    totalTransactions.set(parseHexLong(info.getTransactionCount()));
                    failedTransactions.set(parseHexLong(info.getFailedTransactionCount()));
                }
            }
        } catch (Exception e) {
            log.warn("刷新区块链状态指标失败: {}", e.getMessage());
        }
    }

    private long parseHexLong(String hexValue) {
        if (hexValue == null || hexValue.isEmpty()) return 0L;
        try {
            return new java.math.BigInteger(hexValue).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
