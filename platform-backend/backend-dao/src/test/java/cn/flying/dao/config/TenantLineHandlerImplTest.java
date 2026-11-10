package cn.flying.dao.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the tenant-line ignore list against SQL identifier quoting.
 */
class TenantLineHandlerImplTest {

    private final TenantLineHandlerImpl handler = new TenantLineHandlerImpl();

    /**
     * System tables must stay unfiltered no matter whether jsqlparser reports the name quoted.
     */
    @Test
    @DisplayName("ignoreTable strips identifier quotes before matching the ignore list")
    void shouldIgnoreSystemTablesWithOrWithoutQuotes() {
        assertThat(handler.ignoreTable("tenant")).isTrue();
        assertThat(handler.ignoreTable("`tenant`")).isTrue();
        assertThat(handler.ignoreTable("\"tenant\"")).isTrue();
        assertThat(handler.ignoreTable("[tenant]")).isTrue();
        assertThat(handler.ignoreTable(" `tenant` ")).isTrue();
    }

    /**
     * Business tables must always receive the tenant filter.
     */
    @Test
    @DisplayName("ignoreTable keeps quoting out of the decision for business tables")
    void shouldFilterBusinessTables() {
        assertThat(handler.ignoreTable("file")).isFalse();
        assertThat(handler.ignoreTable("`file`")).isFalse();
        assertThat(handler.ignoreTable("sys_operation_log")).isFalse();
        assertThat(handler.ignoreTable("`sys_operation_log`")).isFalse();
    }

    /**
     * Defensive handling of missing identifiers keeps the interceptor from failing.
     */
    @Test
    @DisplayName("ignoreTable tolerates null and empty identifiers")
    void shouldTolerateMissingTableName() {
        assertThat(handler.ignoreTable(null)).isFalse();
        assertThat(handler.ignoreTable("")).isFalse();
        assertThat(handler.ignoreTable("``")).isFalse();
    }
}
