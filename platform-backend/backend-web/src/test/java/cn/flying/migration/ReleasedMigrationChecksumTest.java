package cn.flying.migration;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleasedMigrationChecksumTest {

    private static final String RELEASE_TAG = "v0.0.2";
    private static final MigrationVersion RELEASED_MAX_VERSION = MigrationVersion.fromVersion("1.7.0");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^V([^_]+)__.+\\.sql$");
    private static final String MANIFEST_SHA256 =
            "623183fdc7335b338a8c8f2845a7504abbb2fb42e36bd0fc62e53cdf1dafb5a6";
    private static final Set<String> RELEASED_PATHS = Set.of(
            "db/migration/V1.0.0__init_schema.sql",
            "db/migration/V1.0.1__add_account_nickname.sql",
            "db/migration/V1.1.0__add_friend_system.sql",
            "db/migration/V1.2.0__audit_views_and_share_nullable.sql",
            "db/migration/V1.2.1__add_anomaly_check_procedure.sql",
            "db/migration/V1.2.2__add_ticket_view_time.sql",
            "db/migration/V1.2.3__add_operation_log_backup_procedure.sql",
            "db/migration/V1.2.4__add_error_rate_threshold_config.sql",
            "db/migration/V1.2.5__replace_select_star_views_and_procedure.sql",
            "db/migration/V1.2.6__file_query_indexes.sql",
            "db/migration/V1.3.0__quota_governance.sql",
            "db/migration/V1.3.1__file_query_keyword_mode_indexes.sql",
            "db/migration/V1.3.2__quota_rollout_audit.sql",
            "db/migration/V1.4.0__file_version_chain.sql",
            "db/migration/V1.5.0__integrity_alert.sql",
            "db/migration/V1.5.1__expand_file_param_column.sql",
            "db/migration/V1.6.0__remove_auto_increment.sql",
            "db/migration/V1.7.0__add_soft_delete_columns.sql");

    @TempDir
    Path tempDir;

    /**
     * Verifies every migration published by v0.0.2 still exists with the reviewed SHA-256.
     */
    @Test
    @DisplayName("should keep every v0.0.2 migration byte stable")
    void shouldKeepEveryReleasedMigrationByteStable() throws IOException {
        Path resourceRoot = resolveResourceRoot();
        Path manifest = resourceRoot.resolve("db/released-migration-checksums.csv");

        assertEquals(MANIFEST_SHA256, sha256(manifest),
                "Released checksum manifest changed; review the release source and update the test anchor explicitly");
        List<ManifestEntry> entries = readManifest(manifest);
        assertEquals(RELEASED_PATHS, entries.stream().map(ManifestEntry::path).collect(java.util.stream.Collectors.toSet()));
        verifyReleasedMigrations(resourceRoot, entries);
    }

    /**
     * Proves the checksum gate rejects a byte change in a released migration.
     */
    @Test
    @DisplayName("should fail closed when a released migration is tampered")
    void shouldFailClosedWhenReleasedMigrationIsTampered() throws IOException {
        Path resourceRoot = resolveResourceRoot();
        List<ManifestEntry> entries = readManifest(resourceRoot.resolve("db/released-migration-checksums.csv"));
        copyReleasedMigrations(resourceRoot, tempDir, entries);
        Files.writeString(
                tempDir.resolve("db/migration/V1.0.0__init_schema.sql"),
                "\n-- tampered\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> verifyReleasedMigrations(tempDir, entries));

        assertTrue(exception.getMessage().contains("V1.0.0__init_schema.sql"));
        assertTrue(exception.getMessage().contains("checksum mismatch"));
    }

    /**
     * Proves the checksum gate rejects a deleted released path.
     */
    @Test
    @DisplayName("should fail closed when a released migration is deleted")
    void shouldFailClosedWhenReleasedMigrationIsDeleted() throws IOException {
        Path resourceRoot = resolveResourceRoot();
        List<ManifestEntry> entries = readManifest(resourceRoot.resolve("db/released-migration-checksums.csv"));
        copyReleasedMigrations(resourceRoot, tempDir, entries);
        Files.delete(tempDir.resolve("db/migration/V1.0.1__add_account_nickname.sql"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> verifyReleasedMigrations(tempDir, entries));

        assertTrue(exception.getMessage().contains("Released migration path set mismatch"));
        assertTrue(exception.getMessage().contains("V1.0.1__add_account_nickname.sql"));
    }

    /**
     * Proves the checksum gate rejects a released migration moved to another released-range version.
     */
    @Test
    @DisplayName("should fail closed when a released migration is renumbered")
    void shouldFailClosedWhenReleasedMigrationIsRenumbered() throws IOException {
        Path resourceRoot = resolveResourceRoot();
        List<ManifestEntry> entries = readManifest(resourceRoot.resolve("db/released-migration-checksums.csv"));
        copyReleasedMigrations(resourceRoot, tempDir, entries);
        Files.move(
                tempDir.resolve("db/migration/V1.0.1__add_account_nickname.sql"),
                tempDir.resolve("db/migration/V1.0.2__add_account_nickname.sql"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> verifyReleasedMigrations(tempDir, entries));

        assertTrue(exception.getMessage().contains("Released migration path set mismatch"));
        assertTrue(exception.getMessage().contains("V1.0.1__add_account_nickname.sql"));
        assertTrue(exception.getMessage().contains("V1.0.2__add_account_nickname.sql"));
    }

    /**
     * Proves the checksum gate rejects an unmanifested migration posing as pre-release history.
     */
    @Test
    @DisplayName("should fail closed when an extra migration masquerades as released")
    void shouldFailClosedWhenExtraMigrationMasqueradesAsReleased() throws IOException {
        Path resourceRoot = resolveResourceRoot();
        List<ManifestEntry> entries = readManifest(resourceRoot.resolve("db/released-migration-checksums.csv"));
        copyReleasedMigrations(resourceRoot, tempDir, entries);
        Files.writeString(
                tempDir.resolve("db/migration/V1.0.2__unexpected_released_history.sql"),
                "-- unexpected released-range migration\n",
                StandardCharsets.UTF_8);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> verifyReleasedMigrations(tempDir, entries));

        assertTrue(exception.getMessage().contains("Released migration path set mismatch"));
        assertTrue(exception.getMessage().contains("V1.0.2__unexpected_released_history.sql"));
    }

    /**
     * Parses and validates the released migration checksum manifest.
     *
     * @param manifest manifest file
     * @return validated entries
     */
    private List<ManifestEntry> readManifest(Path manifest) throws IOException {
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals("release_tag,version,path,sha256")) {
            throw new IllegalStateException("Invalid released migration manifest header");
        }

        Set<String> versions = new HashSet<>();
        Set<String> paths = new HashSet<>();
        List<ManifestEntry> entries = lines.stream()
                .skip(1)
                .map(this::parseManifestEntry)
                .toList();
        for (ManifestEntry entry : entries) {
            if (!RELEASE_TAG.equals(entry.releaseTag())) {
                throw new IllegalStateException("Unexpected release tag: " + entry.releaseTag());
            }
            if (!entry.path().startsWith("db/migration/V" + entry.version() + "__")) {
                throw new IllegalStateException("Version/path mismatch: " + entry.path());
            }
            if (!versions.add(entry.version())) {
                throw new IllegalStateException("Duplicate released version: " + entry.version());
            }
            if (!paths.add(entry.path())) {
                throw new IllegalStateException("Duplicate released path: " + entry.path());
            }
        }
        return entries;
    }

    /**
     * Parses one strict four-column manifest row.
     *
     * @param line manifest row
     * @return parsed entry
     */
    private ManifestEntry parseManifestEntry(String line) {
        String[] columns = line.split(",", -1);
        if (columns.length != 4 || !columns[3].matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Invalid released migration manifest row: " + line);
        }
        return new ManifestEntry(columns[0], columns[1], columns[2], columns[3]);
    }

    /**
     * Verifies declared files exist and match their immutable checksums.
     *
     * @param resourceRoot resource root containing declared paths
     * @param entries manifest entries
     */
    private void verifyReleasedMigrations(Path resourceRoot, List<ManifestEntry> entries) {
        Set<String> expectedPaths = entries.stream()
                .map(ManifestEntry::path)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> actualPaths = discoverReleasedRangePaths(resourceRoot);
        if (!expectedPaths.equals(actualPaths)) {
            Set<String> missingPaths = new HashSet<>(expectedPaths);
            missingPaths.removeAll(actualPaths);
            Set<String> unexpectedPaths = new HashSet<>(actualPaths);
            unexpectedPaths.removeAll(expectedPaths);
            throw new IllegalStateException(
                    "Released migration path set mismatch: missing=" + missingPaths
                            + " unexpected=" + unexpectedPaths);
        }

        for (ManifestEntry entry : entries) {
            Path migration = resourceRoot.resolve(entry.path()).normalize();
            if (!migration.startsWith(resourceRoot.normalize()) || !Files.isRegularFile(migration)) {
                throw new IllegalStateException("Released migration missing: " + entry.path());
            }
            try {
                String actual = sha256(migration);
                if (!entry.sha256().equals(actual)) {
                    throw new IllegalStateException(
                            "Released migration checksum mismatch: " + entry.path()
                                    + " expected=" + entry.sha256() + " actual=" + actual);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read released migration: " + entry.path(), exception);
            }
        }
    }

    /**
     * Discovers every versioned migration at or below the v0.0.2 release ceiling.
     *
     * @param resourceRoot resource root containing migration files
     * @return normalized released-range resource paths
     */
    private Set<String> discoverReleasedRangePaths(Path resourceRoot) {
        Path migrationDir = resourceRoot.resolve("db/migration");
        try (var stream = Files.list(migrationDir)) {
            Set<String> paths = new HashSet<>();
            for (Path migration : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("V"))
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList()) {
                String filename = migration.getFileName().toString();
                Matcher matcher = VERSION_PATTERN.matcher(filename);
                if (!matcher.matches()) {
                    throw new IllegalStateException("Invalid migration filename: " + filename);
                }
                MigrationVersion version = MigrationVersion.fromVersion(matcher.group(1));
                if (version.compareTo(RELEASED_MAX_VERSION) <= 0) {
                    paths.add("db/migration/" + filename);
                }
            }
            return paths;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot enumerate released migration paths", exception);
        }
    }

    /**
     * Copies released migrations into an isolated directory for negative testing.
     *
     * @param sourceRoot source resource root
     * @param targetRoot target resource root
     * @param entries manifest entries
     */
    private void copyReleasedMigrations(Path sourceRoot, Path targetRoot, List<ManifestEntry> entries)
            throws IOException {
        for (ManifestEntry entry : entries) {
            Path target = targetRoot.resolve(entry.path());
            Files.createDirectories(target.getParent());
            Files.copy(sourceRoot.resolve(entry.path()), target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Computes the lower-case SHA-256 of a file.
     *
     * @param file file to hash
     * @return lower-case hexadecimal SHA-256
     */
    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Resolves the resource root from module and reactor working directories.
     *
     * @return backend-web main resource root
     */
    private Path resolveResourceRoot() {
        Path moduleRoot = Path.of("src/main/resources");
        if (Files.isDirectory(moduleRoot)) {
            return moduleRoot;
        }
        return Path.of("backend-web/src/main/resources");
    }

    private record ManifestEntry(String releaseTag, String version, String path, String sha256) {
    }
}
