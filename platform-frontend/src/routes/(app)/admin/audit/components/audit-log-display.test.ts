import { describe, expect, it } from "vitest";
import { formatAuditPayload, mapSensitiveOperation } from "./audit-log-display";

describe("audit log display helpers", () => {
  it("maps a sensitive operation into the shared table contract", () => {
    expect(
      mapSensitiveOperation({
        id: "ext-log-1",
        module: "权限管理",
        operationType: "授权",
        requestIp: "10.1.0.2",
        status: 0,
        userId: "user-1",
        username: "tester",
        operationTime: "2026-09-03 09:45:50",
        executionTime: 9,
      }),
    ).toEqual(
      expect.objectContaining({
        id: "ext-log-1",
        action: "授权",
        module: "权限管理",
        ip: "10.1.0.2",
        duration: 9,
        createTime: "2026-09-03 09:45:50",
      }),
    );
  });

  it("formats valid JSON and preserves plain text", () => {
    expect(formatAuditPayload('{"status":"ok","count":2}')).toBe(
      '{\n  "status": "ok",\n  "count": 2\n}',
    );
    expect(formatAuditPayload("plain response")).toBe("plain response");
    expect(formatAuditPayload("  ")).toBe("-");
  });
});
