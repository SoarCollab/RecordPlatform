import { describe, it, expect } from "vitest";
import {
  required,
  minLength,
  maxLength,
  email,
  phone,
  username,
  password,
  confirmPassword,
  fileSize,
  fileType,
  validate,
} from "./validation";

describe("validation utils", () => {
  describe("required", () => {
    it("should fail on null", () => {
      const result = required(null);
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.message).toContain("不能为空");
      }
    });

    it("should fail on undefined", () => {
      const result = required(undefined);
      expect(result.valid).toBe(false);
    });

    it("should fail on empty string", () => {
      const result = required("");
      expect(result.valid).toBe(false);
    });

    it("should pass on valid string", () => {
      const result = required("hello");
      expect(result.valid).toBe(true);
    });

    it("should pass on number zero", () => {
      const result = required(0);
      expect(result.valid).toBe(true);
    });

    it("should use custom field name in error message", () => {
      const result = required("", "用户名");
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.message).toContain("用户名");
      }
    });
  });

  describe("minLength", () => {
    it("should fail when string is too short", () => {
      const result = minLength("abc", 5);
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.message).toContain("5");
      }
    });

    it("should pass when string meets minimum", () => {
      const result = minLength("abcde", 5);
      expect(result.valid).toBe(true);
    });

    it("should pass when string exceeds minimum", () => {
      const result = minLength("abcdefgh", 5);
      expect(result.valid).toBe(true);
    });
  });

  describe("maxLength", () => {
    it("should fail when string is too long", () => {
      const result = maxLength("abcdefghij", 5);
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.message).toContain("5");
      }
    });

    it("should pass when string meets maximum", () => {
      const result = maxLength("abcde", 5);
      expect(result.valid).toBe(true);
    });

    it("should pass when string is shorter than maximum", () => {
      const result = maxLength("abc", 5);
      expect(result.valid).toBe(true);
    });
  });

  describe("email", () => {
    it.each([
      ["valid@email.com", true],
      ["user.name@domain.org", true],
      ["user+tag@example.co.uk", true],
      ["invalid", false],
      ["no@domain", false],
      ["@nodomain.com", false],
      ["spaces @email.com", false],
      ["", false],
    ])('should validate "%s" as %s', (input, expected) => {
      const result = email(input);
      expect(result.valid).toBe(expected);
    });
  });

  describe("phone", () => {
    it.each([
      ["13812345678", true],
      ["15912345678", true],
      ["18612345678", true],
      ["12812345678", false], // 12x is not valid
      ["1381234567", false], // too short
      ["138123456789", false], // too long
      ["23812345678", false], // doesn't start with 1
      ["abc12345678", false],
    ])('should validate "%s" as %s', (input, expected) => {
      const result = phone(input);
      expect(result.valid).toBe(expected);
    });
  });

  describe("username", () => {
    it.each([
      ["user", true], // minimum 4 chars
      ["user_name", true],
      ["User123", true],
      ["user_name_123", true],
      ["abcdefghijklmnopqrst", true], // 20 chars - maximum
      ["usr", false], // too short (3 chars)
      ["abcdefghijklmnopqrstu", false], // too long (21 chars)
      ["user name", false], // contains space
      ["user@name", false], // contains special char
      ["user-name", false], // contains hyphen
    ])('should validate "%s" as %s', (input, expected) => {
      const result = username(input);
      expect(result.valid).toBe(expected);
    });
  });

  describe("password", () => {
    it("should fail when too short", () => {
      const result = password("Pass1");
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.message).toContain("8");
      }
    });

    it("should fail when no letter", () => {
      const result = password("12345678");
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.message).toContain("字母");
      }
    });

    it("should fail when no number", () => {
      const result = password("abcdefgh");
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.message).toContain("数字");
      }
    });

    it("should pass with letter and number", () => {
      const result = password("Password1");
      expect(result.valid).toBe(true);
    });

    it("should pass with special characters", () => {
      const result = password("Pass@word1");
      expect(result.valid).toBe(true);
    });
  });

  describe("confirmPassword", () => {
    it("should pass when passwords match", () => {
      const result = confirmPassword("Password1", "Password1");
      expect(result.valid).toBe(true);
    });

    it("should fail when passwords do not match", () => {
      const result = confirmPassword("Password1", "Password2");
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.message).toContain("不一致");
      }
    });
  });

  describe("fileSize", () => {
    it("should pass when file is within limit", () => {
      const result = fileSize(5 * 1024 * 1024, 10); // 5MB, limit 10MB
      expect(result.valid).toBe(true);
    });

    it("should pass when file equals limit", () => {
      const result = fileSize(10 * 1024 * 1024, 10); // 10MB, limit 10MB
      expect(result.valid).toBe(true);
    });

    it("should fail when file exceeds limit", () => {
      const result = fileSize(15 * 1024 * 1024, 10); // 15MB, limit 10MB
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.message).toContain("10MB");
      }
    });
  });

  describe("fileType", () => {
    it("should pass for allowed type", () => {
      const result = fileType("document.pdf", ["pdf", "doc", "docx"]);
      expect(result.valid).toBe(true);
    });

    it("should fail for disallowed type", () => {
      const result = fileType("script.exe", ["pdf", "doc", "docx"]);
      expect(result.valid).toBe(false);
    });

    it("should be case insensitive", () => {
      const result = fileType("DOCUMENT.PDF", ["pdf", "doc"]);
      expect(result.valid).toBe(true);
    });

    it("should handle files without extension", () => {
      const result = fileType("noextension", ["pdf", "doc"]);
      expect(result.valid).toBe(false);
    });

    it("should handle files with multiple dots", () => {
      const result = fileType("file.name.pdf", ["pdf"]);
      expect(result.valid).toBe(true);
    });
  });

  describe("validate (composite)", () => {
    it("should pass when all validators pass", () => {
      const result = validate("test@email.com", [
        (v) => required(v),
        (v) => email(v as string),
      ]);
      expect(result.valid).toBe(true);
    });

    it("should fail on first failing validator", () => {
      const result = validate("", [
        (v) => required(v),
        (v) => email(v as string),
      ]);
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.message).toContain("不能为空");
      }
    });

    it("should return second validator error if first passes", () => {
      const result = validate("invalid-email", [
        (v) => required(v),
        (v) => email(v as string),
      ]);
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.message).toContain("邮箱");
      }
    });

    it("should pass with empty validator array", () => {
      const result = validate("anything", []);
      expect(result.valid).toBe(true);
    });
  });
});
