# 验证说明

由于环境限制（无法写入默认 Maven 仓库且无法访问外部仓库），当前未能在本地完成 Maven 测试执行。已补充 `monitor-data-service` 的单元测试，建议在具备 Maven 权限的环境中运行：

```bash
mvn -pl monitor-data-service test
```

若需同时验证 server 模块，可先安装 `monitor-common` 依赖（具备网络与仓库写权限的环境下）：

```bash
mvn -pl monitor-common,platform-monitor-server test
```

本地所做修改已完成编译级检查，核心逻辑通过单元测试覆盖，但实际执行需在受控环境中完成。
