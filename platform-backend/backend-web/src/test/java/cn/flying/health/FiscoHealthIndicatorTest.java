package cn.flying.health;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.BlockChainMessage;
import cn.flying.service.remote.FileRemoteClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("FiscoHealthIndicator Tests")
class FiscoHealthIndicatorTest {

    /**
     * 验证默认公开健康检查不会触发 FISCO Dubbo RPC。
     */
    @Test
    void health_shouldNotCallRemoteRpcWhenDisabled() {
        FileRemoteClient fileRemoteClient = mock(FileRemoteClient.class);
        FiscoHealthIndicator indicator = new FiscoHealthIndicator();
        ReflectionTestUtils.setField(indicator, "fileRemoteClient", fileRemoteClient);
        ReflectionTestUtils.setField(indicator, "remoteRpcHealthEnabled", false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("remoteCheck", "disabled");
        verifyNoInteractions(fileRemoteClient);
    }

    /**
     * 验证显式启用远程检查时仍能返回 FISCO 状态。
     */
    @Test
    void health_shouldCallRemoteRpcWhenEnabled() {
        FileRemoteClient fileRemoteClient = mock(FileRemoteClient.class);
        when(fileRemoteClient.getCurrentBlockChainMessage()).thenReturn(Result.success(
                new BlockChainMessage(1L, 2L, 0L, 1, "LOCAL_FISCO")));
        FiscoHealthIndicator indicator = new FiscoHealthIndicator();
        ReflectionTestUtils.setField(indicator, "fileRemoteClient", fileRemoteClient);
        ReflectionTestUtils.setField(indicator, "remoteRpcHealthEnabled", true);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("blockNumber", 1L);
    }
}
