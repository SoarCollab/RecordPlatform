package cn.flying.documentation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StartupDocumentationTest {

    /**
     * 验证手动启动文档会导出 .env 变量，确保 Java 子进程能继承运行配置。
     */
    @Test
    @DisplayName("should export env variables before manual Java startup")
    void shouldExportEnvVariablesBeforeManualJavaStartup() throws IOException {
        String readme = Files.readString(resolveProjectRoot().resolve("README.md"));

        assertTrue(readme.contains("set -a"), "README should enable automatic export before sourcing .env");
        assertTrue(readme.contains("source .env"), "README should source .env for manual startup");
        assertTrue(readme.contains("set +a"), "README should disable automatic export after sourcing .env");
    }

    /**
     * 解析从仓库根、platform-backend 或 backend-web 目录执行测试时的项目根目录。
     *
     * @return 项目根目录
     */
    private Path resolveProjectRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (Path candidate : List.of(cwd, cwd.resolve(".."), cwd.resolve("../.."))) {
            Path normalized = candidate.normalize();
            if (Files.isRegularFile(normalized.resolve("README.md"))) {
                return normalized;
            }
        }
        fail("Cannot resolve project root from " + cwd);
        return cwd;
    }
}
