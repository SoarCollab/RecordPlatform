package cn.flying.fisco_bcos.monitor;

import cn.flying.fisco_bcos.adapter.BlockChainAdapter;
import cn.flying.fisco_bcos.adapter.model.ChainStatus;
import cn.flying.fisco_bcos.adapter.model.ChainType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多链 Prometheus 监控指标 v2.0
 * 支持 LOCAL_FISCO, BSN_FISCO, BSN_BESU 多链监控。
 * 提供区块链操作的计数器、计时器和状态仪表盘。
 *
 * @see BlockChainAdapter
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FiscoMetrics {

    private final MeterRegistry registry;
    private final BlockChainAdapter chainAdapter;
    private final AtomicLong currentBlockHeight = new AtomicLong(0);
    private final AtomicLong totalTransactions = new AtomicLong(0);
    private final AtomicLong failedTransactions = new AtomicLong(0);
    private final AtomicInteger chainHealthy = new AtomicInteger(1);
    private Counter txSuccessCounter;
    private Counter txFailureCounter;
    private Counter txTimeoutCounter;
    private Counter txConnectionErrorCounter;
    private Timer storeFileTimer;
    private Timer queryFileTimer;
    private Timer deleteFileTimer;
    private Timer shareFileTimer;

    @PostConstruct
    public void init() {
        String chainTag = chainAdapter.getChainType().getConfigValue();

        // 交易计数器 (按链类型区分)
        txSuccessCounter = Counter.builder("blockchain.tx.total")
                .description("区块链交易成功总数")
                .tag("chain", chainTag)
                .tag("status", "success")
                .register(registry);

        txFailureCounter = Counter.builder("blockchain.tx.total")
                .description("区块链交易失败总数")
                .tag("chain", chainTag)
                .tag("status", "failure")
                .register(registry);

        txTimeoutCounter = Counter.builder("blockchain.tx.errors")
                .description("区块链超时错误数")
                .tag("chain", chainTag)
                .tag("type", "timeout")
                .register(registry);

        txConnectionErrorCounter = Counter.builder("blockchain.tx.errors")
                .description("区块链连接错误数")
                .tag("chain", chainTag)
                .tag("type", "connection")
                .register(registry);

        // 操作计时器 (按链类型区分)
        storeFileTimer = Timer.builder("blockchain.operation.duration")
                .description("文件存储操作耗时")
                .tag("chain", chainTag)
                .tag("operation", "storeFile")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        queryFileTimer = Timer.builder("blockchain.operation.duration")
                .description("文件查询操作耗时")
                .tag("chain", chainTag)
                .tag("operation", "queryFile")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        deleteFileTimer = Timer.builder("blockchain.operation.duration")
                .description("文件删除操作耗时")
                .tag("chain", chainTag)
                .tag("operation", "deleteFile")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        shareFileTimer = Timer.builder("blockchain.operation.duration")
                .description("文件分享操作耗时")
                .tag("chain", chainTag)
                .tag("operation", "shareFile")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // 区块链状态仪表盘 (按链类型区分)
        Gauge.builder("blockchain.block.height", currentBlockHeight, AtomicLong::get)
                .description("当前区块高度")
                .tag("chain", chainTag)
                .register(registry);

        Gauge.builder("blockchain.transactions.total", totalTransactions, AtomicLong::get)
                .description("链上交易总数")
                .tag("chain", chainTag)
                .register(registry);

        Gauge.builder("blockchain.transactions.failed", failedTransactions, AtomicLong::get)
                .description("链上失败交易数")
                .tag("chain", chainTag)
                .register(registry);

        // 链健康状态 (1=健康, 0=不健康)
        Gauge.builder("blockchain.health", chainHealthy, AtomicInteger::get)
                .description("区块链连接健康状态")
                .tag("chain", chainTag)
                .register(registry);

        log.info("[{}] Prometheus 监控指标已初始化", chainAdapter.getChainType().getDisplayName());
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
            ChainStatus status = chainAdapter.getChainStatus();
            if (status != null) {
                if (status.getBlockNumber() != null) {
                    currentBlockHeight.set(status.getBlockNumber());
                }
                if (status.getTransactionCount() != null) {
                    totalTransactions.set(status.getTransactionCount());
                }
                if (status.getFailedTransactionCount() != null) {
                    failedTransactions.set(status.getFailedTransactionCount());
                }
                chainHealthy.set(status.isHealthy() ? 1 : 0);
            }
        } catch (Exception e) {
            log.warn("[{}] 刷新区块链状态指标失败: {}",
                    chainAdapter.getChainType().getDisplayName(), e.getMessage());
            chainHealthy.set(0);
        }
    }

    /**
     * 获取当前激活的链类型
     */
    public ChainType getActiveChainType() {
        return chainAdapter.getChainType();
    }
}
