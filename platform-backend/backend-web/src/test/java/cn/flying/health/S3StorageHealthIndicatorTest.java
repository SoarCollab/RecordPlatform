package cn.flying.health;

import cn.flying.platformapi.constant.Result;
import cn.flying.service.remote.FileRemoteClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("S3StorageHealthIndicator Tests")
class S3StorageHealthIndicatorTest {

    /**
     * 验证默认公开健康检查不会触发存储 Dubbo RPC。
     */
    @Test
    void health_shouldNotCallRemoteRpcWhenDisabled() {
        FileRemoteClient fileRemoteClient = mock(FileRemoteClient.class);
        S3StorageHealthIndicator indicator = new S3StorageHealthIndicator();
        ReflectionTestUtils.setField(indicator, "fileRemoteClient", fileRemoteClient);
        ReflectionTestUtils.setField(indicator, "remoteRpcHealthEnabled", false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("remoteCheck", "disabled");
        verifyNoInteractions(fileRemoteClient);
    }

    /**
     * 验证显式启用远程检查时仍能返回存储集群状态。
     */
    @Test
    void health_shouldCallRemoteRpcWhenEnabled() {
        FileRemoteClient fileRemoteClient = mock(FileRemoteClient.class);
        when(fileRemoteClient.getClusterHealth()).thenReturn(Result.success(Map.of("node-a", true, "node-b", true)));
        S3StorageHealthIndicator indicator = new S3StorageHealthIndicator();
        ReflectionTestUtils.setField(indicator, "fileRemoteClient", fileRemoteClient);
        ReflectionTestUtils.setField(indicator, "remoteRpcHealthEnabled", true);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("onlineCount", 2L);
    }
}
