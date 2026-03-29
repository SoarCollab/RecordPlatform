package cn.flying.service.impl;

import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.vo.system.*;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.response.BlockChainMessage;
import cn.flying.platformapi.response.StorageCapacityVO;
import cn.flying.service.remote.FileRemoteClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("SystemMonitorServiceImpl Tests")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemMonitorServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private SysOperationLogMapper operationLogMapper;

    @Mock
    private FileRemoteClient fileRemoteClient;

    @Mock
    private HealthIndicator databaseHealthIndicator;

    @Mock
    private HealthIndicator redisHealthIndicator;

    @Mock
    private HealthIndicator fiscoHealthIndicator;

    @Mock
    private HealthIndicator s3HealthIndicator;

    @InjectMocks
    private SystemMonitorServiceImpl systemMonitorService;

    @BeforeEach
    void setUp() {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        ReflectionTestUtils.setField(systemMonitorService, "healthCheckExecutor", executor);

        ReflectionTestUtils.setField(systemMonitorService, "monitorMetricsExecutor", (java.util.concurrent.Executor) Runnable::run);

        lenient().when(fileRemoteClient.getStorageCapacity()).thenReturn(
                Result.success(new StorageCapacityVO(
                        2_000_000L,
                        1_000_000L,
                        1_000_000L,
                        false,
                        "prometheus",
                        List.of(),
                        List.of()
                ))
        );
    }

    @Nested
    @DisplayName("getSystemStats Tests")
    class GetSystemStatsTests {

        @Test
        @DisplayName("should return system stats with all fields populated")
        void shouldReturnSystemStatsWithAllFields() {
            when(accountMapper.selectCount(any())).thenReturn(100L);
            when(fileMapper.selectCount(any())).thenReturn(500L);
            when(operationLogMapper.selectOperationsBetween(any(), any())).thenReturn(50L);

            BlockChainMessage chainMessage = new BlockChainMessage(null, 1000L, null, null, null);
            when(fileRemoteClient.getCurrentBlockChainMessage()).thenReturn(Result.success(chainMessage));

            SystemStatsVO result = systemMonitorService.getSystemStats();

            assertThat(result).isNotNull();
            assertThat(result.totalUsers()).isEqualTo(100L);
            assertThat(result.totalFiles()).isEqualTo(500L);
            assertThat(result.todayDownloads()).isEqualTo(50L);
            assertThat(result.totalTransactions()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("should return zero values when blockchain service fails")
        void shouldReturnZeroWhenBlockchainFails() {
            when(accountMapper.selectCount(any())).thenReturn(100L);
            when(fileMapper.selectCount(any())).thenReturn(500L);
            when(operationLogMapper.selectOperationsBetween(any(), any())).thenReturn(50L);
            when(fileRemoteClient.getCurrentBlockChainMessage()).thenThrow(new RuntimeException("Chain error"));

            SystemStatsVO result = systemMonitorService.getSystemStats();

            assertThat(result).isNotNull();
            assertThat(result.totalTransactions()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should handle null blockchain response gracefully")
        void shouldHandleNullBlockchainResponse() {
            when(accountMapper.selectCount(any())).thenReturn(100L);
            when(fileMapper.selectCount(any())).thenReturn(500L);
            when(operationLogMapper.selectOperationsBetween(any(), any())).thenReturn(50L);
            when(fileRemoteClient.getCurrentBlockChainMessage()).thenReturn(null);

            SystemStatsVO result = systemMonitorService.getSystemStats();

            assertThat(result).isNotNull();
            assertThat(result.totalTransactions()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should return fallback values on database error")
        void shouldReturnFallbackOnDbError() {
            when(accountMapper.selectCount(any())).thenThrow(new RuntimeException("DB error"));

            SystemStatsVO result = systemMonitorService.getSystemStats();

            assertThat(result).isNotNull();
            assertThat(result.totalUsers()).isEqualTo(0L);
            assertThat(result.totalFiles()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("getChainStatus Tests")
    class GetChainStatusTests {

        @Test
        @DisplayName("should return healthy chain status when chain is active")
        void shouldReturnHealthyChainStatus() {
            BlockChainMessage chainMessage = new BlockChainMessage(12345L, 10000L, 5L, 4, "BSN_FISCO");
            when(fileRemoteClient.getCurrentBlockChainMessage()).thenReturn(Result.success(chainMessage));

            ChainStatusVO result = systemMonitorService.getChainStatus();

            assertThat(result).isNotNull();
            assertThat(result.blockNumber()).isEqualTo(12345L);
            assertThat(result.transactionCount()).isEqualTo(10000L);
            assertThat(result.failedTransactionCount()).isEqualTo(5L);
            assertThat(result.nodeCount()).isEqualTo(4);
            assertThat(result.chainType()).isEqualTo("BSN_FISCO");
            assertThat(result.healthy()).isTrue();
        }

        @Test
        @DisplayName("should return healthy status when service is reachable even if block number is zero")
        void shouldReturnHealthyWhenBlockNumberIsZero() {
            BlockChainMessage chainMessage = new BlockChainMessage(0L, null, null, 1, "LOCAL_FISCO");
            when(fileRemoteClient.getCurrentBlockChainMessage()).thenReturn(Result.success(chainMessage));

            ChainStatusVO result = systemMonitorService.getChainStatus();

            assertThat(result).isNotNull();
            assertThat(result.blockNumber()).isEqualTo(0L);
            assertThat(result.nodeCount()).isEqualTo(1);
            assertThat(result.chainType()).isEqualTo("LOCAL_FISCO");
            assertThat(result.healthy()).isTrue();
        }

        @Test
        @DisplayName("should return unhealthy status when blockchain service returns non-success result")
        void shouldReturnUnhealthyWhenResultNotSuccess() {
            when(fileRemoteClient.getCurrentBlockChainMessage())
                    .thenReturn(Result.error(ResultEnum.BLOCKCHAIN_ERROR, (BlockChainMessage) null));

            ChainStatusVO result = systemMonitorService.getChainStatus();

            assertThat(result).isNotNull();
            assertThat(result.healthy()).isFalse();
        }

        @Test
        @DisplayName("should return fallback chain status on error")
        void shouldReturnFallbackOnError() {
            when(fileRemoteClient.getCurrentBlockChainMessage()).thenThrow(new RuntimeException("Chain error"));

            ChainStatusVO result = systemMonitorService.getChainStatus();

            assertThat(result).isNotNull();
            assertThat(result.blockNumber()).isEqualTo(0L);
            assertThat(result.healthy()).isFalse();
        }
    }

    @Nested
    @DisplayName("getSystemHealth Tests")
    class GetSystemHealthTests {

        @Test
        @DisplayName("should return UP when all components are healthy")
        void shouldReturnUpWhenAllHealthy() {
            Health upHealth = Health.up().build();
            when(databaseHealthIndicator.health()).thenReturn(upHealth);
            when(redisHealthIndicator.health()).thenReturn(upHealth);
            when(fiscoHealthIndicator.health()).thenReturn(upHealth);
            when(s3HealthIndicator.health()).thenReturn(upHealth);

             SystemHealthVO result = systemMonitorService.getSystemHealth();

             assertThat(result).isNotNull();
             assertThat(result.status()).isEqualTo("UP");
             assertThat(result.uptime()).isGreaterThanOrEqualTo(0);
         }

        @Test
        @DisplayName("should return DOWN when any component is down")
        void shouldReturnDownWhenAnyComponentDown() {
            Health upHealth = Health.up().build();
            Health downHealth = Health.down().withDetail("error", "Connection failed").build();
            when(databaseHealthIndicator.health()).thenReturn(upHealth);
            when(redisHealthIndicator.health()).thenReturn(downHealth);
            when(fiscoHealthIndicator.health()).thenReturn(upHealth);
            when(s3HealthIndicator.health()).thenReturn(upHealth);

            SystemHealthVO result = systemMonitorService.getSystemHealth();

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo("DOWN");
        }

        @Test
        @DisplayName("should handle health check exception gracefully")
        void shouldHandleHealthCheckException() {
            Health upHealth = Health.up().build();
            when(databaseHealthIndicator.health()).thenThrow(new RuntimeException("Check failed"));
            when(redisHealthIndicator.health()).thenReturn(upHealth);
            when(fiscoHealthIndicator.health()).thenReturn(upHealth);
            when(s3HealthIndicator.health()).thenReturn(upHealth);

            SystemHealthVO result = systemMonitorService.getSystemHealth();

            assertThat(result).isNotNull();
            assertThat(result.components()).containsKey("database");
            assertThat(result.components().get("database").status()).isEqualTo("DOWN");
        }
    }

    @Nested
    @DisplayName("getStorageCapacity Tests")
    class GetStorageCapacityTests {

        @Test
        @DisplayName("should return storage capacity from remote service when available")
        void shouldReturnStorageCapacityFromRemoteServiceWhenAvailable() {
            StorageCapacityVO remoteCapacity = new StorageCapacityVO(
                    3_000_000L,
                    2_100_000L,
                    900_000L,
                    false,
                    "prometheus",
                    List.of(),
                    List.of()
            );
            when(fileRemoteClient.getStorageCapacity()).thenReturn(Result.success(remoteCapacity));

            StorageCapacityVO result = systemMonitorService.getStorageCapacity();

            assertThat(result).isEqualTo(remoteCapacity);
            assertThat(result.degraded()).isFalse();
            assertThat(result.source()).isEqualTo("prometheus");
        }

        @Test
        @DisplayName("should fallback to estimate when remote service fails")
        void shouldFallbackToEstimateWhenRemoteServiceFails() {
            when(fileRemoteClient.getStorageCapacity()).thenReturn(Result.error(ResultEnum.FILE_SERVICE_ERROR, null));
            when(fileMapper.selectCount(any())).thenReturn(10L);

            StorageCapacityVO result = systemMonitorService.getStorageCapacity();

            assertThat(result.degraded()).isTrue();
            assertThat(result.source()).isEqualTo("fallback-estimate");
            assertThat(result.usedCapacityBytes()).isEqualTo(10L * 1024 * 1024);
        }
    }

    @Nested
    @DisplayName("getMonitorMetrics Tests")
    class GetMonitorMetricsTests {

        @Test
        @DisplayName("should aggregate all metrics")
        void shouldAggregateAllMetrics() {
            when(accountMapper.selectCount(any())).thenReturn(100L);
            when(fileMapper.selectCount(any())).thenReturn(500L);
            when(operationLogMapper.selectOperationsBetween(any(), any())).thenReturn(50L);

            BlockChainMessage chainMessage = new BlockChainMessage(100L, 1000L, null, null, null);
            when(fileRemoteClient.getCurrentBlockChainMessage()).thenReturn(Result.success(chainMessage));

            Health upHealth = Health.up().build();
            when(databaseHealthIndicator.health()).thenReturn(upHealth);
            when(redisHealthIndicator.health()).thenReturn(upHealth);
            when(fiscoHealthIndicator.health()).thenReturn(upHealth);
            when(s3HealthIndicator.health()).thenReturn(upHealth);

            MonitorMetricsVO result = systemMonitorService.getMonitorMetrics();

            assertThat(result).isNotNull();
            assertThat(result.systemStats()).isNotNull();
            assertThat(result.chainStatus()).isNotNull();
            assertThat(result.health()).isNotNull();
        }

        @Test
        @DisplayName("should dispatch metric tasks via configured executor (not commonPool)")
        void shouldUseConfiguredExecutor() {
            AtomicInteger submitted = new AtomicInteger();
            java.util.concurrent.Executor countingExecutor = runnable -> {
                submitted.incrementAndGet();
                runnable.run();
            };
            ReflectionTestUtils.setField(systemMonitorService, "monitorMetricsExecutor", countingExecutor);

            when(accountMapper.selectCount(any())).thenReturn(1L);
            when(fileMapper.selectCount(any())).thenReturn(1L);
            when(operationLogMapper.selectOperationsBetween(any(), any())).thenReturn(0L);
            when(fileRemoteClient.getCurrentBlockChainMessage()).thenReturn(Result.success(new BlockChainMessage(null, null, null, null, null)));

            Health upHealth = Health.up().build();
            when(databaseHealthIndicator.health()).thenReturn(upHealth);
            when(redisHealthIndicator.health()).thenReturn(upHealth);
            when(fiscoHealthIndicator.health()).thenReturn(upHealth);
            when(s3HealthIndicator.health()).thenReturn(upHealth);

            systemMonitorService.getMonitorMetrics();

            assertThat(submitted.get()).isEqualTo(3);
        }
    }
}
