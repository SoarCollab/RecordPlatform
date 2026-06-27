package cn.flying.platformapi.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BlockChain RPC auth helper")
class BlockChainRpcAuthTest {

    /**
     * 验证空白令牌不会通过基础配置校验。
     */
    @Test
    void hasToken_shouldRejectBlankToken() {
        assertThat(BlockChainRpcAuth.hasToken(null)).isFalse();
        assertThat(BlockChainRpcAuth.hasToken("")).isFalse();
        assertThat(BlockChainRpcAuth.hasToken("   ")).isFalse();
    }

    /**
     * 验证共享令牌必须完全一致才允许访问区块链 RPC。
     */
    @Test
    void matches_shouldRequireExactSharedToken() {
        assertThat(BlockChainRpcAuth.matches("rpc-token", "rpc-token")).isTrue();
        assertThat(BlockChainRpcAuth.matches("rpc-token", "other-token")).isFalse();
        assertThat(BlockChainRpcAuth.matches("rpc-token", null)).isFalse();
        assertThat(BlockChainRpcAuth.matches(null, "rpc-token")).isFalse();
    }
}
