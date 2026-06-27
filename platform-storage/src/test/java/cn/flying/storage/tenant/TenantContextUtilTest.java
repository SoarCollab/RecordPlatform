package cn.flying.storage.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextUtilTest {

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
