# 测试记录

- 2025-02-14: `mvn -pl monitor-common,platform-monitor-server test`（失败）——Mockito 插件初始化失败，父模块已有测试依赖无法运行。
- 2025-02-14: `mvn -pl monitor-common -DskipTests install`（失败）——Jacoco 覆盖率校验阻塞。
- 2025-02-14: `mvn -pl monitor-common -DskipTests -Djacoco.skip=true install`（失败）——无权限写入默认 Maven 仓库。
- 2025-02-14: `mvn -pl monitor-common -DskipTests -Djacoco.skip=true -Dmaven.repo.local=../m2-repo install`（失败）——受限网络无法解析父 POM。
- 2025-02-14: `mvn test`（在 monitor-data-service 模块）失败——monitor-common 依赖未安装。

> 受限于环境（仓库写权限与外部网络不可用），尚未在当前环境下成功执行 Maven 测试。已新增单元测试，建议在有权限的环境中运行 `mvn -pl monitor-data-service test` 验证。
