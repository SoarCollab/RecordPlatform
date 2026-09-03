package cn.flying.bootstrap;

import cn.flying.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Performs the explicit one-time creation of the first system-tenant platform administrator.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "platform.bootstrap", name = "enabled", havingValue = "true")
public class PlatformAdministratorBootstrapRunner implements ApplicationRunner {

    private static final long MAX_PASSWORD_FILE_BYTES = 4096;

    private final AccountService accountService;
    private final PasswordEncoder passwordEncoder;

    @Value("${platform.bootstrap.username:}")
    private String username;

    @Value("${platform.bootstrap.email:}")
    private String email;

    @Value("${platform.bootstrap.password-file:}")
    private String passwordFile;

    /**
     * Validates explicit bootstrap configuration, reads the local secret once and creates one account.
     */
    @Override
    public void run(ApplicationArguments args) {
        validateIdentityConfiguration();
        char[] password = readPasswordFile();
        try {
            validatePassword(password);
            accountService.createPlatformAdministrator(
                    username.trim(),
                    email.trim(),
                    passwordEncoder.encode(new String(password)));
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        log.info("Platform administrator bootstrap completed; disable the bootstrap runner before restart");
    }

    /** Validates non-secret identity settings without supplying built-in defaults. */
    private void validateIdentityConfiguration() {
        if (!StringUtils.hasText(username)
                || !StringUtils.hasText(email)
                || !StringUtils.hasText(passwordFile)) {
            throw new IllegalStateException("Platform administrator bootstrap configuration is incomplete");
        }
        if (!email.contains("@") || username.length() > 50 || email.length() > 100) {
            throw new IllegalStateException("Platform administrator bootstrap identity is invalid");
        }
    }

    /** Reads a bounded regular non-symlink password file from the local host. */
    private char[] readPasswordFile() {
        Path path = Path.of(passwordFile).toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) == 0
                    || Files.size(path) > MAX_PASSWORD_FILE_BYTES) {
                throw new IllegalStateException("Platform administrator bootstrap password file is invalid");
            }
            validatePosixPermissions(path);
            return Files.readString(path, StandardCharsets.UTF_8).stripTrailing().toCharArray();
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("Platform administrator bootstrap password file is unavailable");
        }
    }

    /** Rejects group/other access where POSIX permissions are available. */
    private void validatePosixPermissions(Path path) throws IOException {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            boolean shared = permissions.stream().anyMatch(permission -> switch (permission) {
                case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
                        OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
                default -> false;
            });
            if (shared) {
                throw new IllegalStateException("Platform administrator bootstrap password file permissions are too broad");
            }
        } catch (UnsupportedOperationException exception) {
            // Non-POSIX filesystems rely on process-level ACLs and the no-symlink regular-file boundary above.
        }
    }

    /** Enforces a deterministic minimum password-strength contract without logging secret material. */
    private void validatePassword(char[] password) {
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean symbol = false;
        for (char value : password) {
            lower |= Character.isLowerCase(value);
            upper |= Character.isUpperCase(value);
            digit |= Character.isDigit(value);
            symbol |= !Character.isLetterOrDigit(value);
        }
        if (password.length < 16 || !lower || !upper || !digit || !symbol) {
            throw new IllegalStateException("Platform administrator bootstrap password does not meet policy");
        }
    }
}
