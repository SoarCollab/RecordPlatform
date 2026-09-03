package cn.flying.dao.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SysOperationLogMapperContractTest {

    /**
     * Locks the local interceptor bypass to statements that explicitly enforce tenant and cutoff predicates.
     */
    @Test
    @DisplayName("should keep audit backup and cleanup explicitly tenant scoped")
    void shouldKeepAuditBackupAndCleanupExplicitlyTenantScoped() throws Exception {
        assertTenantInterceptorBypassed("insertOperationLogBackup");
        assertTenantInterceptorBypassed("deleteOperationLogsBefore");

        String mapperXml = loadMapperXml().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String backupSql = extractStatement(mapperXml, "<insert id=\"insertoperationlogbackup\"", "</insert>");
        String deleteSql = extractStatement(mapperXml, "<delete id=\"deleteoperationlogsbefore\"", "</delete>");

        String targetColumns = backupSql.substring(backupSql.indexOf('(') + 1, backupSql.indexOf(") select"));
        String selectedColumns = backupSql.substring(backupSql.indexOf(") select") + 8, backupSql.indexOf(" from `sys_operation_log`"));

        assertEquals(1, countOccurrences(targetColumns, "`tenant_id`"));
        assertEquals(1, countOccurrences(selectedColumns, "`tenant_id`"));
        assertTrue(backupSql.contains("where `tenant_id` = #{tenantid}"));
        assertTrue(backupSql.contains("and `operation_time` &lt; #{cutofftime}"));
        assertTrue(deleteSql.contains("where `tenant_id` = #{tenantid}"));
        assertTrue(deleteSql.contains("and `operation_time` &lt; #{cutofftime}"));
    }

    /**
     * Verifies the monitoring query counts only one canonical type in a half-open window.
     */
    @Test
    @DisplayName("should count only the requested operation type in a half-open window")
    void shouldCountOnlyRequestedOperationTypeInHalfOpenWindow() throws IOException {
        String mapperXml = loadMapperXml().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String sql = extractStatement(
                mapperXml,
                "<select id=\"countoperationsbytypebetween\"",
                "</select>");

        assertTrue(sql.contains("operation_type = #{operationtype}"));
        assertTrue(sql.contains("operation_time >= #{starttime}"));
        assertTrue(sql.contains("operation_time &lt; #{endtime}"));
    }

    private void assertTenantInterceptorBypassed(String methodName) throws NoSuchMethodException {
        Method method = SysOperationLogMapper.class.getMethod(
                methodName,
                Long.class,
                LocalDateTime.class);
        InterceptorIgnore annotation = method.getAnnotation(InterceptorIgnore.class);

        assertNotNull(annotation);
        assertEquals("true", annotation.tenantLine());
    }

    private String loadMapperXml() throws IOException {
        try (InputStream input = SysOperationLogMapper.class.getResourceAsStream(
                "/mapper/SysOperationLogMapper.xml")) {
            assertNotNull(input, "SysOperationLogMapper.xml must be available on the test classpath");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String extractStatement(String xml, String startMarker, String endMarker) {
        int start = xml.indexOf(startMarker);
        assertTrue(start >= 0, "Missing mapper statement: " + startMarker);
        int end = xml.indexOf(endMarker, start);
        assertTrue(end > start, "Unclosed mapper statement: " + startMarker);
        return xml.substring(start, end + endMarker.length());
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
