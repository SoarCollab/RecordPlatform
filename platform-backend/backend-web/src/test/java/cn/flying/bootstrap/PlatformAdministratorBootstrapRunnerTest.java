package cn.flying.bootstrap;

import cn.flying.service.AccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests the disabled-by-default, local password-file platform bootstrap boundary. */
@ExtendWith(MockitoExtension.class)
class PlatformAdministratorBootstrapRunnerTest {

    private static final String PASSWORD = "Synthetic-Platform9!Password";

    @Mock
    private AccountService accountService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @TempDir
    Path tempDir;

    /** Reads a restrictive local file and passes only its BCrypt result to the account service. */
    @Test
    void createsPlatformAdministratorFromPasswordFile() throws Exception {
        Path passwordFile = tempDir.resolve("platform-password");
        Files.writeString(passwordFile, PASSWORD + System.lineSeparator());
        restrictIfPosix(passwordFile);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("synthetic-bcrypt-hash");
        PlatformAdministratorBootstrapRunner runner = runner(passwordFile);

        runner.run(null);

        verify(accountService).createPlatformAdministrator(
                "platform-operator", "platform-operator@example.test", "synthetic-bcrypt-hash");
    }

    /** Refuses group/other-readable password files on POSIX filesystems. */
    @Test
    void rejectsBroadPosixPermissionsWithoutExposingPassword() throws Exception {
        Path passwordFile = tempDir.resolve("shared-password");
        Files.writeString(passwordFile, PASSWORD);
        assumeTrue(Files.getFileAttributeView(passwordFile, PosixFileAttributeView.class) != null);
        Files.setPosixFilePermissions(passwordFile, PosixFilePermissions.fromString("rw-r--r--"));
        PlatformAdministratorBootstrapRunner runner = runner(passwordFile);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Platform administrator bootstrap password file permissions are too broad")
                .hasMessageNotContaining(PASSWORD);
    }

    /** Refuses incomplete configuration before reading any local file. */
    @Test
    void rejectsIncompleteConfiguration() {
        PlatformAdministratorBootstrapRunner runner = new PlatformAdministratorBootstrapRunner(
                accountService, passwordEncoder);
        ReflectionTestUtils.setField(runner, "username", "");
        ReflectionTestUtils.setField(runner, "email", "");
        ReflectionTestUtils.setField(runner, "passwordFile", "");

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Platform administrator bootstrap configuration is incomplete");
    }

    /** Refuses malformed identity metadata before reading the password file. */
    @Test
    void rejectsInvalidIdentityConfiguration() {
        PlatformAdministratorBootstrapRunner runner = new PlatformAdministratorBootstrapRunner(
                accountService, passwordEncoder);
        ReflectionTestUtils.setField(runner, "username", "platform-operator");
        ReflectionTestUtils.setField(runner, "email", "invalid-email");
        ReflectionTestUtils.setField(runner, "passwordFile", "unused");

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Platform administrator bootstrap identity is invalid");
    }

    /** Refuses empty and unavailable secret files without exposing their content. */
    @Test
    void rejectsInvalidPasswordFiles() throws Exception {
        Path emptyFile = tempDir.resolve("empty-password");
        Files.createFile(emptyFile);
        restrictIfPosix(emptyFile);

        assertThatThrownBy(() -> runner(emptyFile).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Platform administrator bootstrap password file is invalid");
        assertThatThrownBy(() -> runner(tempDir.resolve("missing-password")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Platform administrator bootstrap password file is invalid");
    }

    /** Refuses a readable secret that does not meet the deterministic password policy. */
    @Test
    void rejectsWeakPassword() throws Exception {
        Path passwordFile = tempDir.resolve("weak-password");
        Files.writeString(passwordFile, "only-lowercase");
        restrictIfPosix(passwordFile);

        assertThatThrownBy(() -> runner(passwordFile).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Platform administrator bootstrap password does not meet policy");
    }

    /** Creates a configured runner without embedding password content in configuration. */
    private PlatformAdministratorBootstrapRunner runner(Path passwordFile) {
        PlatformAdministratorBootstrapRunner runner = new PlatformAdministratorBootstrapRunner(
                accountService, passwordEncoder);
        ReflectionTestUtils.setField(runner, "username", "platform-operator");
        ReflectionTestUtils.setField(runner, "email", "platform-operator@example.test");
        ReflectionTestUtils.setField(runner, "passwordFile", passwordFile.toString());
        return runner;
    }

    /** Applies owner-only permissions where the host filesystem supports POSIX attributes. */
    private void restrictIfPosix(Path passwordFile) throws Exception {
        if (Files.getFileAttributeView(passwordFile, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(passwordFile, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
