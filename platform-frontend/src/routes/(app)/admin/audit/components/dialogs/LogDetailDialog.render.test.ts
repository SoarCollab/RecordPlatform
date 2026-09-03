import { cleanup, render } from "@testing-library/svelte";
import { afterEach, describe, expect, it } from "vitest";
import LogDetailDialog from "./LogDetailDialog.svelte";

describe("LogDetailDialog", () => {
  afterEach(cleanup);

  it("separates HTTP method from handler and formats structured payloads", () => {
    const view = render(LogDetailDialog, {
      open: true,
      loading: false,
      onOpenChange: () => undefined,
      log: {
        id: "ext-log-1",
        userId: "user-1",
        username: "tester",
        action: "查询",
        module: "系统审计",
        ip: "10.1.0.2",
        status: 0,
        duration: 9,
        createTime: "2026-09-03 09:45:50",
      },
      detail: {
        id: "ext-log-1",
        module: "系统审计",
        operationType: "查询",
        description: "获取审计概览数据",
        method: "cn.flying.controller.SysAuditController.getAuditOverview",
        requestUrl: "/record-platform/api/v1/system/audit/overview",
        requestMethod: "GET",
        requestIp: "10.1.0.2",
        requestParam: '{"page":1}',
        responseResult: '{"code":200}',
        status: 0,
        username: "tester",
        operationTime: "2026-09-03 09:45:50",
        executionTime: 9,
      },
    });

    expect(view.getByText("HTTP 请求方式")).toBeTruthy();
    expect(view.getByText("GET")).toBeTruthy();
    expect(view.getByText("处理方法")).toBeTruthy();
    expect(
      view.getByText(
        "cn.flying.controller.SysAuditController.getAuditOverview",
      ),
    ).toBeTruthy();
    expect(view.getByText("操作描述")).toBeTruthy();
    expect(view.getByText("操作时间")).toBeTruthy();
    expect(view.getByText(/"page": 1/)).toBeTruthy();
    expect(view.getByText(/"code": 200/)).toBeTruthy();
    expect(view.getByText("9ms")).toBeTruthy();
    expect(view.getByText("错误信息")).toBeTruthy();
    expect(view.getByTestId("audit-error-message").textContent).toBe("-");
  });

  it("shows a placeholder instead of fabricating zero duration", () => {
    const view = render(LogDetailDialog, {
      open: true,
      loading: false,
      onOpenChange: () => undefined,
      log: null,
      detail: {
        id: "ext-log-empty",
        module: "系统审计",
        operationType: "查询",
        status: 0,
      },
    });

    const durationLabel = view.getByText("执行耗时");
    expect(durationLabel.parentElement?.textContent).toContain("-");
    expect(durationLabel.parentElement?.textContent).not.toContain("0ms");
  });
});
