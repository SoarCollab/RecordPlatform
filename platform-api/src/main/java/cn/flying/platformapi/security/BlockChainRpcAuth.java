package cn.flying.platformapi.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 区块链 Dubbo RPC 调用鉴权常量和校验工具。
 */
public final class BlockChainRpcAuth {

    public static final String TOKEN_ATTACHMENT_KEY = "record-platform.blockchain.rpc-token";
    public static final String TOKEN_PROPERTY_NAME = "record-platform.rpc.blockchain-token";

    private BlockChainRpcAuth() {
    }

    /**
     * 判断令牌是否可用于区块链 RPC 鉴权。
     */
    public static boolean hasToken(String token) {
        return token != null && !token.isBlank();
    }

    /**
     * 使用常量时间比较验证传入的区块链 RPC 令牌。
     */
    public static boolean matches(String expectedToken, String actualToken) {
        if (!hasToken(expectedToken) || !hasToken(actualToken)) {
            return false;
        }
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = actualToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
