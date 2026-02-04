package cn.flying.service.impl;

import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.vo.system.*;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.response.BlockChainMessage;
import cn.flying.service.SystemMonitorService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * 系统监控服务实现
 */
@Slf4j
@Service
public class SystemMonitorServiceImpl implements SystemMonitorService {

    @Resource
    private AccountMapper accountMapper;

    @Resource
    private FileMapper fileMapper;

    @Resource
    private SysOperationLogMapper operationLogMapper;

    @DubboReference(id = "blockChainServiceSystemMonitor", version = BlockChainService.VERSION, timeout = 3000, retries = 0, providedBy = "RecordPlatform_fisco")
    private BlockChainService blockChainService;

    private static final int FALLBACK_CHAIN_NODE_COUNT = 0;
    private static final String FALLBACK_CHAIN_TYPE = "UNKNOWN";

    // Health indicators injected by name
    @Resource(name = "database")
    private HealthIndicator databaseHealthIndicator;

    @Resource(name = "redis")
    private HealthIndicator redisHealthIndicator;

    @Resource(name = "fisco")
    private HealthIndicator fiscoHealthIndicator;

    @Resource(name = "s3Storage")
    private HealthIndicator s3HealthIndicator;

    /**
     * 监控指标聚合使用的执行器。
     * <p>
     * 这些任务包含 DB/Dubbo/health check 等阻塞操作，必须避免落到 ForkJoinPool.commonPool。
     * 采用 AsyncConfiguration 中的虚拟线程执行器（局部 vthreads 策略）。
     */
    @Resource(name = "virtualThreadExecutor")
    private Executor monitorMetricsExecutor;

    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 5;

    /**
     * 健康检查执行器（Spring 管理）。
     * <p>
     * 统一使用 Spring Bean，避免 ad-hoc executor 造成线程泄漏和配置分散。
     */
    @Resource(name = "healthIndicatorExecutor")
    private ExecutorService healthCheckExecutor;

    @Override
    public SystemStatsVO getSystemStats() {
        try {
            // 总用户数
            long totalUsers = accountMapper.selectCount(null);

            // 总文件数 (未删除)
            long totalFiles = fileMapper.selectCount(null);

            // 今日开始时间
            LocalDateTime todayStart = LocalDate.now().atStartOfDay();
            Date todayStartDate = Date.from(todayStart.atZone(ZoneId.systemDefault()).toInstant());

            // 今日上传文件数
            long todayUploads = fileMapper.selectCount(
                    new LambdaQueryWrapper<cn.flying.dao.dto.File>()
                            .ge(cn.flying.dao.dto.File::getCreateTime, todayStartDate)
            );

            // 今日下载次数 (从操作日志统计)
            Long todayDownloads = operationLogMapper.selectOperationsBetween(
                    todayStart, LocalDateTime.now()
            );

            // 区块链交易数
            long totalTransactions = 0;
            try {
                Result<BlockChainMessage> chainResult = blockChainService.getCurrentBlockChainMessage();
                if (chainResult != null && chainResult.getData() != null) {
                    Long txCount = chainResult.getData().getTransactionCount();
                    totalTransactions = txCount != null ? txCount : 0L;
                }
            } catch (Exception e) {
                log.warn("Failed to get chain transaction count: {}", e.getMessage());
            }

            // 总存储容量 (暂时使用文件数量估算，实际应从存储服务获取)
            // TODO: 从 S3 存储服务获取实际存储容量
            long totalStorage = totalFiles * 1024 * 1024; // 临时估算值

            return new SystemStatsVO(
                    totalUsers,
                    totalFiles,
                    totalStorage,
                    totalTransactions,
                    todayUploads,
                    todayDownloads != null ? todayDownloads : 0L
            );
        } catch (Exception e) {
            log.error("Failed to get system stats", e);
            return new SystemStatsVO(0L, 0L, 0L, 0L, 0L, 0L);
        }
    }

    /**
     * 获取区块链状态信息（用于监控展示）。
     *
     * @return 区块链状态 VO（包含区块高度、交易统计、节点数、链类型与健康标识）
     */
    @Override
    public ChainStatusVO getChainStatus() {
        long now = System.currentTimeMillis();
        try {
            Result<BlockChainMessage> result = blockChainService.getCurrentBlockChainMessage();
            if (result == null || !result.isSuccess() || result.getData() == null) {
                if (result != null && !result.isSuccess()) {
                    log.warn("Blockchain service returned non-success result: code={}, message={}", result.getCode(), result.getMessage());
                }
                return new ChainStatusVO(0L, 0L, 0L, FALLBACK_CHAIN_NODE_COUNT, FALLBACK_CHAIN_TYPE, false, now);
            }

            BlockChainMessage message = result.getData();
            Long blockNumber = message.getBlockNumber() != null ? message.getBlockNumber() : 0L;
            Long transactionCount = message.getTransactionCount() != null ? message.getTransactionCount() : 0L;
            Long failedTransactionCount = message.getFailedTransactionCount() != null ? message.getFailedTransactionCount() : 0L;
            Integer nodeCount = message.getNodeCount() != null ? message.getNodeCount() : FALLBACK_CHAIN_NODE_COUNT;
            String chainType = message.getChainType() != null ? message.getChainType() : FALLBACK_CHAIN_TYPE;

            boolean healthy = message.getBlockNumber() != null;
            return new ChainStatusVO(
                    blockNumber,
                    transactionCount,
                    failedTransactionCount,
                    nodeCount,
                    chainType,
                    healthy,
                    now
            );
        } catch (Exception e) {
            log.error("Failed to get chain status", e);
            return new ChainStatusVO(0L, 0L, 0L, FALLBACK_CHAIN_NODE_COUNT, FALLBACK_CHAIN_TYPE, false, now);
        }
    }

    @Override
    public SystemHealthVO getSystemHealth() {
        Map<String, ComponentHealthVO> components = new ConcurrentHashMap<>();

        // 并发检查各组件健康状态
        List<Future<?>> futures = new ArrayList<>();
        futures.add(healthCheckExecutor.submit(() ->
                components.put("database", checkHealth(databaseHealthIndicator, "database"))));
        futures.add(healthCheckExecutor.submit(() ->
                components.put("redis", checkHealth(redisHealthIndicator, "redis"))));
        futures.add(healthCheckExecutor.submit(() ->
                components.put("blockchain", checkHealth(fiscoHealthIndicator, "blockchain"))));
        futures.add(healthCheckExecutor.submit(() ->
                components.put("storage", checkHealth(s3HealthIndicator, "storage"))));

        // 等待所有检查完成
        for (Future<?> future : futures) {
            try {
                future.get(HEALTH_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("Health check timed out");
            } catch (Exception e) {
                log.warn("Health check failed: {}", e.getMessage());
            }
        }

        // 计算总体状态
        String overallStatus = calculateOverallStatus(components);

        // 系统运行时间
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        return new SystemHealthVO(
                overallStatus,
                components,
                uptime,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }

    @Override
    public MonitorMetricsVO getMonitorMetrics() {
        // 并发获取所有指标
        CompletableFuture<SystemStatsVO> statsFuture = CompletableFuture.supplyAsync(this::getSystemStats, monitorMetricsExecutor);
        CompletableFuture<ChainStatusVO> chainFuture = CompletableFuture.supplyAsync(this::getChainStatus, monitorMetricsExecutor);
        CompletableFuture<SystemHealthVO> healthFuture = CompletableFuture.supplyAsync(this::getSystemHealth, monitorMetricsExecutor);

        try {
            return new MonitorMetricsVO(
                    statsFuture.get(10, TimeUnit.SECONDS),
                    chainFuture.get(10, TimeUnit.SECONDS),
                    healthFuture.get(10, TimeUnit.SECONDS)
            );
        } catch (Exception e) {
            log.error("Failed to get monitor metrics", e);
            return new MonitorMetricsVO(getSystemStats(), getChainStatus(), getSystemHealth());
        }
    }

    private ComponentHealthVO checkHealth(HealthIndicator indicator, String name) {
        try {
            Health health = indicator.health();
            Map<String, Object> details = new HashMap<>(health.getDetails());
            return new ComponentHealthVO(health.getStatus().getCode(), details.isEmpty() ? null : details);
        } catch (Exception e) {
            log.warn("Health check failed for {}: {}", name, e.getMessage());
            return new ComponentHealthVO("DOWN", Map.of("error", e.getMessage()));
        }
    }

    private String calculateOverallStatus(Map<String, ComponentHealthVO> components) {
        boolean anyDown = false;
        boolean anyDegraded = false;

        for (ComponentHealthVO component : components.values()) {
            if ("DOWN".equals(component.getStatus())) {
                anyDown = true;
            } else if ("DEGRADED".equals(component.getStatus())) {
                anyDegraded = true;
            }
        }

        if (anyDown) {
            return "DOWN";
        } else if (anyDegraded) {
            return "DEGRADED";
        }
        return "UP";
    }
}
