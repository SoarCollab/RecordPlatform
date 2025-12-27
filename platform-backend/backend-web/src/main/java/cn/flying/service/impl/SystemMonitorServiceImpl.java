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

    @DubboReference(id = "blockChainServiceSystemMonitor", version = BlockChainService.VERSION, timeout = 3000, retries = 0)
    private BlockChainService blockChainService;

    // Health indicators injected by name
    @Resource(name = "database")
    private HealthIndicator databaseHealthIndicator;

    @Resource(name = "redis")
    private HealthIndicator redisHealthIndicator;

    @Resource(name = "fisco")
    private HealthIndicator fiscoHealthIndicator;

    @Resource(name = "minio")
    private HealthIndicator minioHealthIndicator;

    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 5;
    private final ExecutorService healthCheckExecutor = Executors.newFixedThreadPool(4);

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
            // TODO: 从 MinIO 服务获取实际存储容量
            long totalStorage = totalFiles * 1024 * 1024; // 临时估算值

            return SystemStatsVO.builder()
                    .totalUsers(totalUsers)
                    .totalFiles(totalFiles)
                    .totalStorage(totalStorage)
                    .totalTransactions(totalTransactions)
                    .todayUploads(todayUploads)
                    .todayDownloads(todayDownloads != null ? todayDownloads : 0L)
                    .build();
        } catch (Exception e) {
            log.error("Failed to get system stats", e);
            return SystemStatsVO.builder()
                    .totalUsers(0L)
                    .totalFiles(0L)
                    .totalStorage(0L)
                    .totalTransactions(0L)
                    .todayUploads(0L)
                    .todayDownloads(0L)
                    .build();
        }
    }

    @Override
    public ChainStatusVO getChainStatus() {
        try {
            Result<BlockChainMessage> result = blockChainService.getCurrentBlockChainMessage();
            if (result != null && result.getData() != null) {
                BlockChainMessage message = result.getData();
                Long blockNumber = message.getBlockNumber() != null ? message.getBlockNumber() : 0L;
                Long transactionCount = message.getTransactionCount() != null ? message.getTransactionCount() : 0L;
                Long failedTransactionCount = message.getFailedTransactionCount() != null ? message.getFailedTransactionCount() : 0L;
                return ChainStatusVO.builder()
                        .blockNumber(blockNumber)
                        .transactionCount(transactionCount)
                        .failedTransactionCount(failedTransactionCount)
                        .nodeCount(4) // 默认值，实际应从链上获取
                        .chainType("LOCAL_FISCO") // 从配置获取
                        .healthy(blockNumber > 0)
                        .lastUpdateTime(System.currentTimeMillis())
                        .build();
            }
        } catch (Exception e) {
            log.error("Failed to get chain status", e);
        }

        return ChainStatusVO.builder()
                .blockNumber(0L)
                .transactionCount(0L)
                .failedTransactionCount(0L)
                .nodeCount(0)
                .chainType("LOCAL_FISCO")
                .healthy(false)
                .lastUpdateTime(System.currentTimeMillis())
                .build();
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
                components.put("storage", checkHealth(minioHealthIndicator, "storage"))));

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

        return SystemHealthVO.builder()
                .status(overallStatus)
                .components(components)
                .uptime(uptime)
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }

    @Override
    public MonitorMetricsVO getMonitorMetrics() {
        // 并发获取所有指标
        CompletableFuture<SystemStatsVO> statsFuture = CompletableFuture.supplyAsync(this::getSystemStats);
        CompletableFuture<ChainStatusVO> chainFuture = CompletableFuture.supplyAsync(this::getChainStatus);
        CompletableFuture<SystemHealthVO> healthFuture = CompletableFuture.supplyAsync(this::getSystemHealth);

        try {
            return MonitorMetricsVO.builder()
                    .systemStats(statsFuture.get(10, TimeUnit.SECONDS))
                    .chainStatus(chainFuture.get(10, TimeUnit.SECONDS))
                    .health(healthFuture.get(10, TimeUnit.SECONDS))
                    .build();
        } catch (Exception e) {
            log.error("Failed to get monitor metrics", e);
            return MonitorMetricsVO.builder()
                    .systemStats(getSystemStats())
                    .chainStatus(getChainStatus())
                    .health(getSystemHealth())
                    .build();
        }
    }

    private ComponentHealthVO checkHealth(HealthIndicator indicator, String name) {
        try {
            Health health = indicator.health();
            Map<String, Object> details = new HashMap<>(health.getDetails());
            return ComponentHealthVO.builder()
                    .status(health.getStatus().getCode())
                    .details(details.isEmpty() ? null : details)
                    .build();
        } catch (Exception e) {
            log.warn("Health check failed for {}: {}", name, e.getMessage());
            return ComponentHealthVO.builder()
                    .status("DOWN")
                    .details(Map.of("error", e.getMessage()))
                    .build();
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
