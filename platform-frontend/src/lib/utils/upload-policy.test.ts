import { describe, expect, it } from "vitest";
import type { UploadPolicyVO } from "$api/types";
import {
  buildUploadAccept,
  describeUploadPolicy,
  normalizeUploadMime,
  validateFileAgainstUploadPolicy,
} from "./upload-policy";

const policy: UploadPolicyVO = {
  maxFileSizeBytes: 1024,
  fileTypes: [
    {
      extension: "pdf",
      category: "document",
      categoryLabel: "文档",
      previewMode: "pdf",
      mimeTypes: ["application/pdf"],
    },
    {
      extension: "svg",
      category: "text",
      categoryLabel: "文本/代码",
      previewMode: "text",
      mimeTypes: ["image/svg+xml", "text/plain"],
    },
  ],
};

describe("upload policy", () => {
  it("builds accept and category copy from the server response", () => {
    expect(buildUploadAccept(policy)).toBe(".pdf,.svg");
    expect(describeUploadPolicy(policy)).toBe("文档、文本/代码");
  });

  it("accepts empty and generic MIME but rejects concrete conflicts", () => {
    expect(
      validateFileAgainstUploadPolicy(
        { name: "paper.pdf", size: 10, type: "" },
        policy,
      ),
    ).toBeNull();
    expect(
      validateFileAgainstUploadPolicy(
        {
          name: "paper.PDF",
          size: 10,
          type: "application/octet-stream",
        },
        policy,
      ),
    ).toBeNull();
    expect(
      validateFileAgainstUploadPolicy(
        { name: "paper.pdf", size: 10, type: "text/plain" },
        policy,
      ),
    ).toContain("不匹配；可上传类型：文档、文本/代码");
  });

  it("rejects missing, unknown, and oversized files before upload", () => {
    expect(
      validateFileAgainstUploadPolicy(
        { name: "README", size: 10, type: "text/plain" },
        policy,
      ),
    ).toContain("缺少有效扩展名；可上传类型：文档、文本/代码");
    expect(
      validateFileAgainstUploadPolicy(
        { name: "payload.exe", size: 10, type: "image/png" },
        policy,
      ),
    ).toContain(".exe；可上传类型：文档、文本/代码");
    expect(
      validateFileAgainstUploadPolicy(
        { name: "paper.pdf", size: 2048, type: "application/pdf" },
        policy,
      ),
    ).toContain("1024");
  });

  it("normalizes MIME parameters", () => {
    expect(normalizeUploadMime(" Application/PDF ; charset=binary")).toBe(
      "application/pdf",
    );
  });
});
