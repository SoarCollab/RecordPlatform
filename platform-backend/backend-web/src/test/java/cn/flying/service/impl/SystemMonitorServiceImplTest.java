package cn.flying.service.impl;

import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.vo.system.*;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.response.BlockChainMessage;
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
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private BlockChainService blockChainService;

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

            BlockChainMessage chainMessage = new BlockChainMessage();
            chainMessage.setTransactionCount(1000L);
            when(blockChainService.getCurrentBlockChainMessage()).thenReturn(Result.success(chainMessage));

            SystemStatsVO result = systemMonitorService.getSystemStats();

            assertThat(result).isNotNull();
            assertThat(result.getTotalUsers()).isEqualTo(100L);
            assertThat(result.getTotalFiles()).isEqualTo(500L);
            assertThat(result.getTodayDownloads()).isEqualTo(50L);
            assertThat(result.getTotalTransactions()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("should return zero values when blockchain service fails")
        void shouldReturnZeroWhenBlockchainFails() {
            when(accountMapper.selectCount(any())).thenReturn(100L);
            when(fileMapper.selectCount(any())).thenReturn(500L);
            when(operationLogMapper.selectOperationsBetween(any(), any())).thenReturn(50L);
            when(blockChainService.getCurrentBlockChainMessage()).thenThrow(new RuntimeException("Chain error"));

            SystemStatsVO result = systemMonitorService.getSystemStats();

            assertThat(result).isNotNull();
            assertThat(result.getTotalTransactions()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should handle null blockchain response gracefully")
        void shouldHandleNullBlockchainResponse() {
            when(accountMapper.selectCount(any())).thenReturn(100L);
            when(fileMapper.selectCount(any())).thenReturn(500L);
            when(operationLogMapper.selectOperationsBetween(any(), any())).thenReturn(50L);
            when(blockChainService.getCurrentBlockChainMessage()).thenReturn(null);

            SystemStatsVO result = systemMonitorService.getSystemStats();

            assertThat(result).isNotNull();
            assertThat(result.getTotalTransactions()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should return fallback values on database error")
        void shouldReturnFallbackOnDbError() {
            when(accountMapper.selectCount(any())).thenThrow(new RuntimeException("DB error"));

            SystemStatsVO result = systemMonitorService.getSystemStats();

            assertThat(result).isNotNull();
            assertThat(result.getTotalUsers()).isEqualTo(0L);
            assertThat(result.getTotalFiles()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("getChainStatus Tests")
    class GetChainStatusTests {

        @Test
        @DisplayName("should return healthy chain status when chain is active")
        void shouldReturnHealthyChainStatus() {
            BlockChainMessage chainMessage = new BlockChainMessage();
            chainMessage.setBlockNumber(12345L);
            chainMessage.setTransactionCount(10000L);
            chainMessage.setFailedTransactionCount(5L);
            when(blockChainService.getCurrentBlockChainMessage()).thenReturn(Result.success(chainMessage));

            ChainStatusVO result = systemMonitorService.getChainStatus();

            assertThat(result).isNotNull();
            assertThat(result.getBlockNumber()).isEqualTo(12345L);
            assertThat(result.getTransactionCount()).isEqualTo(10000L);
            assertThat(result.getFailedTransactionCount()).isEqualTo(5L);
            assertThat(result.getHealthy()).isTrue();
        }

        @Test
        @DisplayName("should return unhealthy status when block number is zero")
        void shouldReturnUnhealthyWhenBlockNumberIsZero() {
            BlockChainMessage chainMessage = new BlockChainMessage();
            chainMessage.setBlockNumber(0L);
            when(blockChainService.getCurrentBlockChainMessage()).thenReturn(Result.success(chainMessage));

            ChainStatusVO result = systemMonitorService.getChainStatus();

            assertThat(result).isNotNull();
            assertThat(result.getHealthy()).isFalse();
        }

        @Test
        @DisplayName("should return fallback chain status on error")
        void shouldReturnFallbackOnError() {
            when(blockChainService.getCurrentBlockChainMessage()).thenThrow(new RuntimeException("Chain error"));

            ChainStatusVO result = systemMonitorService.getChainStatus();

            assertThat(result).isNotNull();
            assertThat(result.getBlockNumber()).isEqualTo(0L);
            assertThat(result.getHealthy()).isFalse();
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
            assertThat(result.getStatus()).isEqualTo("UP");
            assertThat(result.getUptime()).isGreaterThan(0);
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
            assertThat(result.getStatus()).isEqualTo("DOWN");
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
            assertThat(result.getComponents()).containsKey("database");
            assertThat(result.getComponents().get("database").getStatus()).isEqualTo("DOWN");
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

            BlockChainMessage chainMessage = new BlockChainMessage();
            chainMessage.setBlockNumber(100L);
            chainMessage.setTransactionCount(1000L);
            when(blockChainService.getCurrentBlockChainMessage()).thenReturn(Result.success(chainMessage));

            Health upHealth = Health.up().build();
            when(databaseHealthIndicator.health()).thenReturn(upHealth);
            when(redisHealthIndicator.health()).thenReturn(upHealth);
            when(fiscoHealthIndicator.health()).thenReturn(upHealth);
            when(s3HealthIndicator.health()).thenReturn(upHealth);

            MonitorMetricsVO result = systemMonitorService.getMonitorMetrics();

            assertThat(result).isNotNull();
            assertThat(result.getSystemStats()).isNotNull();
            assertThat(result.getChainStatus()).isNotNull();
            assertThat(result.getHealth()).isNotNull();
        }
    }
}
