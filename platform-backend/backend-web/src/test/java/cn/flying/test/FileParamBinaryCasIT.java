package cn.flying.test;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.tenant.TenantContext;
import cn.flying.service.FileService;
import cn.flying.service.impl.FileServiceImpl;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在真实 MySQL 8 的非二进制排序规则下验证最终化 file_param CAS 仍按字节精确比较。
 */
@DisplayName("File parameter binary CAS MySQL integration tests")
class FileParamBinaryCasIT extends BaseIntegrationTest {

    private static final long TENANT_ID = 920_001L;
    private static final long USER_ID = 920_002L;
    private static final long FILE_ID = 920_003L;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private FileService fileService;

    /**
     * 清理本类写入的真实数据库记录和租户上下文。
     */
    @AfterEach
    void cleanDatabaseState() {
        TenantContext.clear();
        jdbcTemplate.update("DELETE FROM file WHERE tenant_id = ?", TENANT_ID);
    }

    /**
     * 证明 general_ci 下大小写或尾随字节不同均更新零行，逐字节相同才更新一行。
     */
    @Test
    void shouldRequireBinaryExactOldFileParamUnderGeneralCiCollation() {
        String collation = jdbcTemplate.queryForObject(
                """
                SELECT COLLATION_NAME
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'file'
                  AND COLUMN_NAME = 'file_param'
                """,
                String.class);
        assertThat(collation).isEqualToIgnoringCase("utf8mb4_general_ci");

        String stored = "{\"phase\":\"CLAIMED\",\"marker\":\"Exact\"}";
        insertPrepareFile(stored);

        assertThat(invokePreparedFileParamCas(
                stored.toLowerCase(Locale.ROOT), "{\"phase\":\"wrong-case\"}"))
                .isFalse();
        assertThat(readFileParam()).isEqualTo(stored);

        assertThat(invokePreparedFileParamCas(
                stored + " ", "{\"phase\":\"wrong-byte-length\"}"))
                .isFalse();
        assertThat(readFileParam()).isEqualTo(stored);

        String next = "{\"phase\":\"CHAIN_ATTESTING\",\"marker\":\"Exact\"}";
        assertThat(invokePreparedFileParamCas(stored, next)).isTrue();
        assertThat(readFileParam()).isEqualTo(next);
    }

    /**
     * 证明 NULL 只匹配 SQL IS NULL 分支，空字符串不能冒充 NULL 快照。
     */
    @Test
    void shouldKeepNullAsASeparateCasState() {
        insertPrepareFile(null);

        assertThat(invokePreparedFileParamCas("", "{\"phase\":\"wrong-null\"}"))
                .isFalse();
        assertThat(readFileParam()).isNull();

        String next = "{\"phase\":\"CLAIMED\"}";
        assertThat(invokePreparedFileParamCas(null, next)).isTrue();
        assertThat(readFileParam()).isEqualTo(next);
    }

    /**
     * 写入一条受当前测试租户约束的 PREPARE 文件。
     *
     * @param fileParam 初始 file_param 快照
     */
    private void insertPrepareFile(String fileParam) {
        jdbcTemplate.update(
                """
                INSERT INTO file (
                    id, tenant_id, uid, file_name, file_param, status, deleted,
                    create_time, version, is_latest, version_group_id
                ) VALUES (?, ?, ?, ?, ?, ?, 0, NOW(), 1, 1, ?)
                """,
                FILE_ID,
                TENANT_ID,
                USER_ID,
                "binary-cas.pdf",
                fileParam,
                FileUploadStatus.PREPARE.getCode(),
                FILE_ID);
    }

    /**
     * 调用生产 CAS 私有边界，使断言覆盖真实 MyBatis SQL 与真实 MySQL 比较语义。
     *
     * @param oldFileParam 期望旧快照
     * @param nextFileParam 目标新快照
     * @return 是否精确更新一行
     */
    private boolean invokePreparedFileParamCas(String oldFileParam, String nextFileParam) {
        FileServiceImpl target = AopTestUtils.getUltimateTargetObject(fileService);
        Boolean updated = TenantContext.callWithTenant(
                TENANT_ID,
                () -> ReflectionTestUtils.invokeMethod(
                        target,
                        "casPreparedFileParam",
                        FILE_ID,
                        USER_ID,
                        oldFileParam,
                        nextFileParam));
        assertThat(TenantContext.isSet()).isFalse();
        return Boolean.TRUE.equals(updated);
    }

    /**
     * 直接回读数据库中的 file_param 字节快照。
     *
     * @return 当前 file_param，可为 NULL
     */
    private String readFileParam() {
        return jdbcTemplate.queryForObject(
                "SELECT file_param FROM file WHERE id = ? AND tenant_id = ?",
                String.class,
                FILE_ID,
                TENANT_ID);
    }
}
