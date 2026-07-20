package cn.flying.storage.tenant;

import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextUtilTest {

    /**
     * 清理 Dubbo attachment，避免租户上下文跨测试泄漏。
     */
    @AfterEach
    void clearTenantAttachment() {
        RpcContext.getServerAttachment().removeAttachment("tenant.id");
    }

    /**
     * 验证关键写边界只接受调用方显式传递的合法租户上下文。
     */
    @Test
    @DisplayName("Should require an explicit valid tenant attachment")
    void shouldRequireExplicitValidTenantAttachment() {
        assertThatThrownBy(TenantContextUtil::requireTenantId)
                .isInstanceOf(IllegalStateException.class);

        RpcContext.getServerAttachment().setAttachment("tenant.id", "invalid");
        assertThatThrownBy(TenantContextUtil::requireTenantId)
                .isInstanceOf(IllegalStateException.class);

        RpcContext.getServerAttachment().setAttachment("tenant.id", "-1");
        assertThatThrownBy(TenantContextUtil::requireTenantId)
                .isInstanceOf(IllegalStateException.class);

        RpcContext.getServerAttachment().setAttachment("tenant.id", "42");
        assertThat(TenantContextUtil.requireTenantId()).isEqualTo(42L);
    }

    /**
     * 验证当前分片路径仍解析到租户隔离对象 key。
     */
    @Test
    @DisplayName("Should parse current storage chunk path")
    void shouldParseCurrentStorageChunkPath() {
        TenantContextUtil.ParsedChunkPath parsed =
                TenantContextUtil.parseChunkPath("storage/tenant/42/chunk/hash-1");

        assertThat(parsed).isNotNull();
        assertThat(parsed.tenantId()).isEqualTo(42L);
        assertThat(parsed.objectName()).isEqualTo("hash-1");
        assertThat(parsed.legacyNodeName()).isNull();
        assertThat(parsed.objectPath()).isEqualTo("tenant/42/hash-1");
    }

    /**
     * 验证旧版带租户和逻辑节点的路径可被兼容解析。
     */
    @Test
    @DisplayName("Should parse legacy tenant node path")
    void shouldParseLegacyTenantNodePath() {
        TenantContextUtil.ParsedChunkPath parsed =
                TenantContextUtil.parseChunkPath("minio/tenant/42/node/node-a/hash-1");

        assertThat(parsed).isNotNull();
        assertThat(parsed.tenantId()).isEqualTo(42L);
        assertThat(parsed.objectName()).isEqualTo("hash-1");
        assertThat(parsed.legacyNodeName()).isEqualTo("node-a");
        assertThat(parsed.objectPath()).isEqualTo("tenant/42/hash-1");
    }

    /**
     * 验证旧版无租户路径保留历史对象 key，避免回放到新的 tenant/0 前缀。
     */
    @Test
    @DisplayName("Should parse legacy node path without tenant")
    void shouldParseLegacyNodePathWithoutTenant() {
        TenantContextUtil.ParsedChunkPath parsed =
                TenantContextUtil.parseChunkPath("minio/node/node-a/hash-1");

        assertThat(parsed).isNotNull();
        assertThat(parsed.tenantId()).isEqualTo(0L);
        assertThat(parsed.objectName()).isEqualTo("hash-1");
        assertThat(parsed.legacyNodeName()).isEqualTo("node-a");
        assertThat(parsed.objectPath()).isEqualTo("hash-1");
    }
}
