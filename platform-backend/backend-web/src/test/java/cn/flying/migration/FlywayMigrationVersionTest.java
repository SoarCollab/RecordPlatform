package cn.flying.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywayMigrationVersionTest {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^V([^_]+)__.+\\.sql$");

    /**
     * 验证 Flyway 迁移版本不会复用历史版本号。
     */
    @Test
    @DisplayName("should keep nickname migration on original version and avoid version reuse")
    void shouldKeepNicknameMigrationOnOriginalVersionAndAvoidVersionReuse() throws IOException {
        Path migrationDir = resolveMigrationDir();
        List<String> migrationFiles;
        try (var stream = Files.list(migrationDir)) {
            migrationFiles = stream
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                    .sorted()
                    .toList();
        }

        assertTrue(migrationFiles.contains("V1.5.0__add_account_nickname.sql"));
        assertTrue(migrationFiles.contains("V1.7.4__rename_file_contract_hash_to_transaction_hash.sql"));
        assertFalse(migrationFiles.contains("V1.0.1__add_account_nickname.sql"));
        assertFalse(migrationFiles.contains("V1.5.0__integrity_alert.sql"));

        Set<String> versions = new HashSet<>();
        for (String fileName : migrationFiles) {
            Matcher matcher = VERSION_PATTERN.matcher(fileName);
            assertTrue(matcher.matches(), "Invalid migration filename: " + fileName);
            assertTrue(versions.add(matcher.group(1)), "Duplicate migration version: " + matcher.group(1));
        }
    }

    /**
     * 验证历史列名修复通过新的前向迁移实现，避免继续修改已发布迁移。
     */
    @Test
    @DisplayName("should rename legacy file contract hash column through forward migration")
    void shouldRenameLegacyFileContractHashColumnThroughForwardMigration() throws IOException {
        Path migration = resolveMigrationDir().resolve("V1.7.4__rename_file_contract_hash_to_transaction_hash.sql");
        String sql = Files.readString(migration);

        assertTrue(sql.contains("INFORMATION_SCHEMA.COLUMNS"));
        assertTrue(sql.contains("COLUMN_NAME = 'contract_hash'"));
        assertTrue(sql.contains("COLUMN_NAME = 'transaction_hash'"));
        assertTrue(sql.contains("CHANGE COLUMN `contract_hash` `transaction_hash`"));
    }

    /**
     * 验证迁移中的存储过程声明使用 MySQL 兼容语法。
     */
    @Test
    @DisplayName("should avoid MariaDB-only CREATE PROCEDURE IF NOT EXISTS syntax")
    void shouldAvoidMariaDbOnlyCreateProcedureIfNotExistsSyntax() throws IOException {
        Path migrationDir = resolveMigrationDir();
        try (var stream = Files.list(migrationDir)) {
            List<Path> migrationFiles = stream
                    .filter(path -> path.getFileName().toString().startsWith("V"))
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList();
            for (Path migration : migrationFiles) {
                String sql = Files.readString(migration);
                assertFalse(sql.matches("(?is).*CREATE\\s+PROCEDURE\\s+IF\\s+NOT\\s+EXISTS.*"),
                        "MySQL-incompatible routine declaration in " + migration.getFileName());
            }
        }
    }

    /**
     * 解析不同 Maven 执行目录下的迁移目录路径。
     *
     * @return Flyway 迁移目录
     */
    private Path resolveMigrationDir() {
        Path moduleDir = Path.of("src/main/resources/db/migration");
        if (Files.isDirectory(moduleDir)) {
            return moduleDir;
        }
        return Path.of("backend-web/src/main/resources/db/migration");
    }
}
